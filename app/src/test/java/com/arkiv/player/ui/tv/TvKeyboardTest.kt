package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvKeyboardTest {
    @Test fun `the grid brings A-Z and 0-9 exactly once`() {
        val chars = TV_KEYBOARD_ROWS.flatten().filterIsInstance<TvKey.Char>().map { it.c }
        assertEquals(('A'..'Z').toList() + ('0'..'9').toList(), chars)
        assertEquals(chars.size, chars.toSet().size)
    }

    @Test fun `the grid has space and backspace`() {
        val keys = TV_KEYBOARD_ROWS.flatten()
        assertTrue(keys.contains(TvKey.Space))
        assertTrue(keys.contains(TvKey.Backspace))
    }

    @Test fun `the rows have at most 6 columns`() {
        assertTrue(TV_KEYBOARD_ROWS.all { it.size <= 6 })
    }

    @Test fun `typing adds the letter`() {
        assertEquals("SUP", applyKey("SU", TvKey.Char('P')))
    }

    @Test fun `space adds a space`() {
        assertEquals("LA ", applyKey("LA", TvKey.Space))
    }

    @Test fun `backspace removes the last character`() {
        assertEquals("SU", applyKey("SUP", TvKey.Backspace))
    }

    @Test fun `backspace on empty doesn't break`() {
        assertEquals("", applyKey("", TvKey.Backspace))
    }

    // --- Task 9: extended keyboard (lowercase + symbols) for the TV's login. The search keyboard's
    // applyKey reducer is unchanged -the tests above didn't change-; these test the new additions
    // (the mode row, and the ⌨ system-keyboard door now shared by the login and search grids).

    @Test fun `applyKey with a mode key doesn't touch the text`() {
        assertEquals("SU", applyKey("SU", TvKey.Mode(TvKeyboardMode.LOWER)))
        assertEquals("SU", applyKey("SU", TvKey.Mode(TvKeyboardMode.SYMBOLS)))
        assertEquals("SU", applyKey("SU", TvKey.Mode(TvKeyboardMode.UPPER)))
    }

    @Test fun `tvKeyboardRows UPPER brings A-Z and 0-9, the mode row, and space-backspace`() {
        val rows = tvKeyboardRows(TvKeyboardMode.UPPER)
        val chars = rows.flatten().filterIsInstance<TvKey.Char>().map { it.c }
        assertEquals(('A'..'Z').toList() + ('0'..'9').toList(), chars)
        val flat = rows.flatten()
        assertTrue(flat.contains(TvKey.Mode(TvKeyboardMode.UPPER)))
        assertTrue(flat.contains(TvKey.Mode(TvKeyboardMode.LOWER)))
        assertTrue(flat.contains(TvKey.Mode(TvKeyboardMode.SYMBOLS)))
        assertTrue(flat.contains(TvKey.Space))
        assertTrue(flat.contains(TvKey.Backspace))
    }

    @Test fun `tvKeyboardRows LOWER brings a-z and 0-9`() {
        val chars = tvKeyboardRows(TvKeyboardMode.LOWER).flatten().filterIsInstance<TvKey.Char>().map { it.c }
        assertEquals(('a'..'z').toList() + ('0'..'9').toList(), chars)
    }

    @Test fun `tvKeyboardRows SYMBOLS brings at-sign, period, and hyphen (what the brief asks for)`() {
        val chars = tvKeyboardRows(TvKeyboardMode.SYMBOLS).flatten().filterIsInstance<TvKey.Char>().map { it.c }
        assertTrue(chars.contains('@'))
        assertTrue(chars.contains('.'))
        assertTrue(chars.contains('-'))
        assertEquals("no repeated symbols", chars.size, chars.toSet().size)
    }

    @Test fun `all of the variants' rows have at most 6 columns`() {
        TvKeyboardMode.entries.forEach { mode ->
            assertTrue("mode=$mode", tvKeyboardRows(mode).all { it.size <= 6 })
        }
    }

    @Test fun `typing in lowercase adds the letter as-is`() {
        assertEquals("su", applyKey("s", TvKey.Char('u')))
    }

    // --- native-keyboard hand-off ---

    @Test fun `applyKey with the native-keyboard key doesn't touch the text`() {
        assertEquals("SU", applyKey("SU", TvKey.NativeKeyboard))
    }

    @Test fun `both the extended keyboard and search's grid carry the native-keyboard key`() {
        TvKeyboardMode.entries.forEach { mode ->
            assertTrue("mode=$mode", tvKeyboardRows(mode).flatten().contains(TvKey.NativeKeyboard))
        }
        // Search's grid now offers the same ⌨ door (its last row is Space + Backspace + ⌨).
        assertTrue(TV_KEYBOARD_ROWS.flatten().contains(TvKey.NativeKeyboard))
        assertEquals(listOf(TvKey.Space, TvKey.Backspace, TvKey.NativeKeyboard), TV_KEYBOARD_ROWS.last())
    }
}
