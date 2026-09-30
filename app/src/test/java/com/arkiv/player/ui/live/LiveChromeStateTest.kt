package com.arkiv.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChromeStateTest {
    @Test fun `asking for a reload bumps a tick the screen watches`() {
        val s = LiveChromeState()
        val before = s.reloadTick
        s.requestReload()
        s.requestReload()
        assertEquals(before + 2, s.reloadTick)
    }

    @Test fun `an action asked from the app bar is handed to the screen exactly once`() {
        val s = LiveChromeState()
        assertNull(s.consumeAction())
        s.request(LiveChromeAction.ADD_PLAYLIST)
        assertEquals(LiveChromeAction.ADD_PLAYLIST, s.consumeAction())
        assertNull("already taken", s.consumeAction())
    }

    @Test fun `the guide toggle flips only while the provider has a guide, and switches itself off when it loses it`() {
        val s = LiveChromeState()
        s.updateHasGuide(false)
        s.toggleGuide()
        assertFalse(s.guideMode)
        s.updateHasGuide(true)
        s.toggleGuide()
        assertTrue(s.guideMode)
        s.updateHasGuide(false)
        assertFalse(s.guideMode)
    }

    @Test fun `the saved form keeps the guide mode across a rotation`() {
        val s = LiveChromeState()
        s.updateHasGuide(true)
        s.toggleGuide()
        val restored = LiveChromeState.restore(LiveChromeState.save(s))
        assertTrue(restored.guideMode)
    }
}
