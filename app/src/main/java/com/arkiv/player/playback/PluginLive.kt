package com.arkiv.player.playback

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.plugin.PluginOutput
import com.arkiv.player.data.plugin.PluginRef

/**
 * A plugin's live channel on its way to the player: [ref] is the wrapped `plg1:` ref its
 * `resolve()` takes, [title] and [logo] what the card showed (the player's header and artwork).
 */
data class PluginLiveChannel(
    val episodeId: String,
    val pluginId: String,
    val ref: String,
    val title: String,
    val logo: String,
    /** A channel whose stream is already known (an inline `stream`, a playlist entry): `loadPlugin` plays it without calling `resolve()`. */
    val direct: com.arkiv.player.data.gateway.GatewayPlayable? = null,
)

/**
 * A plugin's live channel on its way to the player (apiVersion 2's item kind `"live"`).
 *
 * `PlayerViewModel.loadPlugin` reads a title's `ref` from the library, and a live channel is never
 * there: it has no row (nothing to resume, nothing for "Continuar viendo"), so the channel travels
 * outside the navigation route with the same pattern as [DituLive] and [MagisEphemeral]: the card's
 * opener leaves it with [leave] and the player picks it up with [take]. Its id ([PluginIds.liveEpisodeId])
 * is still a plugin id, so `load` routes it to `loadPlugin` and the plugin's host gate, and a live
 * one, so `PlayerScreen` shows the live overlay instead of VOD's bar.
 *
 * [take] does NOT clear it, same as [DituLive.take]: when the stream expires or cuts, the player
 * resolves the same channel again through `loadPlugin` and still has to find it here.
 */
internal object PluginLive {

    private class Pending(val episodeId: String, val channel: PluginLiveChannel)

    @Volatile
    private var pending: Pending? = null

    /**
     * The channel a card's result names, or null when it is not one a plugin may play: not a
     * `live` result, not a plugin's, a ref that is not that plugin's own live ref (the kinds must
     * agree: a `live` card wrapping a movie ref would save nothing and play as VOD), or no item id.
     */
    fun channelOf(result: GatewayResult): PluginLiveChannel? {
        if (result.kind != PluginOutput.KIND_LIVE) return null
        val pluginId = PluginIds.pluginIdOfSource(result.source) ?: return null
        val itemId = result.extra["pluginItemId"].orEmpty().ifBlank { return null }
        val ref = PluginRef.decode(result.ref) ?: return null
        if (ref.pluginId != pluginId || ref.kind != PluginRef.LIVE) return null
        return PluginLiveChannel(
            episodeId = PluginIds.liveEpisodeId(pluginId, itemId),
            pluginId = pluginId,
            ref = result.ref,
            title = result.title,
            logo = result.extra["poster"].orEmpty(),
        )
    }

    /** Leaves [channel] for the player and returns the `episodeId` to navigate with. */
    fun leave(channel: PluginLiveChannel): String {
        pending = Pending(channel.episodeId, channel)
        return channel.episodeId
    }

    /**
     * The channel left for [episodeId], or null if what's saved belongs to a different playback:
     * that way a live channel never reopens an old one, and a library title never finds a channel.
     */
    fun take(episodeId: String): PluginLiveChannel? = pending?.takeIf { it.episodeId == episodeId }?.channel
}
