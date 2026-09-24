package com.arkiv.player.data.local

/**
 * Where downloads are allowed. Offline is a PHONE/TABLET feature: a TV box has little storage and
 * a remote, and a full disk there takes the whole device down (a TV rebooted for "almacenamiento
 * lleno" with 6 GB of Kino's data). So on a TV nothing is ever queued, retried or processed.
 *
 * Pure (no Android) so the rule and the cleanup decision are tested on the JVM. It is ENFORCED in
 * the data layer ([LocalDownloadManager] and [LocalDownloadWorker]), not only by hiding the buttons:
 * hiding them is what 0.9.33 did, and a queued row, a "Reintentar" or an older screen still got
 * through, while what had already been downloaded was left with no screen to delete it from.
 */
object DownloadAvailability {

    fun allowed(isTelevision: Boolean): Boolean = !isTelevision

    /**
     * On a TV, the downloads that must not survive: every row that is NOT finished (queued,
     * downloading, failed, cancelled...). They can never be resumed there, and their `.part` files
     * are what silently holds the gigabytes.
     *
     * COMPLETED ones are deliberately kept: the person saved them on purpose, they still play, and
     * the TV library lists them so they can be watched or deleted.
     */
    fun unfinishedOnTv(rows: List<QueueRow>): List<String> =
        rows.filter { it.state != LocalDownloadState.COMPLETED }.map { it.episodeId }
}
