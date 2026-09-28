package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCompactActionLookTest {
    @Test fun `an enabled button keeps today's look, neutral idle and red focused`() {
        assertEquals(TvCompactActionLook(TvCompactFill.NEUTRAL, focusRing = false, contentAlpha = 1f), tvCompactActionLook(enabled = true, focused = false))
        assertEquals(TvCompactActionLook(TvCompactFill.RED, focusRing = false, contentAlpha = 1f), tvCompactActionLook(enabled = true, focused = true))
    }

    @Test fun `a disabled button never takes the red fill, focused or not`() {
        for (focused in listOf(false, true)) {
            val look = tvCompactActionLook(enabled = false, focused = focused)
            assertEquals(TvCompactFill.DIM, look.fill)
            assertTrue(look.contentAlpha < 1f)
        }
    }

    @Test fun `a focused disabled button shows where focus is with a ring, and only then`() {
        assertTrue(tvCompactActionLook(enabled = false, focused = true).focusRing)
        assertFalse(tvCompactActionLook(enabled = false, focused = false).focusRing)
        assertNotEquals(tvCompactActionLook(enabled = false, focused = true), tvCompactActionLook(enabled = true, focused = true))
    }
}
