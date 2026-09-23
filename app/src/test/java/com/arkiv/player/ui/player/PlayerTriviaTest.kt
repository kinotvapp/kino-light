package com.arkiv.player.ui.player

import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fun fact's pure rules: which one follows the one being viewed, whether there's anything to
 * show, and whether the episode carries a fun fact. They live in pure functions because this
 * project has no UI tests: written inside the Composable they couldn't be tested in any way
 * (same criterion as `DrawerDpad`).
 */
class PlayerTriviaTest {

    // ---- nextIndex: rotation by press ----

    @Test
    fun `each press advances to the next`() {
        assertEquals(1, PlayerTrivia.nextIndex(current = 0, count = 8))
        assertEquals(2, PlayerTrivia.nextIndex(current = 1, count = 8))
        assertEquals(7, PlayerTrivia.nextIndex(current = 6, count = 8))
    }

    /** Past the last one it goes back to the first: there's always something to show on pressing up. */
    @Test
    fun `after the last one it goes back to the first`() {
        assertEquals(0, PlayerTrivia.nextIndex(current = 7, count = 8))
    }

    /** With a single fact, pressing up leaves it where it is instead of dividing by zero or going out of bounds. */
    @Test
    fun `with a single fact it stays on it`() {
        assertEquals(0, PlayerTrivia.nextIndex(current = 0, count = 1))
    }

    @Test
    fun `with no facts there is no index to advance to`() {
        assertEquals(-1, PlayerTrivia.nextIndex(current = 0, count = 0))
        assertEquals(-1, PlayerTrivia.nextIndex(current = -1, count = 0))
    }

    /** The first up, starting from "none shown yet", has to land on the first one. */
    @Test
    fun `from none it starts on the first`() {
        assertEquals(0, PlayerTrivia.nextIndex(current = -1, count = 8))
    }

    @Test
    fun `the button exists only if there is something to read`() {
        assertFalse(PlayerTrivia.hasButton(emptyList()))
        assertTrue(PlayerTrivia.hasButton(listOf("Un dato.")))
    }

    // ---- wantsFacts: when it's worth requesting a fun fact ----

    @Test
    fun `a movie or chapter from Magis or Caracol carries facts`() {
        assertTrue(PlayerTrivia.wantsFacts("magis:C42::e3", SourceKind.MAGIS))
        assertTrue(PlayerTrivia.wantsFacts("ditu:99::e1", SourceKind.DITU))
    }

    @Test
    fun `a live stream, adults content, or another source carries no facts`() {
        assertFalse(PlayerTrivia.wantsFacts("ditu:vivo:canal1", SourceKind.DITU))
        assertFalse(PlayerTrivia.wantsFacts("magis:efimero:C42", SourceKind.MAGIS))
        assertFalse(PlayerTrivia.wantsFacts("live:1", SourceKind.LIVE))
        assertFalse(PlayerTrivia.wantsFacts("algo", SourceKind.UNKNOWN))
    }
}
