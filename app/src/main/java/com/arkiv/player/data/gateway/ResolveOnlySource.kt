package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A [ContentSource] that still opens its own refs (resolve, episodes, listings) but is never
 * searched: [search] emits nothing and never reaches [inner], so it makes no network call and
 * announces no `SourceStart` (no section, no tab, no spinner). Used for Caracol while it is hidden
 * (`CaracolVisibility`), so titles saved from it keep playing.
 */
internal class ResolveOnlySource(private val inner: ContentSource) : ContentSource by inner {
    override val searchTimeoutMs: Long? get() = null

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = emptyFlow()
}
