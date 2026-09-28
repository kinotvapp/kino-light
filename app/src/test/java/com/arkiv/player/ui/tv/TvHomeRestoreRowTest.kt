package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coming back to a Home card must also bring ITS ROW into view: on a TV the restored focus is
 * invisible when the row sits below the fold (found on a device with the last plugin row).
 * [homeRowIndexOf] locates that row; the list ends with the plugin rows, then one trailing pad
 * item, and whatever comes before them varies, so the index counts back from the end.
 */
class TvHomeRestoreRowTest {

    // Two leading items (say "Continuar viendo" and the channels row), 3 plugin rows and the
    // trailing pad: indexes 0-1 lead, 2-4 plugin, 5 pad.
    private val plugin = listOf(listOf("plugin-p-r1-x"), listOf("plugin-p-r2-y", "plugin-p-r2-z"), listOf("plugin-q-r1-w"))
    private val total = 6

    @Test
    fun `a plugin card's row is counted back from the end`() {
        assertEquals(2, homeRowIndexOf("plugin-p-r1-x", plugin, total))
        assertEquals(3, homeRowIndexOf("plugin-p-r2-z", plugin, total))
        assertEquals(4, homeRowIndexOf("plugin-q-r1-w", plugin, total))
    }

    @Test
    fun `a card no row holds has no row`() {
        assertNull(homeRowIndexOf("plugin-z-r9-n", plugin, total))
    }

    @Test
    fun `the match is the exact card key, not a prefix of a neighbouring row`() {
        val rows = listOf(listOf("plugin-a-x"), listOf("plugin-a-b-item"))
        // "plugin-a-b-item" starts with row "a"'s "plugin-a-" but belongs to the second row.
        assertEquals(1, homeRowIndexOf("plugin-a-b-item", rows, totalItems = 3))
    }

    @Test
    fun `a card is held only by a row that has it`() {
        assertTrue(homeCardsHold("plugin-p-r2-y", plugin))
        // Another plugin's row has not arrived yet (or the card is gone): nothing holds it.
        assertFalse(homeCardsHold("plugin-r-r1-x", plugin))
        assertFalse(homeCardsHold("plugin-p-r1-x", emptyList()))
    }

    @Test
    fun `the row above the card's is scrolled to first, so the card sits where navigating puts it`() {
        // With the card's row at the top of a two-row region and the list ending there, the previous
        // row's label was clipped; showing the previous row first is the layout D-pad navigation gives.
        assertEquals(4, homeRowScrollTarget(5))
        assertEquals(0, homeRowScrollTarget(1))
        assertEquals(0, homeRowScrollTarget(0))
    }

    @Test
    fun `a list shorter than its rows has no index`() {
        // Only 2 items in the list but 3 rows plus the pad: the list is not laid out yet.
        assertNull(homeRowIndexOf("plugin-p-r1-x", plugin, totalItems = 2))
    }

    @Test
    fun `skeleton rows after the plugin rows are trailing items, not rows`() {
        // Same list plus one skeleton row between the last plugin row and the pad: indexes 0-1 lead,
        // 2-4 plugin, 5 skeleton, 6 pad. The plugin rows keep their indexes.
        assertEquals(2, tvHomeTrailingItems(skeletonRows = 1))
        assertEquals(1, tvHomeTrailingItems(skeletonRows = 0))
        assertEquals(2, homeRowIndexOf("plugin-p-r1-x", plugin, totalItems = 7, trailingItems = tvHomeTrailingItems(1)))
        assertEquals(4, homeRowIndexOf("plugin-q-r1-w", plugin, totalItems = 7, trailingItems = tvHomeTrailingItems(1)))
    }
}
