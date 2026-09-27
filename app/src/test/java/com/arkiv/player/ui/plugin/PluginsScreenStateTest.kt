package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginsScreenStateTest {
    private val manifest = PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "lordmacu", "", listOf("example.com"), setOf("search", "resolve"), null, null)
    private val preview = InstallPreview(PluginAddress("o", "r"), manifest, "{}", isUpdate = false, newHosts = listOf("example.com"))
    private val installed = InstalledPlugin(manifest, InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L), iconFile = null)
    private val draft = PluginConfigDraft(pluginId = "demo", pluginName = "Demo", settings = emptyList(), values = emptyMap())

    @Test fun `the screen opens on Recomendados from Ajustes`() {
        assertEquals(PluginsTab.RECOMMENDED, initialPluginsTab(AddPluginMode.SETTINGS))
    }

    @Test fun `the screen opens on Recomendados in the first-launch picker`() {
        assertEquals(PluginsTab.RECOMMENDED, initialPluginsTab(AddPluginMode.ONBOARDING))
    }

    @Test fun `the Agregar modal shows when it was requested and nothing else is open`() {
        assertTrue(addModalVisible(requested = true, state = PluginsUiState()))
    }

    @Test fun `the Agregar modal stays closed when it was not requested`() {
        assertFalse(addModalVisible(requested = false, state = PluginsUiState()))
    }

    @Test fun `the Agregar modal closes as soon as the consent sheet opens`() {
        assertFalse(addModalVisible(requested = true, state = PluginsUiState(consent = preview)))
    }

    @Test fun `the Agregar modal never shows over Configurar`() {
        assertFalse(addModalVisible(requested = true, state = PluginsUiState(configuring = draft)))
    }

    @Test fun `the Agregar modal never shows over the uninstall confirmation`() {
        assertFalse(addModalVisible(requested = true, state = PluginsUiState(confirmUninstall = installed)))
    }

    @Test fun `a blank address cannot be submitted`() {
        assertFalse(canSubmitCustom("", busy = false))
        assertFalse(canSubmitCustom("  ", busy = false))
    }

    @Test fun `nothing is submitted while another action is running`() {
        assertFalse(canSubmitCustom("a/b", busy = true))
    }

    @Test fun `an address with text around it can be submitted`() {
        assertTrue(canSubmitCustom(" a/b ", busy = false))
    }

    @Test fun `the Instalados tab has no count when nothing is installed`() {
        assertEquals("Instalados", installedTabLabel(0))
    }

    @Test fun `the Instalados tab counts one plugin`() {
        assertEquals("Instalados (1)", installedTabLabel(1))
    }

    @Test fun `the Instalados tab counts several plugins`() {
        assertEquals("Instalados (3)", installedTabLabel(3))
    }

    @Test fun `the Instalados tab never shows a negative count`() {
        assertEquals("Instalados", installedTabLabel(-2))
    }
}
