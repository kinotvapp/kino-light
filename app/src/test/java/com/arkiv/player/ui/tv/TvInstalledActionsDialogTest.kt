package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvInstalledActionsDialogTest {
    private val everyShape = listOf(true, false).flatMap { switch ->
        listOf(true, false).flatMap { settings -> listOf(true, false).map { rejections -> Triple(switch, settings, rejections) } }
    }

    @Test fun `the actions keep their order, each only when it applies`() {
        assertEquals(
            listOf(TvInstalledAction.TOGGLE, TvInstalledAction.CONFIGURE, TvInstalledAction.UPDATE, TvInstalledAction.FORGET_REJECTIONS, TvInstalledAction.UNINSTALL, TvInstalledAction.CLOSE),
            tvInstalledActions(hasSwitch = true, hasSettings = true, hasRejections = true),
        )
        assertEquals(
            listOf(TvInstalledAction.UPDATE, TvInstalledAction.UNINSTALL, TvInstalledAction.CLOSE),
            tvInstalledActions(hasSwitch = false, hasSettings = false, hasRejections = false),
        )
    }

    // The menu used to open with focus on "Desactivar <plugin>": one more OK after the one that
    // opened it switched the plugin off. Like the consent sheets (focus on
    // "Cancelar"), the first focus is the option that changes nothing.
    @Test fun `the menu opens on Cerrar, never on an action that changes the plugin`() {
        for ((switch, settings, rejections) in everyShape) {
            val actions = tvInstalledActions(switch, settings, rejections)
            val first = tvInstalledActionsInitialFocus(actions)
            assertEquals("switch=$switch settings=$settings rejections=$rejections", TvInstalledAction.CLOSE, first)
            assertTrue(first in actions)
        }
    }
}
