package com.arkiv.player.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
) {

    private val folder = File(cacheDir, RemuxPolicy.FOLDER)

    /**
     * Length of each fragment. Short enough that playback can begin almost immediately, long
     * enough that the overhead of a `moof` header per fragment stays negligible.
     */
    private val FRAGMENT_MS = 2_000L

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
        data class Failed(val reason: String) : RemuxResult
    }

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
    suspend fun remux(inputUri: String, key: String): RemuxResult {
        alreadyDone(key)?.let {
            Log.i(TAG, "already remuxed: ${it.name} (${it.length()}B), reusing it")
            return RemuxResult.Done(it)
        }
        val job = activeExports.computeIfAbsent(key) {
            scope.async { export(inputUri, key) }
                .also { j -> j.invokeOnCompletion { activeExports.remove(key) } }
        }
        return job.await()
    }

    /** The export itself. One per key at a time; see [remux]. */
    private suspend fun export(inputUri: String, key: String): RemuxResult {
        if (!folder.exists() && !folder.mkdirs()) {
            return RemuxResult.Failed("could not create ${folder.path}")
        }
        makeRoom()
        val destination = File(folder, RemuxPolicy.fileName(key))
        val partial = File(folder, "${destination.name}.part")
        runCatching { partial.delete() }

        val t0 = System.currentTimeMillis()
        Log.w(TAG, "remux starts → ${destination.name}")

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    // media3's OWN muxer, never the platform one. Transformer defaults to
                    // `FrameworkMuxer`, which is `MediaMuxer` and underneath it libstagefright's
                    // `MPEG4Writer` -- and that one ABORTS THE PROCESS on the HEVC samples coming
                    // out of a Magis transport stream: `FORTIFY: write: count
                    // 18446744073709551615 > SSIZE_MAX` (a sample size of -1 read as unsigned),
                    // SIGABRT on the MPEG4Writer thread, measured 2026-09-12. A native abort is
                    // not catchable, so the only defence is not to use that muxer. The in-app one
                    // is pure Java, and it is also what can write fragmented MP4.
                    .setMuxerFactory(
                        InAppMuxer.Factory.Builder()
                            // FRAGMENTED, so the file can be served WHILE it is written. A plain
                            // MP4 keeps its index at the end, which is why casting one meant
                            // waiting minutes for the whole title before a single frame reached
                            // the TV. A fragmented one is a chain of self-contained pieces: the
                            // receiver can start on the first while the rest is still arriving.
                            .setOutputFragmentedMp4(true)
                            .setFragmentDurationMs(FRAGMENT_MS)
                            .build(),
                    )
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
                            runCatching { partial.delete() }
                            // The code matters more than the message: it tells "this device cannot"
                            // from "this file cannot", and only the second is worth giving up on.
                            Log.w(TAG, "remux failed (code=${exception.errorCode}): ${exception.message}")
                            if (cont.isActive) cont.resume(RemuxResult.Failed("error ${exception.errorCode}"))
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

                // Clipped when the key says so, so the result BEGINS where playback should.
                // The remux is cast as a live stream and a live stream has no timeline to seek
                // along, so a file that starts at the right place is the only way to land there.
                val fromMs = RemuxPolicy.fromInKey(key)
                val input = if (fromMs > 0L) {
                    Log.w(TAG, "remux starts at ${fromMs}ms, so nothing has to seek")
                    MediaItem.Builder()
                        .setUri(inputUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(fromMs)
                                // On a KEYFRAME. Video can only begin at one while audio can begin
                                // anywhere, so an arbitrary cut point starts the tracks at
                                // different instants -- heard on device as the sound running ahead
                                // of the picture. It is also what keeps the clip a sample copy
                                // rather than a re-encode.
                                .setStartsAtKeyFrame(true)
                                .build(),
                        )
                        .build()
                } else {
                    MediaItem.fromUri(inputUri)
                }

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
                    .setMuxerFactory(InAppMuxer.Factory.Builder().build())
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
                            Log.w(TAG, "chunk $index failed (code=${exception.errorCode}): ${exception.message}")
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
        val job = activeExports.remove(key) ?: return
        Log.w(TAG, "cast ended → stopping the remux of ${RemuxPolicy.fileName(key)}")
        job.cancel()
    }

    /** Drops every remux on disk. For the settings screen, and for tests. */
    fun clear(): Int {
        val files = folder.listFiles().orEmpty()
        var n = 0
        files.forEach { if (runCatching { it.delete() }.getOrDefault(false)) n++ }
        Log.i(TAG, "cleared $n remuxed file(s)")
        return n
    }

    /** Bytes the remuxes are taking up, so the caller can decide when to clear them. */
    fun bytesOnDisk(): Long = folder.listFiles().orEmpty().sumOf { it.length() }

    private companion object { const val TAG = "ArkivRemux" }
}
