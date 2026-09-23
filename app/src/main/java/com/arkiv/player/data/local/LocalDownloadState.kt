package com.arkiv.player.data.local

/**
 * States of a `downloads` table row. They're strings and not an enum because Room has saved them
 * that way since the table's first version, and changing it would force a converter + data
 * migration for no gain.
 */
object LocalDownloadState {
    const val QUEUED = "queued"
    /** Legacy of the torrent size gate (source removed in this branch's pruning): no strategy returns this today. */
    const val NEEDS_CONFIRMATION = "needs_confirmation"
    /** Legacy: the staging phase of the now-removed web+NUC source. Nothing writes this state today, but the UI still recognizes it. */
    const val STAGING = "staging"
    const val DOWNLOADING = "downloading"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}

/** Minimal queue row: the only thing the policy needs to decide. */
data class QueueRow(val episodeId: String, val state: String, val createdAt: Long)

/**
 * Decides which row the worker processes. The queue is ONE at a time (see the spec:
 * `TorrentEngine` is for a single active stream, the blog's disk can't take several stagings, and
 * the Fire TV's bandwidth is scarce), so this returns a single row or null.
 */
object DownloadQueuePolicy {

    /**
     * What's already started (`downloading` / `staging`) wins over what's queued: if the app got
     * killed mid-way through a 4 GB download, resuming it is worth more than starting another one
     * from scratch. Among equals, the oldest first (FIFO).
     */
    fun nextToProcess(rows: List<QueueRow>): QueueRow? {
        val inFlight = rows.filter { it.state == LocalDownloadState.DOWNLOADING || it.state == LocalDownloadState.STAGING }
        if (inFlight.isNotEmpty()) return inFlight.minByOrNull { it.createdAt }
        return rows.filter { it.state == LocalDownloadState.QUEUED }.minByOrNull { it.createdAt }
    }

    fun isTerminal(state: String): Boolean =
        state == LocalDownloadState.COMPLETED || state == LocalDownloadState.FAILED

    fun isRetryable(state: String): Boolean =
        state == LocalDownloadState.FAILED || state == LocalDownloadState.NEEDS_CONFIRMATION
}
