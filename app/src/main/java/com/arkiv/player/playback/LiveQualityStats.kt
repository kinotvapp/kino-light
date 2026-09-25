package com.arkiv.player.playback

/**
 * What a viewer perceives as "interference" on a live channel, counted: how often and for how long the picture
 * froze waiting for data (stalls), frames dropped, audio underruns, failed loads. Pure (the clock is injected and
 * player states are plain ints) so every threshold is pinned by a test; `LiveQualityMonitor` feeds it from
 * ExoPlayer's events.
 *
 * A STALL is a return to BUFFERING after playback had already started. Buffering before the first READY is the
 * channel opening, not a stall, and is reported separately as the startup time.
 */
internal class LiveQualityStats(private val nowMs: () -> Long) {

    private val startedAt = nowMs()

    // Nullable rather than "0 = not yet": 0 is a legitimate clock reading, and a sentinel that collides with it
    // silently turns off everything derived from these.
    private var firstReadyAt: Long? = null
    private var stallStartedAt: Long? = null
    private var lastState = STATE_IDLE

    var startupMs = -1L
        private set
    var stalls = 0
        private set
    var stallMs = 0L
        private set
    var worstStallMs = 0L
        private set
    var droppedFrames = 0L
        private set
    var audioUnderruns = 0
        private set
    var loadErrors = 0
        private set

    /** Frames the decoder has put on screen, set by the monitor from the decoder counters, for the dropped share. */
    var renderedFrames = 0L

    /** Returns the length of the stall that just ended, or 0 when this state change didn't end one. */
    fun onState(state: Int): Long {
        val now = nowMs()
        var endedStall = 0L
        if (state == STATE_READY && firstReadyAt == null) {
            firstReadyAt = now
            startupMs = now - startedAt
        }
        if (state == STATE_BUFFERING && lastState == STATE_READY && firstReadyAt != null) {
            stallStartedAt = now
        }
        val stalledSince = stallStartedAt
        if (state != STATE_BUFFERING && stalledSince != null) {
            endedStall = now - stalledSince
            stalls++
            stallMs += endedStall
            worstStallMs = maxOf(worstStallMs, endedStall)
            stallStartedAt = null
        }
        lastState = state
        return endedStall
    }

    fun onDroppedFrames(count: Int) {
        droppedFrames += count.coerceAtLeast(0)
    }

    fun onAudioUnderrun() {
        audioUnderruns++
    }

    fun onLoadError() {
        loadErrors++
    }

    /** Time playing (from the first READY), which is what the shares below are measured against. */
    fun watchedMs(): Long = firstReadyAt?.let { nowMs() - it } ?: 0L

    /** True while a rebuffer that started after playback was running is still open. */
    val isStalled: Boolean get() = stallStartedAt != null

    /** Length of the stall in progress, 0 when not stalled. */
    fun currentStallMs(): Long = stallStartedAt?.let { nowMs() - it } ?: 0L

    /** Share of the watched time spent frozen waiting for data, counting a stall still in progress. */
    fun stallPercent(): Double {
        val watched = watchedMs()
        return if (watched <= 0) 0.0 else (stallMs + currentStallMs()) * 100.0 / watched
    }

    fun droppedPercent(): Double {
        val total = renderedFrames + droppedFrames
        return if (total <= 0) 0.0 else droppedFrames * 100.0 / total
    }

    /**
     * Why this session counts as degraded, or null when it doesn't (yet). Not judged before [MIN_WATCH_MS]:
     * a couple of seconds of a channel opening say nothing about it.
     */
    fun degradedReason(): String? {
        if (watchedMs() < MIN_WATCH_MS) return null
        return when {
            stallPercent() >= STALL_PERCENT -> "stalls: frozen ${"%.1f".format(stallPercent())}% of the time"
            stalls >= MAX_STALLS -> "stalls: $stalls rebuffers"
            droppedPercent() >= DROPPED_PERCENT -> "dropped frames: ${"%.1f".format(droppedPercent())}%"
            audioUnderruns >= MAX_UNDERRUNS -> "audio underruns: $audioUnderruns"
            loadErrors >= MAX_LOAD_ERRORS -> "load errors: $loadErrors"
            else -> null
        }
    }

    companion object {
        // The same values as androidx.media3.common.Player.STATE_*, kept as plain ints so this stays JVM-testable.
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4

        const val MIN_WATCH_MS = 30_000L
        const val STALL_PERCENT = 3.0
        const val MAX_STALLS = 4
        const val DROPPED_PERCENT = 5.0
        const val MAX_UNDERRUNS = 3
        const val MAX_LOAD_ERRORS = 3
    }
}
