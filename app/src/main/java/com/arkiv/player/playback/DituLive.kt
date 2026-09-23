package com.arkiv.player.playback

import com.arkiv.player.data.ditu.DituChannel

/**
 * A Caracol live channel on its way to the player.
 *
 * `PlayerViewModel.loadDitu` reads the library's `ref`, and a live channel is never there: it has
 * no `ref` -it's identified by the `channelId`/`assetId` pair that came with the list, see
 * [DituChannel]- nor progress to save. So the channel travels outside the navigation route, with the
 * same pattern as [MagisEphemeral]: the Caracol section leaves it with [leave] and the player
 * picks it up with [take].
 *
 * [take] does NOT clear it, same as [MagisEphemeral.take]. When the stream's token expires, the
 * player resolves the same channel again (`PlayerViewModel.onDituExoError` -> `loadDitu`), and for
 * that it still has to find it here.
 */
internal object DituLive {

    /** Starts with `ditu:` so [PlayerSource.kindFor] routes it to Caracol (`loadDitu`). */
    const val PREFIX = "ditu:vivo:"

    fun isLive(episodeId: String): Boolean = episodeId.startsWith(PREFIX)

    private class Pending(val episodeId: String, val channel: DituChannel)

    @Volatile
    private var pending: Pending? = null

    /** Leaves [channel] for the player and returns the `episodeId` to navigate with. */
    fun leave(channel: DituChannel): String {
        val id = "$PREFIX${channel.channelId}"
        pending = Pending(id, channel)
        return id
    }

    /**
     * The channel left for [episodeId], or null if what's saved belongs to a different playback:
     * that way a live channel never reopens an old one.
     */
    fun take(episodeId: String): DituChannel? = pending?.takeIf { it.episodeId == episodeId }?.channel
}
