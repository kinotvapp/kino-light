package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.gateway.liveCode

/**
 * Goes through **the list the channel was entered with** (the category, favorites, or search
 * result), which is the one the user has in mind. Wraps around at the ends, like a set-top box.
 */
class LiveZapping(private val list: List<LiveChannel>, initialIndex: Int) {
    private var index = initialIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))

    val current: LiveChannel get() = list[index]

    fun next(): LiveChannel {
        index = (index + 1) % list.size
        return current
    }

    fun previous(): LiveChannel {
        index = (index - 1 + list.size) % list.size
        return current
    }

    /** Empty if there's a single channel: nowhere to zap to and nothing to preheat. */
    fun neighbors(): List<LiveChannel> {
        if (list.size < 2) return emptyList()
        return listOf(list[(index + 1) % list.size], list[(index - 1 + list.size) % list.size])
    }
}

/**
 * Ephemeral bridge between the "En vivo" screen (grid/guide) and the player: the list the user
 * entered with (category, favorites, recents, or search result) -- what [LiveZapping] needs to go
 * through the SAME list the user has in mind, not the whole catalog.
 *
 * A list of [LiveChannel] doesn't cross a String-argument-based NavHost well: the "player/{episodeId}"
 * route (shared with VOD) only carries the channel code. Same problem
 * [com.arkiv.player.playback.NowPlaying] already solves for other ephemeral state between screens,
 * and the same solution: a short-lived mutable object, set BEFORE navigating (by `LiveScreen` /
 * `TvLiveGuideScreen`, via `ArkivRoot`/`ArkivTvRoot`) and consumed exactly once by
 * `PlayerViewModel.loadLive` on opening an episodeId with the
 * [com.arkiv.player.playback.PlayerSource.LIVE_PREFIX] prefix.
 *
 * If nothing is set here (the process got recreated mid live-player, or whoever navigates didn't
 * go through the grid) `PlayerViewModel` falls back to a single-channel list: zapping is lost
 * until returning to the grid, but the chosen channel still plays.
 *
 * The list may mix providers (a favourites or recents list, the Home row): `PlayerViewModel`
 * narrows it to the chosen channel's provider with [zappingListFor], so zapping never jumps from
 * one provider to another.
 */
object LiveZappingSource {
    var list: List<LiveChannel> = emptyList()
}

/**
 * The list zapping walks after [chosen] is opened from [entry]: [entry] narrowed to [chosen]'s
 * provider (spec §4: zapping never jumps between providers silently). [chosen] leads when the
 * entry list lacks it, so the channel asked for is always the one that plays.
 */
fun zappingListFor(entry: List<LiveChannel>, chosen: LiveChannel): List<LiveChannel> {
    val same = entry.filter { it.provider == chosen.provider }
    return if (same.any { it.liveCode == chosen.liveCode }) same else listOf(chosen) + same
}

/** The channel a `live:` route names: from the entry list when there, else rebuilt from the code (name = code). */
fun channelForLiveCode(liveCode: String, entry: List<LiveChannel>): LiveChannel? =
    entry.firstOrNull { it.liveCode == liveCode }
        ?: LiveChannelKeys.parse(liveCode)?.let { (provider, code) -> LiveChannel(code, code, 0, null, provider = provider) }

/** Whether the channel on screen belongs to a provider that is no longer in the module. */
fun providerGone(channel: LiveChannel?, providerIds: Collection<String>): Boolean =
    channel != null && channel.provider !in providerIds

/** What `loadPlugin` plays for a live episode: the handed-over direct stream, else the plugin's `resolve(ref)`. */
internal sealed interface PluginLivePlay {
    data class Direct(val playable: com.arkiv.player.data.gateway.GatewayPlayable) : PluginLivePlay
    data class Resolve(val ref: String) : PluginLivePlay
    data object Missing : PluginLivePlay
}

internal fun pluginLivePlay(channel: com.arkiv.player.playback.PluginLiveChannel?): PluginLivePlay = when {
    channel == null -> PluginLivePlay.Missing
    channel.direct != null -> PluginLivePlay.Direct(channel.direct)
    channel.ref.isNotBlank() -> PluginLivePlay.Resolve(channel.ref)
    else -> PluginLivePlay.Missing
}
