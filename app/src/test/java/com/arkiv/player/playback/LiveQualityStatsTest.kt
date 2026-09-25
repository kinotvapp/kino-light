package com.arkiv.player.playback

import com.arkiv.player.playback.LiveQualityStats.Companion.STATE_BUFFERING
import com.arkiv.player.playback.LiveQualityStats.Companion.STATE_IDLE
import com.arkiv.player.playback.LiveQualityStats.Companion.STATE_READY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveQualityStatsTest {

    private var now = 0L
    private fun stats() = LiveQualityStats { now }

    @Test
    fun `buffering while the channel opens is the startup, not a stall`() {
        val s = stats()
        now = 0; s.onState(STATE_BUFFERING)
        now = 2_000; s.onState(STATE_READY)
        assertEquals(0, s.stalls)
        assertEquals(2_000L, s.startupMs)
    }

    @Test
    fun `going back to buffering after playing is a stall and its length is measured`() {
        val s = stats()
        now = 0; s.onState(STATE_BUFFERING)
        now = 1_000; s.onState(STATE_READY)
        now = 10_000; s.onState(STATE_BUFFERING)
        now = 11_000
        assertEquals("a stall in progress is visible before it ends", 1_000L, s.currentStallMs())
        now = 12_500
        val ended = s.onState(STATE_READY)
        assertEquals(2_500L, ended)
        assertEquals(1, s.stalls)
        assertEquals(2_500L, s.stallMs)
        assertEquals(2_500L, s.worstStallMs)
        assertEquals(0L, s.currentStallMs())
    }

    @Test
    fun `several stalls add up and the worst is remembered`() {
        val s = stats()
        s.onState(STATE_READY)
        now = 5_000; s.onState(STATE_BUFFERING)
        now = 6_000; s.onState(STATE_READY)
        now = 20_000; s.onState(STATE_BUFFERING)
        now = 23_000; s.onState(STATE_READY)
        assertEquals(2, s.stalls)
        assertEquals(4_000L, s.stallMs)
        assertEquals(3_000L, s.worstStallMs)
    }

    @Test
    fun `a session too short is never judged`() {
        val s = stats()
        s.onState(STATE_READY)
        repeat(10) { s.onAudioUnderrun() }
        now = 10_000
        assertNull("10 s of a channel opening says nothing", s.degradedReason())
    }

    @Test
    fun `a healthy minute is not degraded`() {
        val s = stats()
        s.onState(STATE_READY)
        s.renderedFrames = 1_800
        now = 60_000
        assertNull(s.degradedReason())
    }

    @Test
    fun `frozen a few percent of the time is degraded`() {
        val s = stats()
        s.onState(STATE_READY)
        now = 10_000; s.onState(STATE_BUFFERING)
        now = 13_000; s.onState(STATE_READY) // 3 s frozen
        now = 60_000
        val reason = s.degradedReason()
        assertNotNull(reason)
        assertTrue(reason, reason!!.startsWith("stalls"))
    }

    @Test
    fun `many short freezes are degraded even if the total time is small`() {
        val s = stats()
        s.onState(STATE_READY)
        repeat(4) {
            now += 20_000; s.onState(STATE_BUFFERING)
            now += 200; s.onState(STATE_READY)
        }
        now += 30_000
        assertTrue(s.degradedReason()!!.contains("4 rebuffers"))
    }

    @Test
    fun `dropped frames are judged against the frames shown`() {
        val s = stats()
        s.onState(STATE_READY)
        s.onDroppedFrames(100)
        s.renderedFrames = 1_000
        now = 40_000
        assertEquals(9.09, s.droppedPercent(), 0.01)
        assertTrue(s.degradedReason()!!.startsWith("dropped frames"))
    }

    @Test
    fun `audio underruns and load errors each degrade a session`() {
        val a = stats()
        a.onState(STATE_READY)
        repeat(3) { a.onAudioUnderrun() }
        now = 40_000
        assertTrue(a.degradedReason()!!.startsWith("audio underruns"))

        now = 0
        val b = stats()
        b.onState(STATE_READY)
        repeat(3) { b.onLoadError() }
        now = 40_000
        assertTrue(b.degradedReason()!!.startsWith("load errors"))
    }

    @Test
    fun `a negative frame count from the player cannot make the totals go backwards`() {
        val s = stats()
        s.onDroppedFrames(-5)
        assertEquals(0L, s.droppedFrames)
    }

    @Test
    fun `only a rebuffer straight after playing is a stall, not the resume after an idle`() {
        val s = stats()
        assertFalse(s.isStalled)
        s.onState(STATE_BUFFERING)
        assertFalse("opening the channel", s.isStalled)
        now = 1_000; s.onState(STATE_READY)
        now = 5_000; s.onState(STATE_BUFFERING)
        assertTrue("the buffer ran dry", s.isStalled)
        now = 6_000; s.onState(STATE_READY)
        assertFalse(s.isStalled)
        now = 9_000; s.onState(STATE_IDLE) // the app left
        now = 90_000; s.onState(STATE_BUFFERING) // and came back
        assertFalse("a resume is not a stall", s.isStalled)
        now = 91_000; s.onState(STATE_READY)
        assertEquals(1, s.stalls)
    }

    @Test
    fun `leaving the READY state to idle does not open a stall`() {
        val s = stats()
        s.onState(STATE_READY)
        now = 5_000; s.onState(STATE_IDLE)
        assertEquals(0, s.stalls)
    }
}
