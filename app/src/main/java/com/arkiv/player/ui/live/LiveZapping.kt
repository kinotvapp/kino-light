package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel

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
 */
object LiveZappingSource {
    var list: List<LiveChannel> = emptyList()
}
