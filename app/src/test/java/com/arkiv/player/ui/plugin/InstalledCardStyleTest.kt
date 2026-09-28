package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginColors
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
        iconFile: File? = null,
    ) = InstalledPlugin(manifest, record, iconFile = iconFile, missingSettings = missingSettings)

    @Test fun `the name and version share one line`() {
        assertEquals("Internet Archive · 1.1.0", installedCardModel(plugin(), null).nameLine)
        assertEquals("Internet Archive", installedCardModel(plugin(), null).name)
    }

    // The whole point of "Agregar": a plugin installed from a repo the catalog does not list still shows
    // the icon and colour its own manifest and files declare, not the neutral default's letter tile.
    @Test fun `the plugin's own icon and colour win over the catalog's`() {
        val ownIcon = File.createTempFile("own-icon", ".png").also { it.deleteOnExit() }
        val catalogIcon = File.createTempFile("catalog-icon", ".png").also { it.deleteOnExit() }
        val art = CatalogArt("#00FF00", catalogIcon)
        val model = installedCardModel(plugin(manifest = noSettingsManifest.copy(color = "#FF0000"), iconFile = ownIcon), art)
        assertEquals(PluginColors.parse("#FF0000"), model.tileColorArgb)
        assertEquals(ownIcon, model.iconFile)
    }

    @Test fun `the catalog fills in whichever the plugin's own manifest and files lack`() {
        val catalogIcon = File.createTempFile("catalog-icon", ".png").also { it.deleteOnExit() }
        val art = CatalogArt("#00FF00", catalogIcon)
        val model = installedCardModel(plugin(manifest = noSettingsManifest.copy(color = null), iconFile = null), art)
        assertEquals(PluginColors.parse("#00FF00"), model.tileColorArgb)
        assertEquals(catalogIcon, model.iconFile)
    }

    @Test fun `neither the plugin nor the catalog offers art, so the tile falls to the neutral default`() {
        val model = installedCardModel(plugin(manifest = noSettingsManifest.copy(color = null), iconFile = null), null)
        assertEquals(PluginColors.DEFAULT, model.tileColorArgb)
        assertNull(model.iconFile)
    }

    @Test fun `an active plugin reads Activo, is not a problem, and needs no fix`() {
        val model = installedCardModel(plugin(), null)
        assertEquals("Activo", model.statusLabel)
        assertFalse(model.statusIsProblem)
        assertTrue(model.switchChecked)
        assertTrue(model.switchEnabled)
    }

    @Test fun `a disabled plugin reads Desactivado, is a problem, and its switch still moves`() {
        val model = installedCardModel(plugin(record.copy(enabled = false)), null)
        assertEquals("Desactivado", model.statusLabel)
        assertTrue(model.statusIsProblem)
        assertFalse(model.switchChecked)
        assertTrue(model.switchEnabled)
    }

    @Test fun `an unresponsive plugin reads No responde and its switch stays enabled`() {
        val model = installedCardModel(plugin(record.copy(unresponsive = true)), null)
        assertEquals("No responde", model.statusLabel)
        assertTrue(model.statusIsProblem)
        assertFalse(model.switchChecked)
        assertTrue(model.switchEnabled)
    }

    @Test fun `a damaged plugin reads Dañado and its switch cannot be toggled`() {
        val model = installedCardModel(plugin(record.copy(damaged = true)), null)
        assertEquals("Dañado", model.statusLabel)
        assertTrue(model.statusIsProblem)
        assertFalse(model.switchEnabled)
    }

    @Test fun `a plugin missing a required setting reads Falta configurar`() {
        val model = installedCardModel(plugin(manifest = settingsManifest, missingSettings = listOf("apiKey")), null)
        assertEquals("Falta configurar", model.statusLabel)
        assertTrue(model.statusIsProblem)
        assertTrue(model.hasSettings)
    }

    @Test fun `a pending update is flagged red but needs no fix of its own`() {
        val model = installedCardModel(plugin(record.copy(pendingVersion = "1.2.0")), null)
        assertTrue(model.statusIsProblem)
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

    // Fixed, not "however many lines the actual text takes": a message that fits on one line must reserve the
    // very same height as one that wraps to two, or its line's neighbour (reserving blank space) ends up
    // shorter than the card that has a one-line message. Both the real message and the blank placeholder use
    // this as BOTH minLines and maxLines, so every card of a line -- the one with the message included -- is
    // pinned to the same height regardless of how long its own text happens to be.
    @Test fun `a card's message always reserves two lines`() {
        assertEquals(2, installedMessageLines())
    }

    // The hosts line is not optional (every installed plugin has one), so unlike the message it is not
    // reserved per grid line: every card always uses this many lines for it, whether one host or several.
    @Test fun `the hosts line always reserves two lines`() {
        assertEquals(2, installedHostsLines())
    }

    // A live plugin's "Lista recortada: ..." line (Ajustes > Plugins): every card of a line with one reserves it.
    @Test fun `a live notice is reserved by every card of its grid line`() {
        assertEquals(listOf(false, false, true, true, false), installedGridLinesReserving(listOf(false, false, false, true, false), columns = 2))
        assertEquals(listOf(false, false, false), installedGridLinesReserving(listOf(false, false, false), columns = 2))
        assertEquals(listOf(true, true, false), installedGridLinesReserving(listOf(true, true, false), columns = 2))
    }
}
