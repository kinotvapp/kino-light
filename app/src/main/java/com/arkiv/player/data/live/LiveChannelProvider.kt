package com.arkiv.player.data.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.playback.PluginLiveChannel
import kotlinx.coroutines.flow.StateFlow

/** One category of one provider. [id] is the provider's own (Xuper's portal number as text, a plugin's string id). */
data class ProviderCategory(val id: String, val name: String)

/** What a screen shows for a provider: its chip/section and the badge on its channels. */
data class LiveProviderTab(val id: String, val name: String, val color: Long)

/** How the player opens a channel: each provider keeps its own playback path. */
sealed interface LiveOpening {
    /** Xuper: the local `LiveHlsProxy` URL, played by `LiveExoPlayer` (seed rotation, preheat, reopen policy). */
    data class Proxied(val url: String) : LiveOpening
    /** A plugin: handed to `PluginLive`, resolved by `PlayerViewModel.loadPlugin` and played by `StreamExoPlayer`. */
    data class Plugin(val channel: PluginLiveChannel) : LiveOpening
}

/**
 * A source of channels for the En vivo module (spec §1). [id] is a [com.arkiv.player.data.gateway.LiveChannelKeys]
 * provider; every [LiveChannel] it returns carries it.
 */
interface LiveChannelProvider {
    val id: String
    val name: String
    /** Opaque ARGB for the provider badge. */
    val color: Long

    /** The category a fresh screen can paint from cache before [categories] answers; null = wait for them and take the first. */
    fun initialCategory(): String?

    suspend fun categories(includeAdults: Boolean): List<ProviderCategory>

    /** Every channel of [categoryId] (all pages). [force] skips in-memory caches (the "Recargar" button). */
    suspend fun channels(categoryId: String, force: Boolean = false): List<LiveChannel>

    /** Programmes by live code for those of [channels] that are this provider's, and the live codes worth one retry later. */
    suspend fun guide(channels: List<LiveChannel>): Pair<Map<String, List<LiveProgram>>, List<String>>

    /** Whether this provider can have programme data at all; the phone guide toggle hides otherwise. */
    fun hasGuide(): Boolean

    suspend fun open(channel: LiveChannel): LiveOpening
}

/** The En vivo module as screens and the player see it: its providers, in order, live. */
interface LiveModule {
    val providers: StateFlow<List<LiveChannelProvider>>

    fun provider(id: String): LiveChannelProvider? = providers.value.firstOrNull { it.id == id }

    /** What the player says when a channel's provider is not (or no longer) in [providers]. */
    fun blockedMessage(providerId: String): String
}
