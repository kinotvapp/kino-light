package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodSyncStatsTest {
    private var now = 0L
    private val stats = VodSyncStats { now }

    /** Playback started and ran for [ms]: what every reason below needs before it can be judged. */
    private fun watch(ms: Long) {
        stats.quality.onState(LiveQualityStats.STATE_READY)
        now += ms
    }

    @Test
    fun `nothing is suspect before a minute of playback`() {
        stats.onAudioSinkError()
        watch(VodSyncStats.MIN_WATCH_MS - 1)
        assertNull(stats.suspectReason())
    }

    @Test
    fun `a clean session is not suspect`() {
        watch(10 * 60_000)
        assertNull(stats.suspectReason())
    }

    @Test
    fun `an audio sink error is suspect`() {
        stats.onAudioSinkError()
        watch(VodSyncStats.MIN_WATCH_MS)
        assertEquals("audio sink errors: 1", stats.suspectReason())
    }

    @Test
    fun `three audio underruns are suspect, two are not`() {
        repeat(2) { stats.quality.onAudioUnderrun() }
        watch(VodSyncStats.MIN_WATCH_MS)
        assertNull(stats.suspectReason())
        stats.quality.onAudioUnderrun()
        assertEquals("audio underruns: 3", stats.suspectReason())
    }

    @Test
    fun `the first audio selection is not a switch, a change of it is`() {
        stats.onAudioSelected("a|es|ac3")
        stats.onAudioSelected("a|es|ac3")
        assertEquals(0, stats.audioSwitches)
        stats.onAudioSelected("b|en|aac")
        assertEquals(1, stats.audioSwitches)
    }

    @Test
    fun `one audio switch is suspect only when external audio tracks are merged in`() {
        stats.onAudioSelected("a")
        stats.onAudioSelected("b")
        watch(VodSyncStats.MIN_WATCH_MS)
        assertNull(stats.suspectReason())
        stats.externalAudioTracks = 2
        assertEquals("audio track switched with external tracks: 1", stats.suspectReason())
    }

    @Test
    fun `three audio switches are suspect even with no external tracks`() {
        listOf("a", "b", "a", "b").forEach { stats.onAudioSelected(it) }
        watch(VodSyncStats.MIN_WATCH_MS)
        assertEquals("audio track switched 3 times", stats.suspectReason())
    }

    @Test
    fun `unexpected position jumps are suspect from the third`() {
        repeat(2) { stats.onUnexpectedDiscontinuity() }
        watch(VodSyncStats.MIN_WATCH_MS)
        assertNull(stats.suspectReason())
        stats.onUnexpectedDiscontinuity()
        assertEquals("unexpected position jumps: 3", stats.suspectReason())
    }

    @Test
    fun `a tenth of the frames dropped is suspect`() {
        stats.quality.renderedFrames = 900
        stats.quality.onDroppedFrames(100)
        watch(VodSyncStats.MIN_WATCH_MS)
        assertEquals("dropped frames: 10.0%", stats.suspectReason())
    }

    @Test
    fun `the average video frame offset is in milliseconds, null with no frames`() {
        assertNull(stats.avgFrameOffsetMs())
        stats.onFrameProcessingOffset(totalUs = 600_000, frames = 20)
        stats.onFrameProcessingOffset(totalUs = -200_000, frames = 20)
        assertEquals(10.0, stats.avgFrameOffsetMs()!!, 0.001)
    }

    // --- the budget: at most one report per device per day

    @Test
    fun `a device that never reported may report`() {
        assertTrue(VodSyncStats.mayReport(nowMs = 1_000, lastReportMs = 0))
    }

    @Test
    fun `a second report inside a day is refused, after a day it is allowed`() {
        val last = 5_000_000L
        assertFalse(VodSyncStats.mayReport(last + VodSyncStats.MIN_REPORT_GAP_MS - 1, last))
        assertTrue(VodSyncStats.mayReport(last + VodSyncStats.MIN_REPORT_GAP_MS, last))
    }

    @Test
    fun `a clock set back never blocks reporting for good`() {
        assertTrue(VodSyncStats.mayReport(nowMs = 1_000, lastReportMs = 9_000_000))
    }
}
