package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.local.EnqueueOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision for tapping a Magis result on the phone: a series opens the season dialog (as
 * today); a movie shows the watch/download dialog, with "download" offered only when a download
 * strategy exists for Magis. Kept pure and Compose-free so it is unit-testable — the dialog itself
 * is not (this repo does not unit-test Compose dialogs).
 */
class MagisTapDecisionTest {

    private fun result(programType: String) =
        GatewayResult(source = "magis", title = "X", ref = "r", extra = mapOf("program_type" to programType))

    @Test fun series_opens_the_season_dialog() {
        val r = result("series")
        assertEquals(MagisTapDecision.OpenSeasonDialog(r), decideMagisTap(r, canDownload = true))
    }

    @Test fun teleplay_also_opens_the_season_dialog() {
        val r = result("teleplay")
        assertEquals(MagisTapDecision.OpenSeasonDialog(r), decideMagisTap(r, canDownload = true))
    }

    @Test fun variety_also_opens_the_season_dialog() {
        val r = result("variety")
        assertEquals(MagisTapDecision.OpenSeasonDialog(r), decideMagisTap(r, canDownload = false))
    }

    @Test fun a_series_never_shows_the_movie_dialog_even_when_downloadable() {
        val r = result("series")
        val decision = decideMagisTap(r, canDownload = true)
        assertTrue(decision is MagisTapDecision.OpenSeasonDialog)
    }

    @Test fun a_movie_shows_the_dialog_with_download_offered() {
        val r = result("movie")
        assertEquals(
            MagisTapDecision.ShowMovieDialog(r, canDownload = true),
            decideMagisTap(r, canDownload = true),
        )
    }

    @Test fun a_movie_without_a_download_strategy_does_not_offer_it() {
        val r = result("movie")
        assertEquals(
            MagisTapDecision.ShowMovieDialog(r, canDownload = false),
            decideMagisTap(r, canDownload = false),
        )
    }

    @Test fun a_missing_program_type_is_treated_as_a_movie() {
        val r = GatewayResult(source = "magis", title = "X", ref = "r")
        assertEquals(
            MagisTapDecision.ShowMovieDialog(r, canDownload = true),
            decideMagisTap(r, canDownload = true),
        )
    }

    // queuedDownloadToastText: only a fresh EnqueueOutcome.QUEUED gets the "queued" toast.
    // ALREADY_QUEUED and ALREADY_DOWNLOADED already surface their own message through
    // rememberDuplicateDownloadNotice, so this must stay silent for them — otherwise the user would
    // see both "you already have that" and a false "queued".

    @Test fun queued_shows_the_toast_with_the_movie_title() {
        assertEquals("Descarga de \"Matrix\" en cola", queuedDownloadToastText(EnqueueOutcome.QUEUED, "Matrix"))
    }

    @Test fun already_queued_shows_nothing() {
        assertNull(queuedDownloadToastText(EnqueueOutcome.ALREADY_QUEUED, "Matrix"))
    }

    @Test fun already_downloaded_shows_nothing() {
        assertNull(queuedDownloadToastText(EnqueueOutcome.ALREADY_DOWNLOADED, "Matrix"))
    }
}
