package com.arkiv.player.ui.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddPluginModeTest {
    @Test fun `Back closes the window opened from Ajustes`() {
        var closed = 0
        handleAddPluginBack(AddPluginMode.SETTINGS) { closed++ }
        assertEquals(1, closed)
    }

    @Test fun `Back is swallowed in the mandatory first-launch picker`() {
        var closed = 0
        handleAddPluginBack(AddPluginMode.ONBOARDING) { closed++ }
        assertEquals(0, closed)
    }

    @Test fun `only the Ajustes window has a way out to draw`() {
        assertTrue(AddPluginMode.SETTINGS.canClose)
        assertFalse(AddPluginMode.ONBOARDING.canClose)
    }
}
