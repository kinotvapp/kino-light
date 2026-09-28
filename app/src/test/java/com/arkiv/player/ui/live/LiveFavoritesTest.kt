package com.arkiv.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveFavoritesTest {

    @Test
    fun `removing a row hands focus to the one below it`() {
        assertEquals("c", neighborAfterRemoval(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `removing the last row hands focus to the one above it`() {
        assertEquals("b", neighborAfterRemoval(listOf("a", "b", "c"), "c"))
    }

    @Test
    fun `removing the only row leaves no row to focus`() {
        assertNull(neighborAfterRemoval(listOf("a"), "a"))
    }

    @Test
    fun `a code that is not on the list has no neighbour`() {
        assertNull(neighborAfterRemoval(listOf("a", "b"), "z"))
    }

    @Test
    fun `the feedback line says what just happened`() {
        assertEquals("Agregado a favoritos", favoriteNotice(added = true))
        assertEquals("Quitado de favoritos", favoriteNotice(added = false))
    }
}
