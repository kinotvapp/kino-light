package com.arkiv.player.playback

/**
 * What in a film's playback can explain "the audio is out of sync", counted. A viewer who hears audio ahead of or
 * behind the picture cannot be measured by any player, so this counts the things that come with it: an audio
 * output that failed or starved, a person who kept switching audio tracks looking for one that fits (above all when
 * external tracks are merged in, which carry their own timestamps), the position jumping by itself, and frames
 * dropped so the picture lags. Pure (the clock is injected) so each threshold is pinned by a test;
 * [VodSyncMonitor] feeds it from ExoPlayer's events.
 *
 * The stalls, dropped frames and underruns are [LiveQualityStats]' own counters; only what a sync report adds is here.
 */
internal class VodSyncStats(nowMs: () -> Long) {

    val quality = LiveQualityStats(nowMs)

    var audioSinkErrors = 0
        private set
    var audioSwitches = 0
        private set
    var unexpectedDiscontinuities = 0
        private set

    /** How many audio tracks were merged into the film from outside its own container, set by the monitor. */
    var externalAudioTracks = 0

    private var lastAudioSelection: String? = null
    private var frameOffsetUsSum = 0L
    private var frameOffsetCount = 0L

    fun onAudioSinkError() {
        audioSinkErrors++
    }

    /** [id] names the audio that is playing now; the first one is the player's own choice, not a switch. */
    fun onAudioSelected(id: String) {
        val last = lastAudioSelection
        if (last != null && last != id) audioSwitches++
        lastAudioSelection = id
    }

    /** The position moved on its own (not a seek, not the next item): audio and video re-aligning. */
    fun onUnexpectedDiscontinuity() {
        unexpectedDiscontinuities++
    }

    fun onFrameProcessingOffset(totalUs: Long, frames: Int) {
        frameOffsetUsSum += totalUs
        frameOffsetCount += frames.coerceAtLeast(0)
    }

    /** Average of how far video frames were from their release time, in ms (ExoPlayer's own measure), null with no frames. */
    fun avgFrameOffsetMs(): Double? =
        if (frameOffsetCount <= 0) null else frameOffsetUsSum.toDouble() / frameOffsetCount / 1000.0

    /** Why this film's playback is worth a look for sync, or null. Not judged before [MIN_WATCH_MS]. */
    fun suspectReason(): String? {
        if (quality.watchedMs() < MIN_WATCH_MS) return null
        return when {
            audioSinkErrors >= 1 -> "audio sink errors: $audioSinkErrors"
            quality.audioUnderruns >= MAX_UNDERRUNS -> "audio underruns: ${quality.audioUnderruns}"
            audioSwitches >= 1 && externalAudioTracks > 0 -> "audio track switched with external tracks: $audioSwitches"
            audioSwitches >= MAX_SWITCHES -> "audio track switched $audioSwitches times"
            unexpectedDiscontinuities >= MAX_DISCONTINUITIES -> "unexpected position jumps: $unexpectedDiscontinuities"
            quality.droppedPercent() >= DROPPED_PERCENT -> "dropped frames: ${String.format(java.util.Locale.ROOT, "%.1f", quality.droppedPercent())}%"
            else -> null
        }
    }

    companion object {
        const val MIN_WATCH_MS = 60_000L
        const val MAX_UNDERRUNS = 3
        const val MAX_SWITCHES = 3
        const val MAX_DISCONTINUITIES = 3
        const val DROPPED_PERCENT = 10.0

        /** At most one report per device per day: enough to see that it happens and on what, never a flood. */
        const val MIN_REPORT_GAP_MS = 24L * 60 * 60 * 1000

        /** Whether a device that last reported at [lastReportMs] (0 = never) may report at [nowMs]. A clock set back never blocks it. */
        fun mayReport(nowMs: Long, lastReportMs: Long): Boolean =
            lastReportMs <= 0L || nowMs < lastReportMs || nowMs - lastReportMs >= MIN_REPORT_GAP_MS
    }
}
