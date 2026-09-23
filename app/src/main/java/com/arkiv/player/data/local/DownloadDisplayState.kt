package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow

/**
 * What a chapter row has to SHOW about its download.
 *
 * Exists because the chapter list only knew two things ("it's saved" / "it's doing something"),
 * and with that a queued chapter, a downloading one and one waiting for confirmation all looked
 * the same: a spinner that doesn't say whether it's at 5% or 90%, or whether anything is really
 * happening.
 */
sealed interface DownloadDisplayState {
    /** Nobody queued it: the row offers the download button. */
    data object NotDownloaded : DownloadDisplayState

    /** Queued, waiting its turn — the queue is one at a time (see [DownloadQueuePolicy]). */
    data object Queued : DownloadDisplayState

    /**
     * In progress. [fraction] is `null` when how much is left can't be known (total size
     * unknown, or the server's staging phase, which can only report 0/1): in that case the bar
     * goes indeterminate instead of lying with a stuck 0%.
     */
    data class Downloading(val fraction: Float?) : DownloadDisplayState

    /** Already on the device. */
    data object Done : DownloadDisplayState

    /** Failed. [reason] is what the row saved, so it can be said instead of staying silent. */
    data class Failed(val reason: String?) : DownloadDisplayState

    /** A torrent past the size threshold: nothing downloads until the user confirms. */
    data object NeedsConfirmation : DownloadDisplayState
}

/** Translates a `downloads` table row into what shows in the chapter list. */
object ChapterDownloadState {

    fun of(row: DownloadRow?): DownloadDisplayState = when (row?.state) {
        null -> DownloadDisplayState.NotDownloaded
        // COMPLETED goes BEFORE checking the error on purpose: a completed row can carry an
        // "error" that isn't a failure but the reason nothing had to be downloaded
        // (DuplicateDownloadPolicy.ADOPTED_REASON, "Ya estaba descargado").
        LocalDownloadState.COMPLETED -> DownloadDisplayState.Done
        LocalDownloadState.FAILED -> DownloadDisplayState.Failed(row.error)
        LocalDownloadState.NEEDS_CONFIRMATION -> DownloadDisplayState.NeedsConfirmation
        LocalDownloadState.STAGING -> DownloadDisplayState.Downloading(null)
        LocalDownloadState.DOWNLOADING ->
            DownloadDisplayState.Downloading(if (row.bytes > 0) row.progress else null)
        LocalDownloadState.QUEUED -> DownloadDisplayState.Queued
        // A state we don't know can't leave the row without its download button.
        else -> DownloadDisplayState.NotDownloaded
    }
}
