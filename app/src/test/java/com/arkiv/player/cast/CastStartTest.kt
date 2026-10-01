package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/** The resume point every protocol starts the TV at. */
class CastStartTest {

    @Test
    fun `the TV's own position wins when the title is already on it`() {
        assertEquals(1_500_000, CastStart.resumePointMs(receiverMs = 1_500_000, livePositionMs = 900_000, itemStartMs = 0))
    }

    @Test
    fun `else where the phone is playing`() {
        assertEquals(900_000, CastStart.resumePointMs(receiverMs = null, livePositionMs = 900_000, itemStartMs = 60_000))
    }

    @Test
    fun `a player that answers 0 or nothing is not a position, the item's start is`() {
        assertEquals(60_000, CastStart.resumePointMs(receiverMs = null, livePositionMs = 0, itemStartMs = 60_000))
        assertEquals(60_000, CastStart.resumePointMs(receiverMs = null, livePositionMs = null, itemStartMs = 60_000))
    }

    @Test
    fun `never negative`() {
        assertEquals(0, CastStart.resumePointMs(receiverMs = null, livePositionMs = -5, itemStartMs = -1))
    }
}
