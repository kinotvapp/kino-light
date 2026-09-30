package com.arkiv.player.data.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.playback.PluginLiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One category of one provider. [id] is the provider's own (Xuper's portal number as text, a plugin's string id).
 * [genre] is a [com.arkiv.player.data.plugin.Genre] id, declared by the plugin or guessed from [name]; null = unknown.
 */
data class ProviderCategory(val id: String, val name: String, val genre: String? = null)

/** What a screen shows for a provider: its chip/section and the badge on its channels. */
data class LiveProviderTab(val id: String, val name: String, val color: Long)

/** How the player opens a channel: each provider keeps its own playback path. */
sealed interface LiveOpening {
    /** Xuper: the local `LiveHlsProxy` URL, played by `LiveExoPlayer` (seed rotation, preheat, reopen policy). */
    data class Proxied(val url: String) : LiveOpening
    /**
     * A plugin: handed to `PluginLive`, resolved by `PlayerViewModel.loadPlugin` and played by
     * `StreamExoPlayer`. [adult] is the listed channel's mark, so recents never log it even when
     * the player only had the bare live code.
     */
    data class Plugin(val channel: PluginLiveChannel, val adult: Boolean = false) : LiveOpening
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

    /**
     * Called once by the module when it drops this instance (its plugin changed, was switched off
     * or uninstalled): work still running for it is cancelled and new work refused. Default: nothing.
     */
    fun close() {}

    /**
     * The channels this provider already holds in memory (listed categories, parsed playlists),
     * for the En vivo search across providers. Never a plugin call, a download or a throw, so it
     * can run on every search; channels never loaded are simply not here. Default: none.
     */
    suspend fun knownChannels(): List<LiveChannel> = emptyList()

    /** Whether some of this provider's categories were never listed, so [knownChannels] misses them. Memory only. */
    fun hasUnloadedCategories(): Boolean = false

    /** A note the screens show over this provider's channels (a plugin's "Lista recortada…"); null = none. */
    val notice: StateFlow<String?> get() = NO_NOTICE
}

private val NO_NOTICE: StateFlow<String?> = MutableStateFlow(null)

/** The En vivo module as screens and the player see it: its providers, in order, live. */
interface LiveModule {
    val providers: StateFlow<List<LiveChannelProvider>>

    fun provider(id: String): LiveChannelProvider? = providers.value.firstOrNull { it.id == id }

    /** What the player says when a channel's provider is not (or no longer) in [providers]. */
    fun blockedMessage(providerId: String): String
}
