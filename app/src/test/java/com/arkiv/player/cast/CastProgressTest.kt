package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What progress should be saved while casting, and what to show on the bar.
 *
 * Without a transcoder the receiver counts the position and duration of the same file as the
 * phone; the only thing still needed is clamping `C.TIME_UNSET` (a large negative, what a live
 * stream that doesn't know its duration sends) to "unknown" (0).
 */
class CastProgressTest {

    @Test
    fun `a direct cast saves what the receiver reports`() {
        val p = CastProgress.toSave(reportedPosMs = 120_000, reportedDurMs = 300_000)!!
        assertEquals(120_000, p.positionMs)
        assertEquals(300_000, p.durationMs)
    }

    @Test
    fun `with no duration nothing is saved`() {
        // A live stream: the receiver doesn't know the duration.
        assertNull(CastProgress.toSave(reportedPosMs = 1_000, reportedDurMs = 0))
    }

    @Test
    fun `a position past the end is not saved`() {
        // On finishing, the receiver can report a bit more than the actual length; saving it
        // would leave the episode marked "unwatched".
        assertNull(CastProgress.toSave(reportedPosMs = 400_000, reportedDurMs = 300_000))
    }

    @Test
    fun `a negative position is not saved`() {
        assertNull(CastProgress.toSave(reportedPosMs = -1, reportedDurMs = 300_000))
    }

    @Test
    fun `the position that gets SHOWN is the receiver's`() {
        // For the progress bar, not for saving: here there's no "null", something always has to
        // be shown.
        assertEquals(120_000, CastProgress.contentPosition(receiverPosMs = 120_000))
    }

    @Test
    fun `an invalid position from the receiver is not shown negative`() {
        assertEquals(0, CastProgress.contentPosition(receiverPosMs = Long.MIN_VALUE + 1))
    }

    @Test
    fun `the duration that gets SHOWN is the receiver's`() {
        assertEquals(300_000, CastProgress.contentDuration(receiverDurMs = 300_000))
    }

    @Test
    fun `a remux started mid-title saves on the title's clock, position and duration both`() {
        // The remux begins at 35:00 of a 2 h title; the receiver is 1:46 into it and reports the
        // remux's own length, or nothing (the load's length is the fallback).
        val offset = 35 * 60_000L
        val media = 120 * 60_000L - offset
        assertEquals(SavedProgress(offset + 106_000L, 120 * 60_000L), CastProgress.toSaveInTitle(106_000L, media, 0L, offset))
        assertEquals(SavedProgress(offset + 106_000L, 120 * 60_000L), CastProgress.toSaveInTitle(106_000L, -1L, media, offset))
        // From the top it is plain toSave; nothing known about the length, nothing saved.
        assertEquals(CastProgress.toSave(5_000L, 60_000L), CastProgress.toSaveInTitle(5_000L, 60_000L, 0L, 0L))
        assertEquals(null, CastProgress.toSaveInTitle(106_000L, 0L, 0L, offset))
    }

    @Test
    fun `an invalid duration from the receiver is not shown`() {
        // TIME_UNSET (a live stream with no known duration): 0 means "unknown", and the bar
        // already knows how to handle it.
        assertEquals(0, CastProgress.contentDuration(receiverDurMs = Long.MIN_VALUE + 1))
    }
}
