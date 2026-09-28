package com.arkiv.player.data.plugin.discovery

/**
 * The two numbers an author needs to be found by Kino's community search. `docs/plugins/contract.json`
 * (`discovery`) is pinned to them by `PluginContractParityTest`.
 */
object DiscoveryRules {
    /** The GitHub topic Kino searches for. */
    const val TOPIC = "kino-plugin"

    /** How many search results Kino keeps, after dropping forks and malformed ones. */
    const val MAX_RESULTS = 30
}
