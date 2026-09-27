package com.arkiv.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoGoesHomeTest {
    @Test fun `on a section other than Inicio the logo takes the person home`() {
        assertTrue(logoGoesHome("settings"))
        assertTrue(logoGoesHome("downloads"))
        assertTrue(logoGoesHome("library"))
    }

    @Test fun `on Inicio the logo does nothing`() {
        assertFalse(logoGoesHome("home"))
    }

    @Test fun `with no route yet the logo does nothing`() {
        assertFalse(logoGoesHome(null))
    }
}
