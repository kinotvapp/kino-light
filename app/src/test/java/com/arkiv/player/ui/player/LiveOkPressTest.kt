package com.arkiv.player.ui.player

import com.arkiv.player.ui.player.LiveOkPress.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveOkPressTest {

    @Test
    fun `a quick press acts on release, never on the way down`() {
        val ok = LiveOkPress()
        assertEquals(Action.NONE, ok.onDown(repeatCount = 0, heldMs = 0))
        assertEquals(Action.SHORT, ok.onUp(heldMs = 120))
    }

    @Test
    fun `holding past the threshold toggles once, and the release does nothing`() {
        val ok = LiveOkPress()
        ok.onDown(repeatCount = 0, heldMs = 0)
        assertEquals(Action.NONE, ok.onDown(repeatCount = 1, heldMs = 500))
        assertEquals(Action.LONG, ok.onDown(repeatCount = 3, heldMs = 610))
        assertEquals(Action.NONE, ok.onDown(repeatCount = 4, heldMs = 660))
        assertEquals(Action.NONE, ok.onUp(heldMs = 900))
    }

    @Test
    fun `a remote that sends no key repeat still gets the long press on release`() {
        val ok = LiveOkPress()
        ok.onDown(repeatCount = 0, heldMs = 0)
        assertEquals(Action.LONG, ok.onUp(heldMs = 800))
    }

    @Test
    fun `a release with no press seen here is ignored`() {
        // The OK that opened this channel from the guide or the drawer ends on this view.
        val ok = LiveOkPress()
        assertEquals(Action.NONE, ok.onUp(heldMs = 50))
    }

    @Test
    fun `repeats with no press seen here never toggle`() {
        val ok = LiveOkPress()
        assertEquals(Action.NONE, ok.onDown(repeatCount = 5, heldMs = 900))
        assertEquals(Action.NONE, ok.onUp(heldMs = 950))
    }

    @Test
    fun `each press starts fresh`() {
        val ok = LiveOkPress()
        ok.onDown(repeatCount = 0, heldMs = 0)
        ok.onDown(repeatCount = 5, heldMs = 700)
        ok.onUp(heldMs = 800)
        ok.onDown(repeatCount = 0, heldMs = 0)
        assertEquals(Action.SHORT, ok.onUp(heldMs = 100))
    }
}
