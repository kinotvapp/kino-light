package com.arkiv.player.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's Ajustes: Plugins moved out to its own drawer item, so it is no longer one of the tabs. */
class SettingsTabsTest {
    @Test fun `the phone Ajustes has no Plugins tab`() {
        assertEquals(listOf("Subtítulos", "Cuenta", "App", "Conectar"), SettingsTab.entries.map { it.label })
    }
}
