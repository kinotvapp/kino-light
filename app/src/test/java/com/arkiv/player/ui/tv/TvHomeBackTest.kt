package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvHomeBackTest {

    private fun back(
        listAtTop: Boolean = true,
        focusInRows: Boolean = true,
        focusedRowIsFirst: Boolean = true,
        focusOnLanding: Boolean = false,
        justScrolledToTop: Boolean = false,
    ) = tvHomeBackAction(listAtTop, focusInRows, focusedRowIsFirst, focusOnLanding, justScrolledToTop)

    @Test
    fun `scrolled down the rows, Back goes back to the top first`() {
        assertEquals(TvHomeBack.SCROLL_TO_TOP, back(listAtTop = false, focusedRowIsFirst = false))
    }

    @Test
    fun `a scrolled list goes back up even with the top bar focused`() {
        assertEquals(TvHomeBack.SCROLL_TO_TOP, back(listAtTop = false, focusInRows = false))
    }

    @Test
    fun `focus on the second row of an unscrolled list also goes back to the landing`() {
        assertEquals(TvHomeBack.SCROLL_TO_TOP, back(focusedRowIsFirst = false))
    }

    @Test
    fun `on the first row at the top, Back keeps the usual exit flow`() {
        assertEquals(TvHomeBack.EXIT_FLOW, back())
    }

    @Test
    fun `with the top bar focused and the list at the top, Back keeps the usual exit flow`() {
        assertEquals(TvHomeBack.EXIT_FLOW, back(focusInRows = false, focusedRowIsFirst = false))
    }

    @Test
    fun `on the default landing Back keeps the usual exit flow, even if the landing left the list scrolled`() {
        // The empty state's button can sit below other rows, so landing on it scrolls the list.
        assertEquals(TvHomeBack.EXIT_FLOW, back(listAtTop = false, focusedRowIsFirst = false, focusOnLanding = true))
    }

    @Test
    fun `right after a scroll to the top, the next Back never scrolls again`() {
        // Should the landing fail, the person still reaches the exit flow instead of looping.
        assertEquals(TvHomeBack.EXIT_FLOW, back(listAtTop = false, focusedRowIsFirst = false, justScrolledToTop = true))
    }
}
