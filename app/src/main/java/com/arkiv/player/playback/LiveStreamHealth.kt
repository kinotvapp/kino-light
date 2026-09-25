package com.arkiv.player.playback

import kotlin.math.abs

/** One segment announced by a live playlist; [durationSec] comes from its `#EXTINF` (null when the playlist omits it). */
internal data class LiveSegment(val name: String, val durationSec: Double?)

internal data class LivePlaylist(
    val mediaSequence: Long?,
    val targetDurationSec: Double?,
    val segments: List<LiveSegment>,
    val discontinuities: Int,
    val endList: Boolean,
)

/** Reads the parts of an HLS media playlist that say whether a live stream is healthy. Tolerant: never throws. */
internal object LivePlaylistParser {

    /** Whether [text] is an HLS playlist at all: a CDN's error page, or an empty body, behind a 200 is not. */
    fun looksLikePlaylist(text: String): Boolean = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")

    fun parse(raw: String): LivePlaylist {
        var sequence: Long? = null
        var target: Double? = null
        var discontinuities = 0
        var endList = false
        var pendingDuration: Double? = null
        val segments = mutableListOf<LiveSegment>()
        for (line in raw.lineSequence().map { it.trim() }) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull()
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toDoubleOrNull()
                line.startsWith("#EXTINF:") -> pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> Unit
                line.startsWith("#EXT-X-DISCONTINUITY") -> discontinuities++
                line == "#EXT-X-ENDLIST" -> endList = true
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments += LiveSegment(line.substringAfterLast('/').substringBefore('?'), pendingDuration)
                    pendingDuration = null
                }
            }
        }
        return LivePlaylist(sequence, target, segments, discontinuities, endList)
    }
}

/**
 * Looks at what the live proxy sees on its way through and says what's wrong with it, in words.
 *
 * The proxy already reports the HARD failures (a CDN that rejects, a segment cut off). What it can't see
 * is what makes a live channel look "interfered with" while nothing errors out, and that is what this is for:
 *  - the live edge skipping ahead (segments announced and gone before anyone could fetch them) = a visible jump;
 *  - a playlist that stops advancing, or arrives later than the player needs it = the buffer drains;
 *  - a segment that downloads complete but far smaller than the others = a truncated piece, seen as a glitch;
 *  - a segment that takes longer to download than it lasts = the buffer can only shrink.
 *
 * Pure (time comes in as an argument) so each of those is pinned by a test. One instance per channel session.
 */
internal class LiveStreamHealth {

    enum class Level { INFO, WARN }

    data class Note(val level: Level, val text: String)

    private var previousSequence: Long? = null
    private var previousCount = 0

    // Nullable rather than "0 = none yet": 0 is a valid clock reading and would hide the first interval.
    private var previousAt: Long? = null
    private var lastAdvanceAt = 0L
    private var lastDiscontinuities = 0
    private val durations = LinkedHashMap<String, Double>()
    private val recentKbps = ArrayDeque<Double>()

    // For the periodic summary.
    private var playlists = 0
    private var stale = 0
    private var gaps = 0
    private var lateRefreshes = 0
    private var backwards = 0
    private var segments = 0
    private var slow = 0
    private var short = 0
    private var cutOff = 0
    private var abandoned = 0
    private var resumes = 0
    private var bytes = 0L
    private var totalMsSum = 0L
    private var maxMs = 0L

    fun onPlaylist(p: LivePlaylist, nowMs: Long): List<Note> {
        val notes = mutableListOf<Note>()
        playlists++
        p.segments.forEach { s -> s.durationSec?.let { durations[s.name] = it } }
        while (durations.size > MAX_REMEMBERED) durations.remove(durations.keys.first())

        val sequence = p.mediaSequence
        val target = p.targetDurationSec
        val interval = previousAt?.let { nowMs - it } ?: -1L
        val previous = previousSequence
        // Nobody asked for a playlist for a long while: the player was stopped (the app left and came back), so the
        // window moved on by itself. That is a resume, not the live edge skipping ahead.
        val resumed = interval > RESUME_AFTER_MS
        if (resumed) {
            resumes++
            notes += info("playlist asked again after ${interval / 1000}s (the player was stopped): the window moved on, that is a resume")
        }

        if (sequence != null && previous != null) {
            val delta = sequence - previous
            when {
                delta < 0 -> {
                    backwards++
                    notes += warn("media sequence went BACKWARDS ($previous -> $sequence): the source restarted its window")
                }
                delta == 0L -> {
                    val stuckMs = nowMs - lastAdvanceAt
                    if (stuckMs > (target ?: DEFAULT_TARGET_SEC) * 1500) {
                        stale++
                        notes += warn("playlist STALE: seq $sequence unchanged for ${stuckMs / 1000}s (the live edge isn't advancing)")
                    }
                }
                else -> {
                    lastAdvanceAt = nowMs
                    // The previous window ended at previous + count. A new window starting beyond that means
                    // segments came and went without ever being announced to the player: a jump in the picture.
                    val skipped = sequence - (previous + previousCount)
                    if (skipped > 0 && !resumed) {
                        gaps++
                        notes += warn("GAP: $skipped segment(s) skipped between refreshes (seq $previous+$previousCount -> $sequence): a visible jump")
                    }
                }
            }
        } else {
            lastAdvanceAt = nowMs
        }

        if (!resumed && interval > 0 && target != null && interval > target * 1500) {
            lateRefreshes++
            notes += warn("playlist refresh LATE: ${interval}ms since the last one (target ${target}s): the player may run dry")
        }
        if (p.segments.size <= LOW_CUSHION_SEGMENTS) {
            notes += warn("cushion LOW: only ${p.segments.size} segment(s) announced, any hiccup will cut it")
        }
        if (p.discontinuities != lastDiscontinuities) {
            notes += warn("EXT-X-DISCONTINUITY count $lastDiscontinuities -> ${p.discontinuities}: a timestamp/codec break, usually a glitch")
            lastDiscontinuities = p.discontinuities
        }
        if (p.endList) notes += warn("ENDLIST present: the source says the stream has ended")
        if (target != null) {
            val irregular = p.segments.mapNotNull { it.durationSec }.filter { abs(it - target) > target * 0.5 }
            if (irregular.isNotEmpty()) notes += info("irregular segment durations $irregular vs target ${target}s")
        }

        previousSequence = sequence ?: previousSequence
        previousCount = p.segments.size
        previousAt = nowMs
        return notes
    }

    /** [copyMs] is the time spent copying the body; [ttfbMs] the wait before the first byte. */
    fun onSegment(name: String, sizeBytes: Long, ttfbMs: Long, copyMs: Long, wasCutOff: Boolean, playerGone: Boolean = false): List<Note> {
        val notes = mutableListOf<Note>()
        segments++
        bytes += maxOf(sizeBytes, 0L)
        val totalMs = ttfbMs + copyMs
        totalMsSum += totalMs
        maxMs = maxOf(maxMs, totalMs)
        val duration = durations[name]

        if (playerGone) {
            // The player closed the connection itself (it stopped, or zapped): not the CDN's fault and not a glitch.
            abandoned++
            notes += info("segment abandoned by the player after ${sizeBytes.coerceAtLeast(0) / 1024}KB (it stopped or changed channel) $name")
            return notes
        }
        if (wasCutOff) {
            cutOff++
            notes += warn("segment CUT OFF after ${sizeBytes.coerceAtLeast(0) / 1024}KB (first byte ${ttfbMs}ms, copy ${copyMs}ms) $name")
            return notes
        }

        val throughput = if (copyMs > 0) "%.1f".format(sizeBytes / 1024.0 / 1024.0 / (copyMs / 1000.0)) + "MB/s" else "-"
        notes += info(
            "segment ok ${sizeBytes / 1024}KB ttfb=${ttfbMs}ms copy=${copyMs}ms ($throughput)" +
                (duration?.let { " dur=${it}s" } ?: "") + " $name",
        )

        val kbps = if (duration != null && duration > 0) sizeBytes * 8 / duration / 1000 else null
        if (kbps != null) {
            if (recentKbps.size >= MIN_BASELINE) {
                val typical = median(recentKbps)
                if (kbps < typical * SHORT_FRACTION) {
                    short++
                    notes += warn(
                        "segment SHORT: ${sizeBytes / 1024}KB for ${duration}s = ${kbps.toInt()} kbps vs typical ${typical.toInt()} kbps: " +
                            "likely truncated, seen as a glitch $name",
                    )
                }
            }
            recentKbps.addLast(kbps)
            while (recentKbps.size > BASELINE_WINDOW) recentKbps.removeFirst()
        }

        if (duration != null && totalMs > duration * 1000 * SLOW_FRACTION) {
            slow++
            notes += warn("segment SLOW: ${totalMs}ms for ${duration}s of video (first byte ${ttfbMs}ms, copy ${copyMs}ms): the buffer can only shrink $name")
        } else if (ttfbMs > SLOW_FIRST_BYTE_MS) {
            slow++
            notes += warn("slow first byte: ${ttfbMs}ms $name")
        }
        return notes
    }

    /** One line for the periodic health report. */
    fun summary(): String {
        val avg = if (segments > 0) totalMsSum / segments else 0L
        return "proxy health: playlists=$playlists (stale=$stale gaps=$gaps late=$lateRefreshes backwards=$backwards) " +
            "segments=$segments (slow=$slow short=$short cutOff=$cutOff abandoned=$abandoned) resumes=$resumes avg=${avg}ms max=${maxMs}ms served=${bytes / (1024 * 1024)}MB"
    }

    private fun median(values: Collection<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
    }

    private fun warn(text: String) = Note(Level.WARN, text)
    private fun info(text: String) = Note(Level.INFO, text)

    private companion object {
        const val DEFAULT_TARGET_SEC = 5.0
        const val MAX_REMEMBERED = 80
        const val LOW_CUSHION_SEGMENTS = 2
        const val MIN_BASELINE = 3
        const val BASELINE_WINDOW = 12
        /** A segment under this share of the typical bitrate is probably truncated. */
        const val SHORT_FRACTION = 0.4
        /** Downloading takes this share of the segment's own duration: past it the buffer can only shrink. */
        const val SLOW_FRACTION = 0.8
        const val SLOW_FIRST_BYTE_MS = 2_000L
        /** A playlist asked for again after this long means the player had been stopped, not that the stream skipped. */
        const val RESUME_AFTER_MS = 30_000L
    }
}
