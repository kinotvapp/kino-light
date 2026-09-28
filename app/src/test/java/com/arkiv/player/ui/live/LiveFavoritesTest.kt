package com.arkiv.player.ui.live

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `the dialog asks to add a channel that is not a favourite`() {
        assertEquals(
            FavoriteDialogCopy("¿Agregar Caracol TV a favoritos?", "Agregar"),
            favoriteDialogCopy("Caracol TV", isFavorite = false),
        )
    }

    @Test
    fun `the dialog asks to remove a channel that is already a favourite`() {
        assertEquals(
            FavoriteDialogCopy("¿Quitar Caracol TV de favoritos?", "Quitar"),
            favoriteDialogCopy("Caracol TV", isFavorite = true),
        )
    }

    @Test
    fun `the right arrow on a module channel opens the favourite dialog`() {
        assertTrue(liveKeyOpensFavoriteDialog(KeyEvent.KEYCODE_DPAD_RIGHT, isModuleLive = true, drawerOpen = false))
    }

    @Test
    fun `the right arrow does nothing on a channel outside the module`() {
        assertFalse(liveKeyOpensFavoriteDialog(KeyEvent.KEYCODE_DPAD_RIGHT, isModuleLive = false, drawerOpen = false))
    }

    @Test
    fun `the right arrow belongs to the drawer while it is open`() {
        assertFalse(liveKeyOpensFavoriteDialog(KeyEvent.KEYCODE_DPAD_RIGHT, isModuleLive = true, drawerOpen = true))
    }

    @Test
    fun `no other key opens the favourite dialog`() {
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
        )) {
            assertFalse(liveKeyOpensFavoriteDialog(key, isModuleLive = true, drawerOpen = false))
        }
    }

    @Test
    fun `the dialog swallows the tail of the press that opened it`() {
        val gate = FreshPressGate()
        assertFalse(gate.admit(isDown = true, repeatCount = 3))
        assertFalse(gate.admit(isDown = false, repeatCount = 0))
    }

    @Test
    fun `the dialog admits keys from the first fresh press on`() {
        val gate = FreshPressGate()
        assertFalse(gate.admit(isDown = false, repeatCount = 0))
        assertTrue(gate.admit(isDown = true, repeatCount = 0))
        assertTrue(gate.admit(isDown = false, repeatCount = 0))
        assertTrue(gate.admit(isDown = true, repeatCount = 1))
    }
}
