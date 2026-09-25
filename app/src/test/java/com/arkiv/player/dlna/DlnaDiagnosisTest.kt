package com.arkiv.player.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DlnaDiagnosisTest {

    private fun snap(
        sincePlayMs: Long = 10_000,
        state: String? = "PLAYING",
        status: String? = "OK",
        lanHits: Int = 3,
        userPaused: Boolean = false,
        stalledMs: Long = 0,
    ) = DlnaDiagnosis.Snapshot(sincePlayMs, state, status, lanHits, userPaused, stalledMs)

    @Test
    fun `a healthy cast is not a failure`() {
        assertNull(DlnaDiagnosis.failure(snap()))
    }

    @Test
    fun `the renderer reporting an error is a failure whatever else it says`() {
        assertEquals(DlnaDiagnosis.TRANSPORT_ERROR, DlnaDiagnosis.failure(snap(status = "ERROR_OCCURRED")))
        assertEquals(DlnaDiagnosis.TRANSPORT_ERROR, DlnaDiagnosis.failure(snap(state = "PLAYING", status = "error_occurred")))
    }

    // --- the split that matters: did the TV ever come for the media? ---------

    @Test
    fun `stopped shortly after Play with no request from the TV means it never fetched`() {
        assertEquals(DlnaDiagnosis.NEVER_FETCHED, DlnaDiagnosis.failure(snap(sincePlayMs = 5_000, state = "STOPPED", lanHits = 0)))
        assertEquals(DlnaDiagnosis.NEVER_FETCHED, DlnaDiagnosis.failure(snap(sincePlayMs = 5_000, state = "NO_MEDIA_PRESENT", lanHits = 0)))
    }

    @Test
    fun `stopped shortly after Play after the TV DID request the media means it rejected what it got`() {
        assertEquals(DlnaDiagnosis.STOPPED_EARLY, DlnaDiagnosis.failure(snap(sincePlayMs = 6_000, state = "STOPPED", lanHits = 2)))
    }

    @Test
    fun `a stop long after Play is the end of the video, not a rejection`() {
        assertNull(DlnaDiagnosis.failure(snap(sincePlayMs = 120_000, state = "STOPPED", lanHits = 40)))
    }

    @Test
    fun `right after Play the renderer may still report its old state, so nothing is judged yet`() {
        assertNull(DlnaDiagnosis.failure(snap(sincePlayMs = 1_000, state = "STOPPED", lanHits = 0)))
    }

    @Test
    fun `never asked for the media and not playing after 15 s is a failure`() {
        assertEquals(DlnaDiagnosis.NEVER_FETCHED, DlnaDiagnosis.failure(snap(sincePlayMs = 16_000, state = "TRANSITIONING", lanHits = 0)))
        assertNull(DlnaDiagnosis.failure(snap(sincePlayMs = 10_000, state = "TRANSITIONING", lanHits = 0)))
    }

    @Test
    fun `playing with no counted request is not flagged, the counters may just not cover that server`() {
        assertNull(DlnaDiagnosis.failure(snap(sincePlayMs = 60_000, state = "PLAYING", lanHits = 0)))
    }

    // --- loading forever, and freezing --------------------------------------

    @Test
    fun `still loading long after it requested the media is stuck`() {
        assertEquals(DlnaDiagnosis.STUCK_LOADING, DlnaDiagnosis.failure(snap(sincePlayMs = 30_000, state = "TRANSITIONING", lanHits = 4)))
        assertNull(DlnaDiagnosis.failure(snap(sincePlayMs = 10_000, state = "TRANSITIONING", lanHits = 4)))
    }

    @Test
    fun `a position that stops advancing while playing is a stall`() {
        assertEquals(DlnaDiagnosis.POSITION_STALLED, DlnaDiagnosis.failure(snap(state = "PLAYING", stalledMs = 9_000)))
        assertNull(DlnaDiagnosis.failure(snap(state = "PLAYING", stalledMs = 3_000)))
    }

    @Test
    fun `a stall we caused by pausing is not a failure`() {
        assertNull(DlnaDiagnosis.failure(snap(state = "PLAYING", userPaused = true, stalledMs = 30_000)))
    }

    @Test
    fun `a failed poll gives no verdict`() {
        assertNull(DlnaDiagnosis.failure(snap(state = null, status = null, lanHits = 0, sincePlayMs = 60_000)))
    }

    @Test
    fun `each stage has a message for the person`() {
        listOf(
            DlnaDiagnosis.TRANSPORT_ERROR, DlnaDiagnosis.NEVER_FETCHED, DlnaDiagnosis.STOPPED_EARLY,
            DlnaDiagnosis.STUCK_LOADING, DlnaDiagnosis.POSITION_STALLED, "something_else",
        ).forEach { assertEquals(true, DlnaDiagnosis.userMessage(it).isNotBlank()) }
    }
}
