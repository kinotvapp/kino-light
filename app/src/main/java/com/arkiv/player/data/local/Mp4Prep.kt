package com.arkiv.player.data.local

import android.util.Log
import com.arkiv.player.data.subtitles.SavedOnlineSubtitle
import com.arkiv.player.playback.Container
import com.arkiv.player.playback.VideoContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where a download stands on its way to being a faststart MP4 (see [Mp4Prep]). */
enum class PrepState {
    /** An MP4 with its SRT sidecars: converted, or one already. */
    READY,

    /** Not enough room to write the MP4 next to the original: kept as it is, tried again later. */
    NO_SPACE,

    /** Something an MP4 cannot carry as-is (AC-3 audio, MPEG-2 video, an unknown container): kept for good. */
    UNSUPPORTED,

    /** The conversion failed: the original is kept, tried again up to [Mp4PrepPolicy.MAX_ATTEMPTS] times. */
    FAILED,
}

/**
 * What a download's preparation left, in `<sanitize(episodeId)>.prep.json` next to it (so the prefix
 * sweep of `LocalDownloadManager.remove` takes it with the download, and no database migration is
 * needed). [pendingDelete]: originals already replaced by their MP4 that were still open (playing,
 * or served to a TV) when the MP4 was ready; deleted once nothing holds them.
 */
data class PrepMarker(
    val state: PrepState,
    val reason: String? = null,
    val attempts: Int = 0,
    val pendingDelete: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("state", state.name)
        .put("reason", reason ?: "")
        .put("attempts", attempts)
        .put("pendingDelete", JSONArray(pendingDelete))
        .toString()

    companion object {
        fun fromJson(s: String): PrepMarker? = runCatching {
            val o = JSONObject(s)
            val pending = o.optJSONArray("pendingDelete")
            PrepMarker(
                PrepState.valueOf(o.getString("state")),
                o.optString("reason").ifBlank { null },
                o.optInt("attempts"),
                (0 until (pending?.length() ?: 0)).map { pending!!.getString(it) },
            )
        }.getOrNull()

        fun fileFor(dir: File, episodeId: String): File = File(dir, "${LocalFilePaths.sanitize(episodeId)}.prep.json")
    }
}

/** The decisions of [Mp4Prep], pure so they are tested on the JVM. */
object Mp4PrepPolicy {

    enum class Decision {
        /** Already an MP4 (progressive or fragmented): only the subtitles are prepared. */
        NOT_NEEDED,
        CONVERT,
        /** Not enough free space for a second copy (plus [FreeSpacePolicy.MARGIN_BYTES]). */
        NO_SPACE,
        /** A container media3 has no extractor for, or none recognized at all. */
        UNSUPPORTED,
    }

    /** How many failed conversions a download gets before it is left as it is. */
    const val MAX_ATTEMPTS = 3

    /**
     * What to do with a download of [container] (by its bytes; null = not recognized) weighing
     * [sizeBytes] when the disk has [availableBytes]: the MP4 needs roughly the file's size again.
     */
    fun decide(container: Container?, sizeBytes: Long, availableBytes: Long): Decision = when (container) {
        Container.MP4 -> Decision.NOT_NEEDED
        null, Container.ASF, Container.OGG -> Decision.UNSUPPORTED
        Container.MPEGTS, Container.MATROSKA, Container.WEBM, Container.AVI, Container.MPEGPS ->
            if (FreeSpacePolicy.fits(availableBytes, sizeBytes)) Decision.CONVERT else Decision.NO_SPACE
    }

    /**
     * Whether a download whose last preparation left [marker] is worth (another) pass: never
     * prepared, or the disk was full then (it may not be now), or failed fewer than
     * [MAX_ATTEMPTS] times. READY and UNSUPPORTED are final.
     */
    fun wants(marker: PrepMarker?): Boolean = when (marker?.state) {
        null, PrepState.NO_SPACE -> true
        PrepState.FAILED -> marker.attempts < MAX_ATTEMPTS
        PrepState.READY, PrepState.UNSUPPORTED -> false
    }

    /** The MP4's name next to [original]: `<base>.mp4`, or `<base>.prepared.mp4` when the original is itself named `.mp4`. */
    fun outputFor(original: File): File {
        val base = original.nameWithoutExtension
        val name = if (original.extension.equals("mp4", ignoreCase = true)) "$base.prepared.mp4" else "$base.mp4"
        return File(original.parentFile, name)
    }
}

/**
 * Files the phone has open right now: what the player plays ([playing]) and what a LAN server hands
 * a TV (`LocalFileServer.servedPaths`). A downloaded original replaced by its MP4 is deleted only
 * once neither holds it: ExoPlayer reopens the file on every seek, and a deleted one cannot be.
 */
object LocalFileUse {
    /** The local file the phone's player opened last ([LocalLibrary.fileFor]), or null. */
    @Volatile
    var playing: String? = null

    /** The episode [playing] belongs to: whether the player on screen plays a title from the phone or the network. */
    @Volatile
    var playingEpisode: String? = null

    fun inUse(path: String, served: Set<String> = runCatching { com.arkiv.player.playback.LocalFileServer.servedPaths() }.getOrDefault(emptySet())): Boolean =
        path == playing || path in served
}

/** A download's preparation as the Downloads screen shows it: [percent] while converting, else its last [state]. */
data class PrepStatus(val state: PrepState?, val percent: Int? = null)

/**
 * Turns finished downloads into faststart MP4s with up to three audio tracks (see [Mp4Repackager]) and SRT
 * sidecars (see [Mp4SubtitleSidecars]), the owner's rule for 0.9.46: whatever a download came as
 * (a progressive MPEG-TS from Xuper, a plugin's HLS joined into one `.ts`, a Matroska), it is kept
 * as MP4, which every TV and Chromecast plays without a remux and seeks in.
 *
 * Runs in [Mp4PrepWorker] (survives the app closing), one download at a time ([mutex]):
 * - right after a download finishes (`LocalDownloadWorker`);
 * - lazily for older downloads, the first time one is opened ([onOpened], from [LocalLibrary.fileFor]:
 *   playing it, casting it, "Enviar a la TV") -- playback never waits for it, the original plays
 *   (and casts through the existing remux paths) meanwhile;
 * - in a daily sweep while charging, for every download still worth a pass ([sweepIds]).
 *
 * Space first: with less free than the file again plus [FreeSpacePolicy.MARGIN_BYTES], the
 * original stays and is marked [PrepState.NO_SPACE] for a later pass. A failure keeps the original
 * too, marked [PrepState.FAILED] and reported. Never on a TV, where nothing is written for a download.
 *
 * The rows follow the file: every `downloads` row whose path was the original (an adopted twin's
 * too) points at the MP4 once it is complete, and the original is deleted then, or later if it is
 * still open ([LocalFileUse], [PrepMarker.pendingDelete]).
 */
class Mp4Prep(
    /** The completed download's file path for an episode ([LocalLibrary]'s rules), or null. */
    private val completedPath: suspend (episodeId: String) -> String?,
    /** Re-points every row on [old] to [new]. */
    private val movePath: suspend (old: String, new: String) -> Unit,
    /** Every completed download's episode id. */
    private val completedIds: suspend () -> List<String>,
    /** The online subtitles the person got for an episode (their files in the app's cache). */
    private val onlineSubtitles: (episodeId: String) -> List<SavedOnlineSubtitle>,
    /** Schedules [Mp4PrepWorker] for these episodes. */
    private val schedule: (episodeIds: List<String>) -> Unit,
    /** The person's preferred audio languages, first preference first ([Mp4AudioKeep]). */
    private val audioLanguages: () -> List<com.arkiv.player.playback.TrackLang> = { emptyList() },
    /** The menu label of the audio the person picked by hand for an episode, if any. */
    private val pickedAudio: (episodeId: String) -> String? = { null },
    private val isTelevision: () -> Boolean = { false },
    private val repackager: Mp4Repackager = Mp4Repackager(),
    private val freeSpace: (File) -> Long = { it.usableSpace },
    /** A conversion that failed (not a lack of space, not an unsupported codec): reported. */
    private val reportFailure: (episodeId: String, reason: String) -> Unit = { _, _ -> },
    /** A preparation [Mp4PrepWorker] ran ended in this state (null: nothing to prepare yet). */
    val onPrepared: (episodeId: String, state: PrepState?) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()

    private val _status = MutableStateFlow<Map<String, PrepStatus>>(emptyMap())

    /** Per episode: the last state and, while it converts, the percentage. */
    val status: StateFlow<Map<String, PrepStatus>> = _status.asStateFlow()

    /**
     * The player (or a cast) opened [episodeId]'s download at [path]: remembered as in use, and a
     * download never prepared (or worth another pass) is scheduled. Never waits for anything.
     */
    fun onOpened(episodeId: String, path: String) {
        val previous = LocalFileUse.playing
        LocalFileUse.playing = path
        LocalFileUse.playingEpisode = episodeId
        if (previous != null && previous != path) runCatching { deletePending(File(previous).parentFile ?: return@runCatching) }
        if (isTelevision()) return
        val marker = readMarker(File(path).parentFile ?: return, episodeId)
        if (Mp4PrepPolicy.wants(marker)) schedule(listOf(episodeId))
    }

    /** Asks for [episodeId]'s preparation now (a finished download, "Enviar a la TV", the last-resort offer). */
    fun request(episodeId: String) {
        if (!isTelevision()) schedule(listOf(episodeId))
    }

    /** Every completed download still worth a pass (the charging sweep). */
    suspend fun sweepIds(dir: File): List<String> =
        completedIds().filter { Mp4PrepPolicy.wants(readMarker(dir, it)) }

    /** Loads the markers of [ids] into [status] (the Downloads screen, on open). */
    fun refresh(dir: File, ids: Collection<String>) {
        val states = ids.associateWith { readMarker(dir, it)?.state }
        _status.update { current ->
            current + states.mapValues { (id, s) -> current[id]?.takeIf { it.percent != null } ?: PrepStatus(s) }
        }
    }

    fun readMarker(dir: File, episodeId: String): PrepMarker? = runCatching {
        val f = PrepMarker.fileFor(dir, episodeId)
        if (f.exists()) PrepMarker.fromJson(f.readText()) else null
    }.getOrNull()

    private fun writeMarker(dir: File, episodeId: String, marker: PrepMarker) {
        runCatching { PrepMarker.fileFor(dir, episodeId).writeText(marker.toJson()) }
        _status.update { it + (episodeId to PrepStatus(marker.state)) }
    }

    /**
     * Prepares [episodeId]'s download: subtitles to SRT sidecars, the video to a faststart MP4 when
     * it is not one. Returns the state it ends in, or null when there is nothing to prepare (no
     * completed download, a TV, the file gone). [onProgress]: 0..100 while converting.
     */
    suspend fun prepare(episodeId: String, onProgress: (Int) -> Unit = {}): PrepState? = mutex.withLock {
        if (isTelevision()) return@withLock null
        val path = completedPath(episodeId) ?: return@withLock null
        val original = File(path)
        val dir = original.parentFile ?: return@withLock null
        if (!original.exists()) return@withLock null
        val marker = readMarker(dir, episodeId)
        if (!Mp4PrepPolicy.wants(marker)) {
            deletePending(dir)
            return@withLock marker?.state
        }
        val decision = Mp4PrepPolicy.decide(containerOf(original), original.length(), freeSpace(dir))
        Log.i(TAG, "$episodeId: ${original.name} (${original.length()}B) → $decision")
        val state = when (decision) {
            Mp4PrepPolicy.Decision.NOT_NEEDED -> {
                subtitles(dir, episodeId, original)
                PrepMarker(PrepState.READY, pendingDelete = marker?.pendingDelete.orEmpty())
            }
            Mp4PrepPolicy.Decision.UNSUPPORTED -> {
                subtitles(dir, episodeId, original)
                PrepMarker(PrepState.UNSUPPORTED, "container")
            }
            Mp4PrepPolicy.Decision.NO_SPACE -> PrepMarker(PrepState.NO_SPACE, attempts = marker?.attempts ?: 0)
            Mp4PrepPolicy.Decision.CONVERT -> convert(dir, episodeId, original, marker, onProgress) ?: return@withLock null
        }
        writeMarker(dir, episodeId, state)
        deletePending(dir)
        state.state
    }

    private suspend fun convert(dir: File, episodeId: String, original: File, marker: PrepMarker?, onProgress: (Int) -> Unit): PrepMarker? {
        val output = Mp4PrepPolicy.outputFor(original)
        _status.update { it + (episodeId to PrepStatus(marker?.state, 0)) }
        val result = try {
            val preferred = audioLanguages()
            val picked = pickedAudio(episodeId)
            repackager.repack(original, output, keepAudio = { tracks -> Mp4AudioKeep.select(tracks, preferred, picked) }) { p ->
                _status.update { it + (episodeId to PrepStatus(marker?.state, p)) }
                onProgress(p)
            }
        } finally {
            _status.update { m -> m[episodeId]?.let { m + (episodeId to it.copy(percent = null)) } ?: m }
        }
        return when (result) {
            is Mp4Repackager.Result.Done -> {
                // Removed while it converted: what was written belongs to nobody.
                if (completedPath(episodeId) == null) {
                    output.delete()
                    return null
                }
                movePath(original.absolutePath, output.absolutePath)
                val pending = marker?.pendingDelete.orEmpty().toMutableList()
                if (LocalFileUse.inUse(original.absolutePath)) pending += original.absolutePath else original.delete()
                Log.i(
                    TAG,
                    "$episodeId: ${original.length().takeIf { it > 0 } ?: "-"}B → ${output.name} ${output.length()}B in ${result.elapsedMs}ms " +
                        "(${result.videoTracks} video, ${result.audioTracks} audio, faststart=${result.fastStart})",
                )
                subtitles(dir, episodeId, output)
                PrepMarker(PrepState.READY, pendingDelete = pending)
            }
            is Mp4Repackager.Result.Unsupported -> {
                Log.i(TAG, "$episodeId: kept as it is, ${result.reason}")
                subtitles(dir, episodeId, original)
                PrepMarker(PrepState.UNSUPPORTED, result.reason)
            }
            is Mp4Repackager.Result.NoSpace -> PrepMarker(PrepState.NO_SPACE, attempts = marker?.attempts ?: 0)
            is Mp4Repackager.Result.Failed -> {
                Log.w(TAG, "$episodeId: conversion failed: ${result.reason}", result.cause)
                reportFailure(episodeId, result.reason)
                PrepMarker(PrepState.FAILED, result.reason, (marker?.attempts ?: 0) + 1)
            }
        }
    }

    /** The SRT sidecars, best-effort: a subtitle problem never fails the video. */
    private fun subtitles(dir: File, episodeId: String, video: File) {
        runCatching {
            val online = onlineSubtitles(episodeId).filter { File(it.path).exists() }
            Mp4SubtitleSidecars.rewrite(dir, episodeId, video, online)
        }.onFailure { Log.w(TAG, "$episodeId: subtitles not prepared: ${it.message}") }
    }

    /** Deletes the replaced originals nothing holds any more (every marker in [dir]). */
    fun deletePending(dir: File) {
        val markers = dir.listFiles { f -> f.name.endsWith(".prep.json") }.orEmpty()
        markers.forEach { f ->
            val m = runCatching { PrepMarker.fromJson(f.readText()) }.getOrNull() ?: return@forEach
            if (m.pendingDelete.isEmpty()) return@forEach
            val left = m.pendingDelete.filter { p -> LocalFileUse.inUse(p) && File(p).exists() }
            m.pendingDelete.filter { it !in left }.forEach { runCatching { File(it).delete() } }
            if (left.size != m.pendingDelete.size) runCatching { f.writeText(m.copy(pendingDelete = left).toJson()) }
        }
    }

    private fun containerOf(file: File): Container? = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(VideoContainer.SIGNATURE_BYTES)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            VideoContainer.bySignature(buf.copyOf(read))
        }
    }.getOrNull()

    private companion object {
        const val TAG = "KinoMp4Prep"
    }
}
