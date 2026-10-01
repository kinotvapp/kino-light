package com.arkiv.player.dlna

import com.arkiv.player.dlna.DlnaSeek.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaSeekTest {

    @Test
    fun `the Seek target is H MM SS with unpadded hours`() {
        assertEquals("0:00:00", DlnaSeek.relTime(0))
        assertEquals("0:00:59", DlnaSeek.relTime(59_999))
        assertEquals("0:23:05", DlnaSeek.relTime(23 * 60_000L + 5_000))
        assertEquals("1:02:03", DlnaSeek.relTime(3_723_000))
        assertEquals("10:00:00", DlnaSeek.relTime(36_000_000))
    }

    @Test
    fun `a negative position is the top`() {
        assertEquals("0:00:00", DlnaSeek.relTime(-5_000))
    }

    @Test
    fun `a cast that starts at the top never seeks`() {
        assertEquals(Step.NONE, DlnaSeek.next(0, "PLAYING", 0, 5_000, 0))
        assertEquals(Step.NONE, DlnaSeek.next(2_000, "PLAYING", 0, 5_000, 0))
    }

    @Test
    fun `it waits for the renderer to play`() {
        assertEquals(Step.WAIT, DlnaSeek.next(600_000, null, null, 2_000, 0))
        assertEquals(Step.WAIT, DlnaSeek.next(600_000, "STOPPED", null, 2_000, 0))
        assertEquals(Step.WAIT, DlnaSeek.next(600_000, "TRANSITIONING", null, 4_000, 0))
    }

    @Test
    fun `playing at the top, elsewhere wanted, it seeks`() {
        assertEquals(Step.SEEK, DlnaSeek.next(600_000, "PLAYING", 0, 4_000, 0))
        assertEquals(Step.SEEK, DlnaSeek.next(600_000, "playing", null, 4_000, 0))
        assertEquals(Step.SEEK, DlnaSeek.next(600_000, "PAUSED_PLAYBACK", 1_000, 4_000, 0))
    }

    @Test
    fun `a renderer stuck loading for a while is sought there too, LG prefix included`() {
        assertEquals(Step.SEEK, DlnaSeek.next(600_000, "TRANSITIONING", null, 10_000, 0))
        assertEquals(Step.SEEK, DlnaSeek.next(600_000, "LG_TRANSITIONING", null, 12_000, 0))
    }

    @Test
    fun `a renderer already at the start point (an HLS start it honoured) is left alone`() {
        assertEquals(Step.ALREADY_THERE, DlnaSeek.next(600_000, "PLAYING", 601_000, 4_000, 0))
        assertEquals(Step.ALREADY_THERE, DlnaSeek.next(600_000, "PLAYING", 590_000, 4_000, 0))
        assertEquals(Step.SEEK, DlnaSeek.next(600_000, "PLAYING", 500_000, 4_000, 0))
    }

    @Test
    fun `one retry after a refusal, then it keeps playing`() {
        assertEquals(Step.SEEK, DlnaSeek.next(600_000, "PLAYING", 0, 6_000, 1))
        assertEquals(Step.NONE, DlnaSeek.next(600_000, "PLAYING", 0, 8_000, 2))
    }

    @Test
    fun `a renderer that never plays is given up on`() {
        assertEquals(Step.GIVE_UP, DlnaSeek.next(600_000, "TRANSITIONING", null, 121_000, 0))
    }
}
