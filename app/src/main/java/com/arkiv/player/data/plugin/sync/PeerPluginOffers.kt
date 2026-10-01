package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.db.PluginInstallEntity

/**
 * A plugin the person has on another of their devices and not on this one: a row of
 * "Plugins de tus otros aparatos". [address] is a public GitHub address (the row was validated on
 * arrival); a Nuvio one also names its repo and scraper, so one tap installs exactly that scraper.
 */
data class PeerPluginOffer(
    val id: String,
    val name: String,
    val address: String,
    val nuvioRepo: String?,
    val nuvioScraperId: String?,
    val status: PeerOfferStatus,
)

/** The offers: every live row whose plugin is not installed here, by name. */
fun peerOffers(rows: List<PluginInstallEntity>, installedIds: Set<String>, statuses: Map<String, PeerOfferStatus>): List<PeerPluginOffer> =
    rows.filter { !it.deleted && it.id !in installedIds }
        .sortedBy { it.name.lowercase() }
        .map { PeerPluginOffer(it.id, it.name, it.address, it.nuvioRepo, it.nuvioScraperId, statuses[it.id] ?: PeerOfferStatus.WAITING) }
