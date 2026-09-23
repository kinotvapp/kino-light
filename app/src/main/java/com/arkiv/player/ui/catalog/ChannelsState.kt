package com.arkiv.player.ui.catalog

import com.arkiv.player.data.ditu.DituChannel
import com.arkiv.player.data.ditu.CaracolFailure

/**
 * What the Caracol "En vivo" tab shows, built from the `DituSource.channels` call. Shared by the TV
 * screen (`com.arkiv.player.ui.tv.TvCaracolScreen`) and the phone one ([CaracolScreen]).
 *
 * Exists so a failure does NOT look like "no channels": if Caracol or the network fails, the tab
 * says so in plain words ([CaracolFailure.onLoadChannels]; the detail goes to whichever
 * screen's log) and the person can retry with "Recargar".
 */
internal sealed interface ChannelsState {

    /** The first call hasn't come back yet. */
    object Loading : ChannelsState

    data class Ready(val channels: List<DituChannel>) : ChannelsState

    /** Caracol responded, and with no channels. */
    object Empty : ChannelsState

    data class Failed(val message: String) : ChannelsState

    companion object {
        fun from(result: Result<List<DituChannel>>): ChannelsState = result.fold(
            onSuccess = { if (it.isEmpty()) Empty else Ready(it) },
            onFailure = { Failed(CaracolFailure.onLoadChannels(it)) },
        )
    }
}
