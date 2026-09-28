package com.arkiv.player.ui.plugin

import com.arkiv.player.data.onboarding.Onboarding
import com.arkiv.player.data.onboarding.OnboardingKind
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePickerTest {
    private fun plugin(enabled: Boolean, unresponsive: Boolean = false) = InstalledPlugin(
        PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve"), null, null),
        InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L, enabled = enabled, unresponsive = unresponsive),
        iconFile = null,
    )

    @Test fun `the copy is the spec's`() {
        assertEquals("sources", SOURCE_PICKER_ROUTE)
        assertEquals("Elige tus fuentes", SOURCE_PICKER_TITLE)
        assertEquals("Instala las fuentes que quieras usar. Puedes cambiarlas cuando quieras en Plugins, en el menú.", sourcePickerLine(isTv = false))
        assertEquals("Instala las fuentes que quieras usar. Puedes cambiarlas cuando quieras en Ajustes ▸ Plugins.", sourcePickerLine(isTv = true))
        assertEquals("Listo", SOURCE_PICKER_DONE)
        assertEquals("Recomendados", RECOMMENDED_TITLE)
    }

    @Test fun `Listo needs at least one installed and enabled plugin, the same rule that reopens the picker`() {
        assertFalse(pickerCanFinish(emptyList()))
        assertFalse(pickerCanFinish(listOf(plugin(enabled = false))))
        assertTrue(pickerCanFinish(listOf(plugin(enabled = true))))
        assertTrue(pickerCanFinish(listOf(plugin(enabled = true, unresponsive = true))))
        for (plugins in listOf(emptyList(), listOf(plugin(enabled = false)), listOf(plugin(enabled = true)))) {
            assertEquals(pickerCanFinish(plugins), !Onboarding.opensPickerOnStart(OnboardingKind.NEW, plugins))
        }
    }

    @Test fun `the TV picker takes focus back only once placed, when nothing of it holds focus and no dialog is up`() {
        assertTrue(pickerNeedsRefocus(initialFocusPlaced = true, screenHasFocus = false, dialogOpen = false))
        assertFalse(pickerNeedsRefocus(initialFocusPlaced = false, screenHasFocus = false, dialogOpen = false))
        assertFalse(pickerNeedsRefocus(initialFocusPlaced = true, screenHasFocus = true, dialogOpen = false))
        assertFalse(pickerNeedsRefocus(initialFocusPlaced = true, screenHasFocus = false, dialogOpen = true))
    }

    @Test fun `Listo pops back to what was under the picker`() {
        var wentHome = false
        leaveSourcePicker(isShowing = { true }, popBack = { true }, goHome = { wentHome = true })
        assertFalse(wentHome)
    }

    @Test fun `Listo with nothing under the picker goes Home`() {
        var wentHome = false
        leaveSourcePicker(isShowing = { true }, popBack = { false }, goHome = { wentHome = true })
        assertTrue(wentHome)
    }

    @Test fun `a second Listo while the picker fades out neither pops Home nor navigates`() {
        var pops = 0
        var wentHome = false
        leaveSourcePicker(isShowing = { false }, popBack = { pops++; true }, goHome = { wentHome = true })
        assertEquals(0, pops)
        assertFalse(wentHome)
    }

    @Test fun `the start picker opens over any route but a deep-linked player`() {
        assertTrue(pickerMayOpenOver("home"))
        assertTrue(pickerMayOpenOver("settings"))
        assertTrue(pickerMayOpenOver("search?kind={kind}&tmdbId={tmdbId}&anilistId={anilistId}"))
        assertTrue(pickerMayOpenOver(SOURCE_PICKER_ROUTE))
        assertFalse(pickerMayOpenOver("player/{episodeId}"))
        assertFalse(pickerMayOpenOver(null))
    }

    @Test fun `the start cover hides everything but a deep-linked player until the picker is up or not needed`() {
        assertTrue(startCoverShows(StartGate.DECIDING, "home"))
        assertTrue(startCoverShows(StartGate.OPENING, "settings"))
        assertFalse(startCoverShows(StartGate.DECIDING, "player/{episodeId}"))
        assertFalse(startCoverShows(StartGate.OPENING, "player/{episodeId}"))
        assertFalse(startCoverShows(StartGate.DONE, "home"))
    }

    @Test fun `the start picker is opened only if there is still no source right before navigating`() {
        assertTrue(startPickerStillNeeded(emptyList()))
        assertTrue(startPickerStillNeeded(listOf(plugin(enabled = false))))
        assertFalse(startPickerStillNeeded(listOf(plugin(enabled = true))))
    }

    @Test fun `leaving the app from the picker sends the task to the back and never finishes the Activity`() {
        var moved = false
        var finished = false
        val activity = object : android.app.Activity() {
            override fun moveTaskToBack(nonRoot: Boolean): Boolean { moved = nonRoot; return true }
            override fun finish() { finished = true }
        }
        exitFromSourcePicker(activity)
        assertTrue(moved)
        assertFalse(finished)
    }

    private fun installed(id: String, address: String, enabled: Boolean = true, damaged: Boolean = false) = InstalledPlugin(
        PluginManifest(id, id.uppercase(), "1.0.0", 1, "plugin.js", "Lo de $id", "", "", listOf("example.com"), setOf("search", "resolve"), "#112233", null),
        InstalledRecord(address, "1.0.0", "x", listOf("example.com"), 0L, enabled = enabled, damaged = damaged),
        iconFile = null,
    )

    @Test fun `the picker lists the person's disabled or damaged plugins that no other card shows`() {
        val off = installed("off", "someone/custom", enabled = false)
        val broken = installed("broken", "someone/broken@dev", damaged = true)
        val fine = installed("fine", "someone/fine")
        val offInCatalog = installed("cat", "kinotvapp/kino-plugin-archive", enabled = false)
        val shown = listOf(CatalogRow(com.arkiv.player.data.plugin.catalog.CatalogEntry("cat", "kinotvapp/kino-plugin-archive", "Cat", ""), offInCatalog))
        val rows = pickerInstalledRows(listOf(off, broken, fine, offInCatalog), shown)
        assertEquals(listOf("off", "broken"), rows.map { it.entry.id })
        assertEquals(listOf("someone/custom", "someone/broken@dev"), rows.map { it.entry.repo })
        assertEquals(listOf("OFF", "BROKEN"), rows.map { it.entry.name })
        assertEquals(listOf(off, broken), rows.map { it.installed })
        assertEquals(listOf(CatalogAction.ENABLE, CatalogAction.INSTALL), rows.map { catalogActionOf(it) })
    }

    @Test fun `Back on the picker opened at start leaves the app and never reveals Home`() {
        var exited = false
        var pops = 0
        onSourcePickerBack(mandatory = true, exitApp = { exited = true }, popBack = { pops++ })
        assertTrue(exited)
        assertEquals(0, pops)
    }

    @Test fun `Back on the picker opened from the empty Home mid-session goes back to that Home`() {
        var exited = false
        var pops = 0
        onSourcePickerBack(mandatory = false, exitApp = { exited = true }, popBack = { pops++ })
        assertFalse(exited)
        assertEquals(1, pops)
    }
}
