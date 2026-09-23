package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic of the incremental jump and the state that goes with it. They used to live
 * inside `PlayerContent` —`seekBy` was a local function closed over four variables of the
 * composable—, so the rule that actually matters (accumulate on the previous target, not on the
 * player's position) couldn't be exercised from anywhere.
 */
class SeekStateTest {

    // ---- seekTarget ----

    @Test
    fun `adds the delta to the base`() {
        assertEquals(70_000L, seekTarget(base = 60_000L, deltaMs = 10_000L, durationMs = 600_000L))
    }

    @Test
    fun `goes back with a negative delta`() {
        assertEquals(50_000L, seekTarget(base = 60_000L, deltaMs = -10_000L, durationMs = 600_000L))
    }

    @Test
    fun `does not go past the end`() {
        assertEquals(600_000L, seekTarget(base = 595_000L, deltaMs = 10_000L, durationMs = 600_000L))
    }

    @Test
    fun `does not fall before the start`() {
        assertEquals(0L, seekTarget(base = 5_000L, deltaMs = -10_000L, durationMs = 600_000L))
    }

    /**
     * Duration 0 = "not known yet" (startup, or a live stream). There's no ceiling to respect
     * there, and clamping against 0 would pin any forward jump at second zero.
     */
    @Test
    fun `with no known duration it only clamps from below`() {
        assertEquals(70_000L, seekTarget(base = 60_000L, deltaMs = 10_000L, durationMs = 0L))
        assertEquals(0L, seekTarget(base = 5_000L, deltaMs = -10_000L, durationMs = 0L))
    }

    // ---- the burst ----

    /**
     * The heart of the deferred seek: twelve D-pad presses in a row have to add up to twelve
     * steps. If the count started from the player's position —which only advances when a real
     * seek lands, and on Magis a range can take up to 20 s— the burst would race against itself
     * and the result would depend on how many finished.
     */
    @Test
    fun `a burst accumulates on the previous target, not on the player`() {
        val seek = SeekState()
        val playerPositionThatDoesNotMove = 60_000L
        repeat(12) { seek.jump(10_000L, playerPositionThatDoesNotMove, 600_000L) }
        assertEquals(60_000L + 12 * 10_000L, seek.pendingMs)
    }

    @Test
    fun `the first jump does start from the player's position`() {
        val seek = SeekState()
        seek.jump(10_000L, currentPositionMs = 60_000L, durationMs = 600_000L)
        assertEquals(70_000L, seek.pendingMs)
    }

    @Test
    fun `jumping turns on the drag and paints the target`() {
        val seek = SeekState()
        assertFalse(seek.dragging)
        seek.jump(10_000L, 60_000L, 600_000L)
        assertTrue(seek.dragging)
        assertEquals(70_000L, seek.positionToShow(realPositionMs = 60_000L))
    }

    @Test
    fun `confirming clears the pending target and turns off the drag`() {
        val seek = SeekState()
        seek.jump(10_000L, 60_000L, 600_000L)
        seek.confirmed()
        assertNull(seek.pendingMs)
        assertFalse(seek.dragging)
    }

    // ---- the slider drag ----

    /**
     * Grabbing the bar has to discard the pending burst: otherwise the debounce would fire AFTER
     * releasing and send you back to the arrows' target, overriding the drag.
     */
    @Test
    fun `grabbing the bar discards the pending burst`() {
        val seek = SeekState()
        seek.jump(10_000L, 60_000L, 600_000L)
        assertEquals(70_000L, seek.pendingMs)
        seek.dragTo(300_000f)
        assertNull(seek.pendingMs)
        assertEquals(300_000L, seek.release())
    }

    @Test
    fun `releasing turns off the drag and returns the target`() {
        val seek = SeekState()
        seek.dragTo(120_000f)
        assertTrue(seek.dragging)
        assertEquals(120_000L, seek.release())
        assertFalse(seek.dragging)
    }

    @Test
    fun `with no movement the real position is shown`() {
        val seek = SeekState()
        assertEquals(42_000L, seek.positionToShow(realPositionMs = 42_000L))
        assertEquals(42_000f, seek.barValue(realPositionMs = 42_000L), 0.001f)
    }
}
