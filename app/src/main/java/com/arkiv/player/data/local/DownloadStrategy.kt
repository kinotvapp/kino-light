package com.arkiv.player.data.local

import java.io.File

/** Result of trying to download an episode. */
sealed interface DownloadOutcome {
    data class Done(val file: File) : DownloadOutcome
    /** Legacy of the torrent size gate (source removed in this branch's pruning): no strategy returns this today. */
    data class NeedsConfirmation(val fileSizeBytes: Long) : DownloadOutcome
    /**
     * [transient] = "this might work in a bit" (network cut, 5xx, torrent in use). The worker uses
     * it to decide whether to return `Result.retry()` (WorkManager's backoff) or mark the row
     * `failed`. Defaults to false: a new reason nobody classified shouldn't retry on its own.
     * See [DownloadRetryPolicy].
     */
    data class Failed(val reason: String, val transient: Boolean = false) : DownloadOutcome
}

/**
 * How ONE source is downloaded. The worker picks the implementation by `source` and knows nothing
 * about libtorrent, the NUC or archive.org.
 *
 * Strategies resolve the origin by querying the repository for `episodeId` (same as
 * `PlayerViewModel` does today), instead of receiving it as a parameter: that way the `downloads`
 * table doesn't duplicate data that already lives in `items`/`episodes`, and there aren't two
 * sources of truth that can drift apart.
 */
interface DownloadStrategy {
    suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit,
    ): DownloadOutcome

    /**
     * Deletes what [download] left OUTSIDE [targetDir], if it left anything.
     *
     * `LocalDownloadManager.remove` knows how to delete a file and sweep the downloads directory
     * by prefix, and that was enough while every download was a file. Caracol isn't one: its bytes
     * live in a media3 cache shared by every chapter, and only its own strategy knows which ones
     * belong to which. Without this hook, "Remove" deleted the row and left the megabytes taking
     * up disk forever.
     *
     * Empty by default: a strategy that only writes a file has nothing to add.
     */
    suspend fun clearLeftovers(episodeId: String, targetDir: File) = Unit
}
