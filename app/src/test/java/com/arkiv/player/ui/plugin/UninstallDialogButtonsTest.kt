package com.arkiv.player.ui.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class UninstallDialogButtonsTest {
    // A tv-material Surface's onClick ignores a finger tap: the phone's "¿Desinstalar…?" must never draw the TV buttons.
    @Test fun `the phone gets touch buttons`() {
        assertEquals(UninstallDialogButtons.TOUCH, UninstallDialogButtons.forDevice(isTv = false))
    }

    @Test fun `the TV keeps its own focus-driven buttons`() {
        assertEquals(UninstallDialogButtons.TV, UninstallDialogButtons.forDevice(isTv = true))
    }
}
