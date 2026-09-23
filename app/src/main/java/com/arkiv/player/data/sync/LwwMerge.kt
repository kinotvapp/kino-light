package com.arkiv.player.data.sync

/** Last-Write-Wins: resolves conflicts by comparing logical clocks `updatedAt`. */
object LwwMerge {
    /** true if the remote wins (remote.updatedAt > local.updatedAt); tie = local stays. */
    fun pickWinner(localUpdatedAt: Long, remoteUpdatedAt: Long): Boolean = remoteUpdatedAt > localUpdatedAt
}
