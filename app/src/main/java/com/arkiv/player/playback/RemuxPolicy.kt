package com.arkiv.player.playback

/**
 * Decides whether a title has to be remuxed before the Cast receiver will take it, and where the
 * result lives.
 *
 * Separated from the remuxing itself so the DECISION can be pinned by tests on the JVM: the
 * remuxer needs Android (media3's Transformer), the policy does not, and the policy is where the
 * costly mistakes are -- remuxing something that did not need it wastes minutes and a gigabyte,
 * and skipping something that did sends the TV a container it refuses.
 *
 * Why remux at all: the receiver refuses a bare MPEG-TS served progressively, and when the same
 * bytes are handed to it as HLS segments it still has to derive every frame's presentation time
 * from PTS/DTS -- which on the KALLEY produced hundreds of `Failed to get frame timestamps` a
 * minute. An MP4 carries explicit per-sample timing instead. Nothing is re-encoded: Transformer
 * copies the compressed samples when the format already fits.
 */
object RemuxPolicy {

    /** Extension of the remuxed copy. Not `.mp4` by accident: it IS an mp4. */
    const val EXTENSION = "mp4"

    /** Remuxed copies live here, under the app's own cache dir. */
    const val FOLDER = "remux"

    /** Connect timeout of the remux's own reads (loopback to the proxy: a connect is instant). */
    const val INPUT_CONNECT_MS = 15_000

    /**
     * Read timeout of the remux's own reads. Longer than everything the proxy may spend opening the
     * CDN for it ([OriginPolicy.Profile.MAGIS_REMUX]: four deadlines and the waits between them),
     * so the proxy's patience is never cut short by ours. media3's default (8 s) is what turned a
     * CDN taking 4-7 s to open into a dead remux (2026-10-01).
     */
    const val INPUT_READ_MS = 120_000

    /**
     * How many times in a row the remux's input may fail before the export gives up. ExoPlayer's
     * own default (3) is meant for a viewer who can press play again; a remux that dies costs the
     * whole cast. Each retry reopens where it was.
     */
    const val INPUT_LOAD_RETRIES = 8

    /** Attempts of a whole export that fails before it has written anything worth keeping. */
    const val EXPORT_ATTEMPTS = 3

    /** Below this much written, a failed export is a failed START, and starting again is cheap. */
    const val RETRY_MAX_WRITTEN_BYTES = 4L * 1024 * 1024

    /**
     * Should an export that failed with [errorCode] (media3 `ExportException.errorCode`) after
     * writing [writtenBytes] be started again, it being attempt [attempt] (0-based)?
     *
     * Only failures of the INPUT or of time are retried: 1000 (unspecified: the asset loader's
     * source error lands here), 2xxx (I/O) and 7002 (muxing timeout). A decoder, encoder or muxer
     * refusing the stream (3xxx-6xxx, 7001) fails the same way every time. And only at the start:
     * past [RETRY_MAX_WRITTEN_BYTES] the TV may already be playing the file, which a new run would
     * pull from under it -- the input's own retries ([INPUT_LOAD_RETRIES]) cover the middle.
     */
    fun retryExport(attempt: Int, errorCode: Int, writtenBytes: Long): Boolean {
        if (attempt + 1 >= EXPORT_ATTEMPTS) return false
        if (writtenBytes > RETRY_MAX_WRITTEN_BYTES) return false
        return errorCode == 1000 || errorCode in 2000..2999 || errorCode == 7002
    }

    /** Wait before export attempt [attempt] + 1: 2 s, then 6 s. */
    fun retryDelayMs(attempt: Int): Long = if (attempt <= 0) 2_000L else 6_000L

    /**
     * Does [mime] need remuxing before this receiver will play it properly?
     *
     * Only MPEG-TS does. An mp4 or a webm is what the receiver already wants, and a remux of one
     * would burn time and disk to produce the same thing. Anything unknown is left alone as well:
     * the segmenter path still exists for it, and a remux that fails is worse than a cast that
     * works imperfectly.
     */
    fun needsRemux(mime: String?): Boolean = mime == Container.MPEGTS.mime

    /**
     * What the phone's own reading of the video's pixel aspect [par] says about the remux, for the
     * cast log. It used to print "BLOCKED by a non-square pixel" for anything but exactly 1 --
     * including a missing format (a live channel) and par=1.00125 (a 1280x534 HEVC title) -- while
     * the remux went ahead as a plain copy at ~15x real time and played fine (2026-10-01). A ratio
     * within rounding of square is square; a really non-square one MAY make Transformer re-encode,
     * which shows as a remux far slower than usual, so the log says that instead of a verdict.
     */
    fun transmuxOutlook(par: Float?): String = when {
        par == null -> "unknown, no video format available"
        kotlin.math.abs(par - 1f) < SQUARE_PIXEL_TOLERANCE -> "possible (square pixel)"
        else -> "possible, but a non-square pixel may make it re-encode (watch the remux speed)"
    }

    /** How far from 1 a pixel aspect still counts as square: SAR rounding in the SPS, not a real anamorphic frame. */
    private const val SQUARE_PIXEL_TOLERANCE = 0.01f

    /**
     * Name of the remuxed copy for [originKey].
     *
     * Keyed by the ORIGIN, not by the title: two episodes can share a title, and the same episode
     * re-resolved gets a new proxy url with new tokens but the same object in the CDN. Hashing
     * also keeps the auth blob in the query from ever reaching the filesystem.
     */
    fun fileName(originKey: String): String =
        "${originKey.hashCode().toUInt().toString(16)}.$EXTENSION"

    /**
     * Is [remuxedBytes] far enough ahead of playback at [positionMs] to start casting?
     *
     * A remux that has only just begun is not castable: the receiver would drain it in seconds and
     * stall. [MIN_START_SEC] of content, estimated from the title's own bitrate, is the
     * floor -- enough that the remux, which runs much faster than real time, stays ahead.
     */
    fun canStart(
        remuxedBytes: Long,
        totalBytes: Long,
        durationMs: Long,
        positionMs: Long = 0L,
    ): Boolean {
        if (remuxedBytes <= 0L || totalBytes <= 0L || durationMs <= 0L) return false
        val secondsReady = durationMs / 1000.0 * remuxedBytes / totalBytes
        return secondsReady >= positionMs / 1000.0 + MIN_START_SEC
    }

    /** How much finished content must exist before handing the receiver the url. */
    const val MIN_START_SEC = 30.0

    /**
     * How long each chunk runs when a title is cast as a QUEUE of finished files.
     *
     * Thirty seconds: short enough that playback starts almost at once (only the first chunk has
     * to exist), long enough that the joins between chunks stay rare. Each chunk is a complete mp4
     * with a duration of its own, which is the entire point -- one file that kept growing made the
     * receiver recompute its duration every second or two and chase an end that never stopped
     * moving.
     */
    const val CHUNK_SEC = 30L

    /** How many chunks a title of [durationMs] is cut into. */
    fun chunksFor(durationMs: Long): Int =
        if (durationMs <= 0L) 0 else Math.ceil(durationMs / 1000.0 / CHUNK_SEC).toInt()

    /**
     * Cache key for a remux that starts at [fromMs] instead of at the beginning.
     *
     * Starting somewhere other than zero is done by remuxing FROM that point, not by seeking into
     * the result. The remux is cast as a LIVE stream -- that is what stopped the receiver inventing
     * an end and stalling against it -- and a live stream has no timeline to seek along. A file
     * that begins where you left off needs none: it plays from its own zero.
     *
     * Rounded to [GRAIN_SEC] so reopening a title seconds later reuses the remux instead of paying
     * for another. The rounding goes BACKWARDS on purpose: starting a few seconds early is
     * harmless, starting late skips content.
     */
    fun keyFrom(originKey: String, fromMs: Long, audioOrdinal: Int? = null): String {
        // The audio track goes INTO the key: a remux carries exactly one audio track, so the same
        // title with another audio is another file. Placed before the `#from` part, which
        // [fromInKey] reads from the end.
        val base = if (audioOrdinal != null && audioOrdinal >= 0) "$originKey$AUDIO_MARK$audioOrdinal" else originKey
        if (fromMs <= 0L) return base
        return keyFromExact(base, fromMs)
    }

    /** Marks the audio-track part of a key (see [keyFrom]); never contains `#`. */
    private const val AUDIO_MARK = "|audio="

    private fun keyFromExact(originKey: String, fromMs: Long): String {
        // EXACT milliseconds, no rounding. Rounding here was a real bug: the keyframe is found to
        // the millisecond and then this filed it under the nearest 30 s, so the remux was clipped
        // 583 ms away from the keyframe and the tracks went back to starting at different instants
        // -- the very desync the search exists to remove. Reuse comes from rounding the REQUEST
        // before the search instead, which lands on the same keyframe and so the same key.
        return "$originKey#$fromMs"
    }

    /** Rounds a resume point down to [GRAIN_SEC], so nearby ones look for the same keyframe. */
    fun roundRequest(fromMs: Long): Long =
        if (fromMs < GRAIN_SEC * 1000L) 0L else fromMs / 1000 / GRAIN_SEC * GRAIN_SEC * 1000L

    /** How coarsely a resume point is rounded when keying a remux. */
    const val GRAIN_SEC = 30L

    /** Where a remux keyed by [keyFrom] actually begins, in ms. */
    fun fromInKey(key: String): Long =
        key.substringAfterLast('#', "").toLongOrNull() ?: 0L

    /** Cache name for chunk [index] of [originKey]. */
    fun chunkFileName(originKey: String, index: Int): String =
        "${originKey.hashCode().toUInt().toString(16)}-$index.$EXTENSION"

    /**
     * Seconds of playable content in [bytes], for a title of [totalBytes] and [durationMs].
     *
     * The head start has to be measured in TIME, not megabytes. A fixed 6 MB is twenty-seven
     * seconds of a 221 KB/s title and six seconds of a 1 MB/s one -- the same number meaning
     * "comfortable" for one title and "about to stall" for another.
     */
    fun secondsReady(bytes: Long, totalBytes: Long, durationMs: Long): Double {
        if (bytes <= 0L || totalBytes <= 0L || durationMs <= 0L) return 0.0
        return durationMs / 1000.0 * bytes / totalBytes
    }

    /**
     * Ceiling for everything remuxed, together. A two-hour title is around a gigabyte, and these
     * are derived copies of things the person can always fetch again -- filling their phone with
     * them would be a poor trade for saving a few minutes of re-muxing.
     */
    const val BYTE_CAP = 4L * 1024 * 1024 * 1024

    /**
     * Which files to drop, oldest first, so that what remains fits under [BYTE_CAP] alongside
     * [incomingBytes].
     *
     * Takes (name, size, lastModified) and returns the names to delete. Pure so the eviction order
     * can be pinned by test: getting it backwards would throw away what is being watched right now
     * and keep what nobody has opened in weeks.
     */
    fun toDelete(
        files: List<Triple<String, Long, Long>>,
        incomingBytes: Long = 0L,
    ): List<String> {
        val total = files.sumOf { it.second } + incomingBytes
        if (total <= BYTE_CAP) return emptyList()
        var over = total - BYTE_CAP
        val out = ArrayList<String>()
        files.sortedBy { it.third }.forEach { (name, bytes, _) ->
            if (over <= 0L) return out
            out.add(name)
            over -= bytes
        }
        return out
    }
}
