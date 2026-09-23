package com.arkiv.player.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the home list should snap back to the top when the top sections (hero, continue
 * watching, live channels, library) change shape.
 *
 * Root cause of the bug this guards against: those sections used to be conditionally-emitted,
 * unkeyed `item {}` blocks. When one of them got data after the first frame, Compose inserted a
 * new item ABOVE the already-visible, stably-keyed remote rows, and `LazyColumn` kept the keyed
 * row pinned on screen -- leaving the list scrolled to the middle instead of the top. Making the
 * top sections always-present with stable keys removes the insertion, but this decision is the
 * safety net that also covers any remaining case where the list isn't already at the top when the
 * content above changes.
 */
class HomeScrollTest {

    @Test
    fun `no snap once the user has scrolled by hand, even if the signature changed`() {
        assertFalse(
            shouldSnapHomeToTop(
                userScrolled = true,
                firstVisibleItemIndex = 4,
                firstVisibleItemScrollOffset = 120,
                signatureChanged = true,
            ),
        )
    }

    @Test
    fun `no snap when the top sections did not change shape`() {
        assertFalse(
            shouldSnapHomeToTop(
                userScrolled = false,
                firstVisibleItemIndex = 4,
                firstVisibleItemScrollOffset = 120,
                signatureChanged = false,
            ),
        )
    }

    @Test
    fun `no snap when the list is already at the top`() {
        assertFalse(
            shouldSnapHomeToTop(
                userScrolled = false,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                signatureChanged = true,
            ),
        )
    }

    @Test
    fun `snaps when the signature changed and the index drifted away from the top`() {
        assertTrue(
            shouldSnapHomeToTop(
                userScrolled = false,
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffset = 0,
                signatureChanged = true,
            ),
        )
    }

    @Test
    fun `snaps when the signature changed and only the offset drifted`() {
        assertTrue(
            shouldSnapHomeToTop(
                userScrolled = false,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 37,
                signatureChanged = true,
            ),
        )
    }

    // ---- the signature itself ----

    @Test
    fun `two signatures with the same shape are equal`() {
        val a = TopSectionsSignature(heroVisible = true, continueWatchingCount = 2, channelsCount = 3, libraryCount = 5)
        val b = TopSectionsSignature(heroVisible = true, continueWatchingCount = 2, channelsCount = 3, libraryCount = 5)
        assertTrue(a == b)
    }

    @Test
    fun `a change in a single section changes the signature`() {
        val before = TopSectionsSignature(heroVisible = false, continueWatchingCount = 0, channelsCount = 0, libraryCount = 0)
        val heroAppears = before.copy(heroVisible = true)
        val channelsGrow = before.copy(channelsCount = 4)
        assertFalse(before == heroAppears)
        assertFalse(before == channelsGrow)
        assertFalse(heroAppears == channelsGrow)
    }
}
