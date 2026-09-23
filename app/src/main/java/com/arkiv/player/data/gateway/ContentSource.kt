package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow

/**
 * Where the titles the app searches and plays come from.
 *
 * Exists so sub-project 2A's wiring is a constructor change: the screens depend on this
 * interface and not on a concrete gateway client, so moving from the gateway to the portal's
 * direct client doesn't touch them. Today two sources implement it, `MagisSource` and
 * `DituSource`, and a third implementation, `CompositeSource`, joins them behind the single
 * object the screens see (`AppGraph.contentSource`). The models are still called `Gateway*`
 * because renaming them would be churn with no gain (they're the contract, not the transport).
 *
 * Errors travel as [GatewayException]: whoever calls already catches them that way.
 */
interface ContentSource {
    /**
     * Whether this `ref` belongs to this source. Exists since there's more than one
     * implementation (`MagisSource`, `DituSource`): each one knows how to read its own —including
     * old gateway ones, which carry no visible prefix— without whoever dispatches having to guess
     * from outside.
     */
    fun recognizes(ref: String): Boolean

    fun search(ctx: GatewaySearchQuery): Flow<SearchEvent>

    suspend fun resolve(ref: String): GatewayPlayable

    /**
     * Episodes of a season and, if the series could be matched against TMDB, its [GatewaySeries]
     * block (null if not).
     */
    suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?>

    suspend fun episodes(ref: String): List<GatewayEpisode> = episodesWithSeries(ref).first
}
