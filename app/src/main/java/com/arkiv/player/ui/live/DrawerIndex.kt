package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel

/**
 * Which row the channel drawer lands on when it opens: the one for the channel being watched.
 *
 * Exists as a separate function for a concrete reason. Measured on the Fire TV on 2026-08-14: on
 * opening the drawer, the list scrolled itself upward on its own for a long while until the first
 * channel. There were TWO effects fighting each other -- one did `scrollToItem` to the live
 * channel and another requested focus, with the `FocusRequester` set on item 0. Requesting focus
 * on the first row drags the whole list back to the top, and with 1040 channels that drag looks endless.
 *
 * The fix isn't picking which effect wins: it's having a SINGLE index. The same row that gets
 * focus is the one that gets scrolled into view, and by construction they can't disagree.
 */
object DrawerIndex {

    /**
     * Never returns -1: the result goes straight to `scrollToItem` and to deciding which row
     * carries the `FocusRequester`. With the channel outside the list --the first thing that
     * happens when typing in the search box-- it lands on the first result, which is what you want
     * to look at at that moment.
     */
    fun indexFor(channels: List<LiveChannel>, currentChannel: String?): Int {
        if (channels.isEmpty() || currentChannel.isNullOrBlank()) return 0
        return channels.indexOfFirst { it.code == currentChannel }.coerceAtLeast(0)
    }
}
