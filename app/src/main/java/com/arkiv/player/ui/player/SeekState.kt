package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Where an incremental jump of [deltaMs] lands starting from [base], clamped to the content.
 *
 * [base] is the previous unconfirmed target, NOT the player's position: that way the count
 * doesn't depend on whether the previous jump already landed. With twelve D-pad presses in a row,
 * taking the real position each time would give a different result depending on how many seeks
 * had finished.
 *
 * A [durationMs] of 0 or less means "not known yet" (startup, or a live stream): there it only
 * clamps from below, since there's no ceiling to respect.
 */
internal fun seekTarget(base: Long, deltaMs: Long, durationMs: Long): Long =
    (base + deltaMs).coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)

/**
 * The state of "moving through the bar": the slider drag and the D-pad's incremental jumps, the
 * ±10 s buttons, and the double-tap.
 *
 * Both paths share [dragging] and [dragPosition] on purpose: while either one is in progress, the
 * bar and the clock are painted with the TARGET and not the real position, so you can see where
 * you're going even while the video is still on the old frame. Same behavior as Netflix or Prime
 * on TV.
 *
 * Who actually fires the seek stays with the screen: it depends on the active player (local or
 * Chromecast) and on whether the cast is transcoding, which isn't this state's business.
 */
@Stable
internal class SeekState {
    /** A movement is in progress: slider drag or a burst of unconfirmed jumps. */
    var dragging by mutableStateOf(false)
        private set

    /** Position painted while [dragging], in ms as a float (what the Slider wants). */
    var dragPosition by mutableFloatStateOf(0f)
        private set

    /**
     * Accumulated target of the incremental jumps not yet confirmed, or null if none is in
     * progress. Every new press overwrites it and resets the debounce, so only one real seek
     * comes out once you stop moving.
     *
     * Every press used to be a real `seekTo`, i.e. a Range request and its rebuffer: moving two
     * minutes with the TV's D-pad is twelve of those. On Magis each range can take 0.2 to 20 s, so
     * the burst was racing against itself.
     */
    var pendingMs by mutableStateOf<Long?>(null)
        private set

    /**
     * Focus on the progress bar (TV): thickens the track so it's noticeable that it's selected.
     * Without a visual signal it couldn't be told apart from being on the buttons, and since here
     * left/right seek instead of changing button, navigation looked erratic.
     */
    var barFocused by mutableStateOf(false)
        private set

    /** Which position to show: the target while there's movement, the real one otherwise. */
    fun positionToShow(realPositionMs: Long): Long =
        if (dragging) dragPosition.toLong() else realPositionMs

    /** Same, for the Slider, which works in float. */
    fun barValue(realPositionMs: Long): Float =
        if (dragging) dragPosition else realPositionMs.toFloat()

    /**
     * An incremental jump. Returns the accumulated target; does NOT touch the player -- the
     * screen's debounce is in charge of confirming it.
     */
    fun jump(deltaMs: Long, currentPositionMs: Long, durationMs: Long): Long {
        val target = seekTarget(pendingMs ?: currentPositionMs, deltaMs, durationMs)
        pendingMs = target
        dragPosition = target.toFloat()
        dragging = true
        return target
    }

    /**
     * Grabbing the bar discards any pending incremental jump: otherwise the debounce would fire
     * AFTER releasing and send you back to the arrows' target, overriding the drag.
     */
    fun dragTo(position: Float) {
        pendingMs = null
        dragging = true
        dragPosition = position
    }

    /** On releasing the bar. Returns where the player needs to be sent. */
    fun release(): Long {
        dragging = false
        return dragPosition.toLong()
    }

    /**
     * After confirming the burst. Order matters: the caller has already put the real position at
     * the target, so turning off [dragging] only here keeps the bar from flashing back to the old
     * position for a frame.
     */
    fun confirmed() {
        pendingMs = null
        dragging = false
    }

    fun focusChanged(focused: Boolean) {
        barFocused = focused
    }
}

@Composable
internal fun rememberSeekState(): SeekState = remember { SeekState() }
