package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.PluginSetting
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.SettingType
import com.arkiv.player.data.plugin.catalog.CatalogArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What an installed plugin's card shows (the Instalados tab, phone and TV): the name/version line, the tile,
 * the status, the switch's position and whether it can be moved, and which action would fix a status that
 * isn't just "working".
 */
class InstalledCardStyleTest {
    private val settingsManifest = PluginManifest(
        "demo", "Internet Archive", "1.1.0", 1, "plugin.js", "", "lordmacu", "",
        listOf("example.com"), setOf("search"), null, null,
        settings = listOf(PluginSetting("apiKey", "API key", SettingType.TEXT, required = true)),
    )
    private val noSettingsManifest = settingsManifest.copy(settings = emptyList())
    private val record = InstalledRecord("o/r", "1.1.0", "x", listOf("example.com"), 0L)

    private fun plugin(
        record: InstalledRecord = this.record,
        manifest: PluginManifest = this.noSettingsManifest,
        missingSettings: List<String> = emptyList(),
    ) = InstalledPlugin(manifest, record, iconFile = null, missingSettings = missingSettings)

    @Test fun `the name and version share one line`() {
        assertEquals("Internet Archive · 1.1.0", installedCardModel(plugin(), null).nameLine)
        assertEquals("Internet Archive", installedCardModel(plugin(), null).name)
    }

    @Test fun `the tile reuses the shared colour and icon rules`() {
        val art = CatalogArt("#112233", File("art.png"))
        val model = installedCardModel(plugin(), art)
        assertEquals(tileColor(art), model.tileColorArgb)
        assertEquals(art.iconFile, model.iconFile)
    }

    @Test fun `an active plugin reads Activo, is not a problem, and needs no fix`() {
        val model = installedCardModel(plugin(), null)
        assertEquals("Activo", model.statusLabel)
        assertFalse(model.statusIsProblem)
        assertTrue(model.switchChecked)
        assertTrue(model.switchEnabled)
        assertNull(model.fixingAction)
    }

    @Test fun `a disabled plugin reads Desactivado, is a problem, and Activar fixes it`() {
        val model = installedCardModel(plugin(record.copy(enabled = false)), null)
        assertEquals("Desactivado", model.statusLabel)
        assertTrue(model.statusIsProblem)
        assertFalse(model.switchChecked)
        assertTrue(model.switchEnabled)
        assertEquals("Activar", model.fixingAction)
    }

    @Test fun `an unresponsive plugin reads No responde and its switch stays enabled`() {
        val model = installedCardModel(plugin(record.copy(unresponsive = true)), null)
        assertEquals("No responde", model.statusLabel)
        assertTrue(model.statusIsProblem)
        assertFalse(model.switchChecked)
        assertTrue(model.switchEnabled)
        assertEquals("Activar", model.fixingAction)
    }

    @Test fun `a damaged plugin reads Dañado and its switch cannot be toggled`() {
        val model = installedCardModel(plugin(record.copy(damaged = true)), null)
        assertEquals("Dañado", model.statusLabel)
        assertTrue(model.statusIsProblem)
        assertFalse(model.switchEnabled)
        assertEquals("Reinstalar", model.fixingAction)
    }

    @Test fun `a plugin missing a required setting reads Falta configurar and Configurar fixes it`() {
        val model = installedCardModel(plugin(manifest = settingsManifest, missingSettings = listOf("apiKey")), null)
        assertEquals("Falta configurar", model.statusLabel)
        assertTrue(model.statusIsProblem)
        assertEquals("Configurar", model.fixingAction)
        assertTrue(model.hasSettings)
    }

    @Test fun `a pending update is flagged red but needs no fix of its own`() {
        val model = installedCardModel(plugin(record.copy(pendingVersion = "1.2.0")), null)
        assertTrue(model.statusIsProblem)
        assertNull(model.fixingAction)
    }

    @Test fun `Configurar only belongs to a plugin whose manifest declares settings`() {
        assertFalse(installedCardModel(plugin(manifest = noSettingsManifest), null).hasSettings)
        assertTrue(installedCardModel(plugin(manifest = settingsManifest), null).hasSettings)
    }

    @Test fun `no message anywhere reserves nothing`() {
        assertEquals(listOf(false, false, false, false), installedGridLinesWithMessage(4, messageIndex = null, columns = 2))
    }

    @Test fun `both cards of the message's line reserve it, the others don't`() {
        assertEquals(listOf(false, false, true, true, false), installedGridLinesWithMessage(5, messageIndex = 2, columns = 2))
    }

    @Test fun `a message on the last card of an odd count reaches only its own line`() {
        assertEquals(listOf(false, false, false, false, true), installedGridLinesWithMessage(5, messageIndex = 4, columns = 2))
    }

    @Test fun `three columns group the same way`() {
        assertEquals(listOf(true, true, true, false, false), installedGridLinesWithMessage(5, messageIndex = 1, columns = 3))
    }
}
