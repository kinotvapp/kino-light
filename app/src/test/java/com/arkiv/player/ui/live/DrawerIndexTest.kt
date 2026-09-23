package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which row the drawer lands on when it opens.
 *
 * MEASURED ON THE FIRE TV on 2026-08-14: on opening the drawer, the list scrolled itself upward
 * on its own for a long while until the first channel, instead of staying on the one being watched.
 *
 * There were TWO effects fighting each other. One did `scrollToItem` to the live channel; the
 * other requested focus, with the `FocusRequester` set on item 0 -- requesting focus on the first
 * row drags the whole list back to the top. With 1040 channels, that drag looks like an endless scroll.
 *
 * The structural fix is that the index gets computed ONCE and serves both purposes: the same row
 * that gets focus is the one that gets scrolled into view. This function is that single index,
 * and it lives out here so its edges can be pinned down -- the project has no UI tests.
 */
class DrawerIndexTest {

    private fun channel(code: String) = LiveChannel(code, code.uppercase(), 0, null)
    private val list = listOf(channel("a"), channel("b"), channel("c"))

    @Test fun `lands on the channel being watched`() {
        assertEquals(1, DrawerIndex.indexFor(list, "b"))
    }

    /**
     * The case that shows up as soon as you type in the search box: the filtered list might not
     * contain the channel on screen. It has to land on the first result -- NOT on -1, which would
     * blow up `scrollToItem`, nor leaving focus on a row that no longer exists.
     */
    @Test fun `if the channel isn't in the list, it lands on the first one`() {
        assertEquals(0, DrawerIndex.indexFor(list, "z"))
        assertEquals(0, DrawerIndex.indexFor(list, null))
        assertEquals(0, DrawerIndex.indexFor(list, ""))
    }

    @Test fun `with an empty list there's no valid index`() {
        assertEquals(0, DrawerIndex.indexFor(emptyList(), "a"))
    }
}
