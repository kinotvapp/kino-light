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
    fun keyFrom(originKey: String, fromMs: Long): String {
        if (fromMs <= 0L) return originKey
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
