package com.arkiv.player.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppFragmentedMp4Muxer
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import androidx.media3.common.util.Util
import androidx.media3.transformer.ProgressHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.arkiv.player.cast.CastAudioChoice
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Rewrites an MPEG-TS into an MP4 without touching the video or the audio.
 *
 * Nothing is re-encoded: media3's Transformer copies the compressed samples straight across when
 * the format already fits the output container, so this costs I/O and almost no CPU, and the
 * picture is bit for bit what it was. It is `ffmpeg -c copy`, using a library the project already
 * depends on -- ffmpeg-kit was retired in January 2025, and re-adding a native blob right after
 * libVLC was removed from this branch would undo that.
 *
 * Why it is worth doing at all: the Cast receiver refuses a bare transport stream outright, and
 * even fed the same bytes as HLS segments it still has to derive every frame's presentation time
 * from PTS/DTS. On the KALLEY that produced hundreds of `Failed to get frame timestamps` a minute
 * and visible judder while the decoder itself was healthy. An MP4 states each sample's timing in
 * a table, so there is nothing left to derive.
 *
 * **Writes to a `.part` file and renames on success.** A half-written remux that looked finished
 * would be handed to the TV as a complete title and truncate mid-playback; a rename is atomic on
 * the same filesystem, so what exists under the final name is always whole.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class TsRemuxer(
    private val context: Context,
    cacheDir: File,
    /**
     * Where the export actually runs, and it must OUTLIVE whoever asked for it. Kicking it off
     * inside a `LaunchedEffect` was measured to fail: casting churns those keys, each change
     * cancelled a remux minutes from finishing and the next one started from zero, so it never
     * completed once (`remux cancelled, partial file removed` at 44 s, then `remux starts` again).
     */
    private val scope: CoroutineScope,
    /**
     * Whether the remux filed under a key should hold off reading its input for now: how a cast
     * keeps the phone from downloading the title far ahead of the TV over the radio the TV needs
     * (see [RemuxPacing]). Wired to `RemuxHlsServer.remuxShouldWait`; never true for a remux that
     * is not being cast.
     */
    private val pace: (key: String) -> Boolean = { false },
    /** Is the remux of `key` what a TV is playing right now? Then a failed export is not run again. */
    private val onAir: (key: String) -> Boolean = { false },
    /**
     * May a cast remux start near the phone's position instead of at 0:00 ([startPoint])? The
     * runtime switch `debug.kino.remux_seek_start` (anything but `off` = yes), read off the main thread.
     */
    private val seekStartEnabled: () -> Boolean = { true },
) {

    private val folder = File(cacheDir, RemuxPolicy.FOLDER)

    /**
     * Exports in flight, by key. A second caller for the same title joins the one already running
     * instead of starting a rival export over the same output file -- and because the job lives in
     * [scope], a caller giving up on the wait does not take the export with it.
     */
    private val activeExports = ConcurrentHashMap<String, Deferred<RemuxResult>>()

    /**
     * How far along the export is, 0..100, or -1 when nothing is running.
     *
     * Exists because the wait is the whole cost of this approach: a person staring at a still
     * screen for four minutes with no sign of life assumes it hung, and they would be right to.
     */
    private val _progress = MutableStateFlow(-1)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    /** Result of asking for a remux. `Done` carries a file that is complete and playable. */
    sealed interface RemuxResult {
        data class Done(val file: File) : RemuxResult
        /** [code] is media3's `ExportException.errorCode` (0 when it is not an export error). */
        data class Failed(val reason: String, val code: Int = 0, val writtenBytes: Long = 0L) : RemuxResult
    }

    /**
     * Where each mid-file remux starts reading ([TsStart]), by its key without the audio part
     * ([RemuxPolicy.startKey]): every audio of a title starts on the same keyframe. Located once
     * per title and grid point ([startPoint]), read by the export and by [startMsOf].
     */
    private val starts = ConcurrentHashMap<String, TsStart>()

    /**
     * The grid point (ms) the cast remux of [originKey] (with [audioOrdinal]) should start at for a
     * TV starting at [positionMs], with its [TsStart] located through [inputUri] -- or 0, a remux
     * from the top: switched off, too near the start to be worth it, a finished remux from the top
     * already on disk (nothing to wait for), or a stream the start cannot be found in. Suspends for
     * the few ranged reads that takes (see [TsStartLocator]), at most [LOCATE_BUDGET_MS].
     */
    suspend fun startPoint(inputUri: String, originKey: String, audioOrdinal: Int?, positionMs: Long): Long {
        val grid = TsStartPoint.gridMs(positionMs)
        if (grid <= 0L) return 0L
        if (alreadyDone(RemuxPolicy.keyFrom(originKey, 0L, audioOrdinal)) != null) {
            Log.w(TAG, "start point: a whole remux from the top is on disk, using it")
            return 0L
        }
        val startKey = RemuxPolicy.keyFrom(originKey, grid)
        if (starts.containsKey(startKey)) return grid
        val enabled = withContext(Dispatchers.IO) { runCatching(seekStartEnabled).getOrDefault(true) }
        if (!enabled) {
            Log.w(TAG, "start point: debug.kino.remux_seek_start=off → remux from 0:00")
            return 0L
        }
        val t0 = System.currentTimeMillis()
        val start = withTimeoutOrNull(LOCATE_BUDGET_MS) {
            runInterruptible(Dispatchers.IO) { locate(inputUri, grid) }
        }
        val ms = System.currentTimeMillis() - t0
        if (start == null) {
            Log.w(TAG, "start point for ${grid}ms not found in ${ms}ms → remux from 0:00")
            return 0L
        }
        starts[startKey] = start
        Log.w(TAG, "start point: phone at ${positionMs}ms → grid ${grid}ms → $start, found in ${ms}ms")
        return grid
    }

    /** Where the remux filed under [key] begins in the title, in ms: its keyframe's time, 0 from the top. */
    fun startMsOf(key: String): Long =
        if (RemuxPolicy.fromInKey(key) <= 0L) 0L else starts[RemuxPolicy.startKey(key)]?.startMs ?: 0L

    /** [TsStartLocator] over ranged reads of [inputUri] (the loopback proxy, which adds the CDN's headers). */
    private fun locate(inputUri: String, gridMs: Long): TsStart? {
        val source = remuxHttp().createDataSource()
        val uri = android.net.Uri.parse(inputUri)
        fun spec(offset: Long, size: Long) = DataSpec.Builder().setUri(uri).setPosition(offset).setLength(size).build()
        fun range(offset: Long, size: Int): ByteArray? = runCatching {
            try {
                source.open(spec(offset, size.toLong()))
                val out = java.io.ByteArrayOutputStream(size)
                val buf = ByteArray(64 * 1024)
                while (out.size() < size) {
                    val n = source.read(buf, 0, minOf(buf.size, size - out.size()))
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } finally {
                runCatching { source.close() }
            }
        }.getOrNull()
        val total = runCatching {
            try {
                source.open(spec(0L, 1L))
                val headers = source.responseHeaders.entries
                FileWindow.totalFromContentRange(headers.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull())
            } finally {
                runCatching { source.close() }
            }
        }.getOrDefault(0L)
        if (total <= 0L) {
            Log.w(TAG, "start point: the input's length is unknown")
            return null
        }
        return TsStartLocator(total, ::range) { Log.i(TAG, "start point: $it") }.locate(gridMs)
    }

    /** The remux's own HTTP reads: patient, and marked for the proxy so it is patient with the CDN too (REMUX_HEADER). */
    private fun remuxHttp(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(RemuxPolicy.INPUT_CONNECT_MS)
        .setReadTimeoutMs(RemuxPolicy.INPUT_READ_MS)
        .setDefaultRequestProperties(mapOf(ArchiveCacheProxy.REMUX_HEADER to "1"))

    /** The finished remux for [key] if one is already on disk, or null. */
    fun alreadyDone(key: String): File? =
        File(folder, RemuxPolicy.fileName(key)).takeIf { it.exists() && it.length() > 0 }

    /**
     * The remux for [key] as it stands, finished or still being written, with a flag saying
     * which. A fragmented MP4 is playable before it is complete, so the half-written one is worth
     * handing out -- that is the whole reason for fragmenting it.
     */
    fun inProgress(key: String): Pair<File, Boolean>? {
        val done = File(folder, RemuxPolicy.fileName(key))
        if (done.exists() && done.length() > 0) return done to true
        val partial = File(folder, "${done.name}.part")
        return if (partial.exists() && partial.length() > 0) partial to false else null
    }

    /**
     * What an earlier, stopped remux of [key] left on disk, while [key] is unfinished: served at
     * once by the cast while a new run catches up with it. See [RemuxLeftover].
     */
    fun leftover(key: String): File? {
        val done = File(folder, RemuxPolicy.fileName(key))
        return RemuxLeftover.usable(done, RemuxLeftover.fileFor(done))
    }

    /**
     * Remuxes [inputUri] into the cache and returns the finished file.
     *
     * Idempotent: a remux already on disk is returned without redoing the work. Suspends until the
     * export finishes, and cancelling the coroutine cancels the export and removes the partial
     * file -- an abandoned `.part` would otherwise sit there forever, since nothing else knows
     * what it belonged to.
     *
     * Transformer needs a Looper, so the export is driven on the main thread; the actual work
     * happens on its own threads, so this does not block the UI.
     */
    suspend fun remux(inputUri: String, key: String, audio: CastAudioChoice? = null): RemuxResult {
        alreadyDone(key)?.let {
            Log.i(TAG, "already remuxed: ${it.name} (${it.length()}B), reusing it")
            return RemuxResult.Done(it)
        }
        val job = activeExports.computeIfAbsent(key) {
            scope.async { exportRetrying(inputUri, key, audio) }
                .also { j -> j.invokeOnCompletion { activeExports.remove(key, j) } }
        }
        return job.await()
    }

    /** [export], started again when it fails at the start for a reason worth retrying (see [RemuxPolicy.retryExport]). */
    private suspend fun exportRetrying(inputUri: String, key: String, audio: CastAudioChoice?): RemuxResult {
        var attempt = 0
        while (true) {
            val result = export(inputUri, key, audio)
            if (result !is RemuxResult.Failed || !RemuxPolicy.retryExport(attempt, result.code, onAir(key))) {
                return result
            }
            val wait = RemuxPolicy.retryDelayMs(attempt)
            Log.w(TAG, "remux failed at the start (code=${result.code}, ${result.writtenBytes}B written) → attempt ${attempt + 2} in ${wait}ms")
            delay(wait)
            attempt++
        }
    }

    /**
     * Where Transformer reads the input from ([RemuxExport.assetLoaderFactory]): through
     * [PacedDataSource], which holds the reads while [pace] says the cast is far enough ahead, and
     * from [start]'s keyframe on when the remux begins mid-file ([TsStartDataSource]).
     */
    private fun assetLoaderFactory(audio: CastAudioChoice?, key: String, start: TsStart?): AssetLoader.Factory {
        val plain = DefaultDataSource.Factory(context, remuxHttp())
        val read: DataSource.Factory = start?.let { TsStartDataSource.Factory(plain, it) } ?: plain
        return RemuxExport.assetLoaderFactory(context, PacedDataSource.Factory(read) { pace(key) }, audio)
    }

    /** The export itself. One per key at a time; see [remux]. */
    private suspend fun export(inputUri: String, key: String, audio: CastAudioChoice?): RemuxResult {
        if (!folder.exists() && !folder.mkdirs()) {
            return RemuxResult.Failed("could not create ${folder.path}")
        }
        makeRoom()
        val destination = File(folder, RemuxPolicy.fileName(key))
        val partial = File(folder, "${destination.name}.part")
        // What a stopped run wrote is not thrown away: it becomes the leftover the next cast starts
        // on while this run catches up (see RemuxLeftover). This run always writes from zero.
        RemuxLeftover.rotate(partial, RemuxLeftover.fileFor(destination))

        val t0 = System.currentTimeMillis()
        Log.w(TAG, "remux starts → ${destination.name} · audio=${audio?.let { "#${it.ordinal} ${it.id}/${it.language}" } ?: "default"}")

        // A remux keyed past 0:00 reads from its start point's keyframe (see TsStart); one whose
        // start point is not known (the process restarted between locating it and this) has nothing
        // to read from, and failing it sends the cast to its fallback instead of a wrong timeline.
        val start = if (RemuxPolicy.fromInKey(key) > 0L) {
            starts[RemuxPolicy.startKey(key)] ?: return RemuxResult.Failed("no start point for ${destination.name}")
        } else {
            null
        }
        start?.let { Log.w(TAG, "remux reads from byte ${it.byteOffset}: the title from ${it.startMs}ms") }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = RemuxExport.transformer(context, assetLoaderFactory(audio, key, start), startsMidFile = start != null)
                    .buildUpon()
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            val ok = runCatching { partial.renameTo(destination) }.getOrDefault(false)
                            val ms = System.currentTimeMillis() - t0
                            if (ok) {
                                Log.w(
                                    TAG,
                                    "remux done in ${ms}ms → ${destination.name} (${destination.length()}B" +
                                        (result.durationMs.takeIf { it > 0 }?.let { ", ${it}ms" } ?: "") + ")",
                                )
                                if (cont.isActive) cont.resume(RemuxResult.Done(destination))
                            } else {
                                runCatching { partial.delete() }
                                Log.w(TAG, "remux finished but the rename failed")
                                if (cont.isActive) cont.resume(RemuxResult.Failed("rename failed"))
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            val written = runCatching { partial.length() }.getOrDefault(0L)
                            // A TV playing it keeps what was written (until the cast falls back
                            // or ends); deleted, its next segment 404'd. A later run reuses it.
                            if (!onAir(key)) runCatching { partial.delete() }
                            // The code matters more than the message: it tells "this device cannot"
                            // from "this file cannot", and only the second is worth giving up on.
                            Log.w(TAG, "remux failed (code=${exception.errorCode}, ${written}B written): ${exception.message}", exception)
                            if (cont.isActive) cont.resume(RemuxResult.Failed("error ${exception.errorCode}", exception.errorCode, written))
                        }
                    })
                    .build()

                // Only reached if [scope] itself is cancelled -- the app is going away. A caller
                // that stops waiting no longer lands here, which is the whole point of running in
                // an outer scope.
                cont.invokeOnCancellation {
                    runCatching { transformer.cancel() }
                    // The partial file is KEPT. A fragmented MP4 stops at a fragment boundary, so
                    // what is on disk is a valid, playable prefix -- casting the same title again
                    // starts on it immediately instead of converting from zero.
                    Log.w(TAG, "remux stopped, keeping ${partial.length()}B already written")
                }

                // Progress, polled: Transformer has no callback for it. On the main thread
                // because that is where the transformer lives, and cheap -- twice a second.
                val holder = ProgressHolder()
                scope.launch(Dispatchers.Main) {
                    while (isActive && cont.isActive) {
                        val state = runCatching { transformer.getProgress(holder) }.getOrNull()
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            _progress.value = holder.progress
                        }
                        delay(500)
                    }
                    _progress.value = -1
                }

                // Never clipped: a mid-file remux starts by READING from its keyframe (the data
                // source above), not by Transformer cutting a timeline, which measured the tracks
                // half a second apart (see TsStart).
                val input = MediaItem.fromUri(inputUri)

                runCatching {
                    transformer.start(input, partial.absolutePath)
                }.onFailure {
                    runCatching { partial.delete() }
                    Log.w(TAG, "remux could not start: ${it.message}")
                    if (cont.isActive) cont.resume(RemuxResult.Failed(it.message ?: "could not start"))
                }
            }
        }
    }

    /**
     * Evicts the oldest remuxes until the cache is back under its ceiling.
     *
     * Runs before starting a new one rather than after finishing it: the point is to have room,
     * and discovering there was none only once a gigabyte is already written helps nobody.
     */
    private fun makeRoom() {
        val files = folder.listFiles().orEmpty().filter { it.isFile }
        val toDelete = RemuxPolicy.toDelete(
            files.map { Triple(it.name, it.length(), it.lastModified()) },
        )
        if (toDelete.isEmpty()) return
        var freed = 0L
        toDelete.forEach { name ->
            val f = File(folder, name)
            val bytes = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) freed += bytes
        }
        Log.w(TAG, "cache over its ceiling: dropped ${toDelete.size} file(s), freed ${freed}B")
    }

    /** The finished chunk [index] of [key], or null. */
    fun chunkDone(key: String, index: Int): File? =
        File(folder, RemuxPolicy.chunkFileName(key, index))
            .takeIf { it.exists() && it.length() > 0 }

    /**
     * Remuxes ONE chunk: the stretch of [inputUri] from [index] * CHUNK_SEC, lasting
     * CHUNK_SEC, into a complete mp4 of its own.
     *
     * Complete and NOT fragmented, which is the whole idea. A single fragmented file served while
     * it grew made the receiver recompute the duration from whatever fragments had arrived and
     * report a new one every second (`kDurationChanged 75.25 … 80.25`, read off its own log), so
     * playback chased an end that kept moving and stalled whenever it caught up. A finished chunk
     * states one duration and stays still; the receiver plays a queue of them back to back.
     *
     * The cut starts at a keyframe: video can only begin at one while audio can begin anywhere, so
     * an arbitrary cut point offsets the tracks against each other -- audible as the picture
     * running behind the sound.
     */
    suspend fun remuxChunk(inputUri: String, key: String, index: Int): RemuxResult {
        chunkDone(key, index)?.let { return RemuxResult.Done(it) }
        val chunkKey = "$key##$index"
        val job = activeExports.computeIfAbsent(chunkKey) {
            scope.async { exportChunk(inputUri, key, index) }
                .also { j -> j.invokeOnCompletion { activeExports.remove(chunkKey) } }
        }
        return job.await()
    }

    private suspend fun exportChunk(inputUri: String, key: String, index: Int): RemuxResult {
        if (!folder.exists() && !folder.mkdirs()) {
            return RemuxResult.Failed("could not create ${folder.path}")
        }
        // Bound the chunk path too: without this, casting via the chunk queue accumulated
        // `<hash>-<index>.mp4` files with no eviction ever (only export() called makeRoom), so the
        // folder grew until the disk filled -- the source of the reported storage bloat + SQLITE_FULL.
        makeRoom()
        val destination = File(folder, RemuxPolicy.chunkFileName(key, index))
        val partial = File(folder, "${destination.name}.part")
        runCatching { partial.delete() }
        val fromMs = index * RemuxPolicy.CHUNK_SEC * 1000L
        val toMs = fromMs + RemuxPolicy.CHUNK_SEC * 1000L
        val t0 = System.currentTimeMillis()

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    // Plain mp4, not fragmented: a chunk is finished before it is ever served.
                    .setMuxerFactory(InAppMp4Muxer.Factory())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            val ok = runCatching { partial.renameTo(destination) }.getOrDefault(false)
                            Log.w(
                                TAG,
                                "chunk $index [${fromMs}..${toMs}ms] " +
                                    (if (ok) "done in ${System.currentTimeMillis() - t0}ms (${destination.length()}B)"
                                    else "finished but the rename failed"),
                            )
                            if (cont.isActive) {
                                cont.resume(if (ok) RemuxResult.Done(destination) else RemuxResult.Failed("rename"))
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            runCatching { partial.delete() }
                            Log.w(TAG, "chunk $index failed (code=${exception.errorCode}): ${exception.message}", exception)
                            if (cont.isActive) cont.resume(RemuxResult.Failed("error ${exception.errorCode}"))
                        }
                    })
                    .build()

                cont.invokeOnCancellation {
                    runCatching { transformer.cancel() }
                    runCatching { partial.delete() }
                }

                val input = MediaItem.Builder()
                    .setUri(inputUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(fromMs)
                            .setEndPositionMs(toMs)
                            // Both tracks must begin at the same instant, or the audio runs ahead.
                            .setStartsAtKeyFrame(true)
                            .build(),
                    )
                    .build()

                runCatching { transformer.start(input, partial.absolutePath) }.onFailure {
                    runCatching { partial.delete() }
                    Log.w(TAG, "chunk $index could not start: ${it.message}")
                    if (cont.isActive) cont.resume(RemuxResult.Failed(it.message ?: "could not start"))
                }
            }
        }
    }

    /**
     * Stops the remux for [key] if one is running.
     *
     * Called when casting ends, because the remux converts the WHOLE title regardless of how much
     * is watched: casting ten minutes of a film otherwise downloads and converts all two hours of
     * it, which on mobile data is a gigabyte or more spent on something nobody is going to see.
     * Whatever was written is kept -- it is a valid prefix, and resuming the same title later
     * plays it straight away.
     */
    fun stop(key: String) {
        // Cancelling the export cancels its Transformer, which only takes calls on the main thread
        // (the cancellation handler runs on the caller's): a retired remux, a cast session's end
        // or a DLNA stop reach this from other threads, where the cancel threw and the export ran on.
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() != main) {
            android.os.Handler(main).post { stop(key) }
            return
        }
        val job = activeExports.remove(key) ?: return
        Log.w(TAG, "cast ended → stopping the remux of ${RemuxPolicy.fileName(key)}")
        job.cancel()
    }

    /**
     * Drops every remux on disk. Called at app startup (off the main thread, [ArkivApp]) and from
     * the settings screen / tests. Safe to nuke everything on startup: a cold start means a fresh
     * process, so no cast is in flight and every file is a leftover from an earlier session -- a
     * derived copy the app can always regenerate. This is what keeps the Chromecast remux cache from
     * accumulating gigabytes (the reported storage bloat + SQLITE_FULL), since makeRoom only evicts
     * when a NEW remux pushes over the 4 GB ceiling.
     */
    fun clear(): Int {
        val files = folder.listFiles().orEmpty()
        var n = 0
        files.forEach { if (runCatching { it.delete() }.getOrDefault(false)) n++ }
        Log.i(TAG, "cleared $n remuxed file(s)")
        return n
    }

    /** Bytes the remuxes are taking up, so the caller can decide when to clear them. */
    fun bytesOnDisk(): Long = folder.listFiles().orEmpty().sumOf { it.length() }

    private companion object {
        const val TAG = "ArkivRemux"
        /** At most this long finding a start point; past it the remux starts from 0:00. */
        const val LOCATE_BUDGET_MS = 45_000L
    }
}

/**
 * How a cast remux is put together, apart from where it reads from: the extractors and the input's
 * error policy ([assetLoaderFactory]) and the Transformer itself ([transformer]). Shared by
 * [TsRemuxer] and the test that checks a mid-file remux keeps audio and video in step on the very
 * pipeline the app runs (`RemuxMidFileSyncTest`).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object RemuxExport {

    /**
     * Length of each fragment. Short enough that playback can begin almost immediately, long
     * enough that the overhead of a `moof` header per fragment stays negligible.
     */
    const val FRAGMENT_MS = 2_000L

    /**
     * Transformer's media source setup (the extractor flags `ExoPlayerAssetLoader.Factory` uses)
     * reading [input], with an [audio] choice picked by [AudioPinnedTrackSelector] instead of
     * Transformer's own selector.
     */
    fun assetLoaderFactory(context: Context, input: DataSource.Factory, audio: CastAudioChoice?): AssetLoader.Factory {
        val extractors = DefaultExtractorsFactory()
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            .setAmrExtractorFlags(AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
        val sources = DefaultMediaSourceFactory(input, extractors)
            .setEnableClippingInMediaPeriod(true)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(RemuxPolicy.INPUT_LOAD_RETRIES))
        val decoders = DefaultDecoderFactory.Builder(context).build()
        val bitmaps = DataSourceBitmapLoader.Builder(context).build()
        return if (audio == null) {
            DefaultAssetLoaderFactory(context, decoders, Clock.DEFAULT, sources, bitmaps)
        } else {
            DefaultAssetLoaderFactory(context, decoders, Clock.DEFAULT, sources, bitmaps) { ctx -> AudioPinnedTrackSelector(ctx, audio) }
        }
    }

    /**
     * The cast remux's Transformer. [startsMidFile]: the input begins at a keyframe in the middle
     * of the title ([TsStart]), and every audio sample before that keyframe is dropped
     * (`setEnsureFileStartsOnVideoFrameEnabled`): the muxer starts each track at decode time 0 on
     * its first sample, so audio left half a second ahead of the keyframe would play half a second early.
     */
    fun transformer(context: Context, assets: AssetLoader.Factory, startsMidFile: Boolean): Transformer =
        Transformer.Builder(context)
            // media3's OWN muxer, never the platform one. Transformer defaults to
            // `FrameworkMuxer`, which is `MediaMuxer` and underneath it libstagefright's
            // `MPEG4Writer` -- and that one ABORTS THE PROCESS on the HEVC samples coming
            // out of a Magis transport stream: `FORTIFY: write: count
            // 18446744073709551615 > SSIZE_MAX` (a sample size of -1 read as unsigned),
            // SIGABRT on the MPEG4Writer thread, measured 2026-09-12. A native abort is
            // not catchable, so the only defence is not to use that muxer. The in-app one
            // is pure Java, and it is also what can write fragmented MP4.
            .setMuxerFactory(
                // FRAGMENTED, so the file can be served WHILE it is written. A plain
                // MP4 keeps its index at the end, which is why casting one meant
                // waiting minutes for the whole title before a single frame reached
                // the TV. A fragmented one is a chain of self-contained pieces: the
                // receiver can start on the first while the rest is still arriving.
                InAppFragmentedMp4Muxer.Factory(FRAGMENT_MS),
            )
            .setAssetLoaderFactory(assets)
            // Only mid-file: from the top the tracks already start together, and the from-zero
            // remux is the measured-good path, left exactly as it was.
            .setEnsureFileStartsOnVideoFrameEnabled(startsMidFile)
            // No "no output sample in 10 s" abort. Transformer's watchdog killed remuxes
            // whose CDN took 7 s to open (`Muxer error`, `Abort: no output sample written
            // in the last 10000 milliseconds`, 2026-10-01), and pacing holds the
            // input on purpose for minutes. A dead input still fails: its reads time out
            // and the loader gives up after its retries.
            .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
            .build()
}
