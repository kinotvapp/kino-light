package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.MAGIS_SERIES
import com.arkiv.player.data.local.EnqueueOutcome

/**
 * What tapping a Magis result on the phone should do. Kept as a pure decision, separate from the
 * Compose dialogs it drives, so it can be unit-tested (this repo does not unit-test Compose
 * dialogs — see [decideMagisTap]).
 */
sealed class MagisTapDecision {
    /** Series: open the season dialog to pick a chapter, same as today. */
    data class OpenSeasonDialog(val result: GatewayResult) : MagisTapDecision()

    /** Movie: ask whether to watch or download it. [canDownload] hides the download choice when
     *  no download strategy is registered for Magis (defensive — Magis movies always have one). */
    data class ShowMovieDialog(val result: GatewayResult, val canDownload: Boolean) : MagisTapDecision()
}

/**
 * Decides what a tap on a Magis result should do: series still open the season dialog
 * ([MagisTapDecision.OpenSeasonDialog], unchanged behavior); movies now ask to watch or download
 * ([MagisTapDecision.ShowMovieDialog]) instead of playing right away.
 */
fun decideMagisTap(result: GatewayResult, canDownload: Boolean): MagisTapDecision =
    if (result.extra["program_type"] in MAGIS_SERIES) MagisTapDecision.OpenSeasonDialog(result)
    else MagisTapDecision.ShowMovieDialog(result, canDownload)

/**
 * Text for the "queued" toast after downloading a Magis movie from the watch/download dialog, or
 * null to show nothing. Only [EnqueueOutcome.QUEUED] gets this toast: [EnqueueOutcome.ALREADY_QUEUED]
 * and [EnqueueOutcome.ALREADY_DOWNLOADED] already surface their own message through
 * `rememberDuplicateDownloadNotice`, so a "queued" toast on top of that would be misleading — the
 * user would see both "you already have that" and a false "queued".
 */
fun queuedDownloadToastText(outcome: EnqueueOutcome, movieTitle: String): String? =
    if (outcome == EnqueueOutcome.QUEUED) "Descarga de \"$movieTitle\" en cola" else null
