package com.arkiv.player.ui.home

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEmptyStateTest {
    private fun plugin(enabled: Boolean) = InstalledPlugin(
        PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve"), null, null),
        InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L, enabled = enabled),
        iconFile = null,
    )

    @Test fun `empty only with nothing usable, no plugin rows and no live module`() {
        assertTrue(homeShowsEmptyState(emptyList(), pluginRowCount = 0, liveAvailable = false))
        assertTrue(homeShowsEmptyState(listOf(plugin(enabled = false)), pluginRowCount = 0, liveAvailable = false))
        assertFalse(homeShowsEmptyState(listOf(plugin(enabled = true)), pluginRowCount = 0, liveAvailable = false))
        assertFalse(homeShowsEmptyState(emptyList(), pluginRowCount = 1, liveAvailable = false))
        assertFalse(homeShowsEmptyState(emptyList(), pluginRowCount = 0, liveAvailable = true))
    }

    @Test fun `the copy is the spec's`() {
        assertEquals("Aún no tienes fuentes de contenido", EMPTY_HOME_TITLE)
        assertEquals("Agrega un plugin para ver películas, series o canales en vivo.", EMPTY_HOME_LINE)
        assertEquals("Agregar plugin", EMPTY_HOME_ACTION)
    }

    @Test fun `with plugins installed but none usable the copy says they are not working`() {
        assertEquals(HomeEmptyCopy(EMPTY_HOME_TITLE, EMPTY_HOME_LINE, EMPTY_HOME_ACTION), homeEmptyCopy(emptyList(), isTv = false))
        val broken = homeEmptyCopy(listOf(plugin(enabled = false)), isTv = false)
        assertEquals("Tus fuentes no están funcionando", broken.title)
        assertEquals("Revisa tus plugins en Menú ▸ Plugins o agrega otro.", broken.line)
        assertEquals("Agregar plugin", broken.action)
        // The TV keeps Plugins as a tab of Ajustes.
        assertEquals("Revisa tus plugins en Ajustes ▸ Plugins o agrega otro.", homeEmptyCopy(listOf(plugin(enabled = false)), isTv = true).line)
    }

    @Test fun `on the TV the empty state's button is the default landing`() {
        assertEquals(TvHomeLanding.ADD_SOURCES, tvHomeDefaultLanding(homeEmpty = true, hasContinueCard = false))
        assertEquals(TvHomeLanding.TOP_BAR, tvHomeDefaultLanding(homeEmpty = false, hasContinueCard = false))
        assertEquals(TvHomeLanding.FIRST_CARD, tvHomeDefaultLanding(homeEmpty = false, hasContinueCard = true))
    }

    @Test fun `an empty Home lands on its button even with orphan continue-watching cards`() {
        assertEquals(TvHomeLanding.ADD_SOURCES, tvHomeDefaultLanding(homeEmpty = true, hasContinueCard = true))
    }

    @Test fun `the Agregar plugin landing counts only once the button really holds focus`() {
        assertFalse(tvHomeLandingHeld(TvHomeLanding.ADD_SOURCES, requestSucceeded = true, addSourcesFocused = false))
        assertTrue(tvHomeLandingHeld(TvHomeLanding.ADD_SOURCES, requestSucceeded = true, addSourcesFocused = true))
        assertTrue(tvHomeLandingHeld(TvHomeLanding.TOP_BAR, requestSucceeded = true, addSourcesFocused = false))
        assertFalse(tvHomeLandingHeld(TvHomeLanding.FIRST_CARD, requestSucceeded = false, addSourcesFocused = false))
    }

    @Test fun `focus goes back to the top bar only when the button vanished with it`() {
        assertTrue(emptyStateNeedsRefocus(wasEmpty = true, isEmpty = false, screenHasFocus = false))
        assertFalse(emptyStateNeedsRefocus(wasEmpty = true, isEmpty = false, screenHasFocus = true))
        assertFalse(emptyStateNeedsRefocus(wasEmpty = false, isEmpty = true, screenHasFocus = false))
        assertFalse(emptyStateNeedsRefocus(wasEmpty = true, isEmpty = true, screenHasFocus = false))
    }

    // --- 0.9.45, ERRORES-AKR: Xuper's catalog came back empty ---

    private fun xuper(enabled: Boolean = true, address: String = com.arkiv.player.data.plugin.XuperPrivilege.SOURCE_REPO) = InstalledPlugin(
        PluginManifest("xuper", "Xuper", "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve", "home"), null, null),
        InstalledRecord(address, "1.0.0", "x", listOf("example.com"), 0L, enabled = enabled),
        iconFile = null,
    )

    private fun row(pluginId: String) = com.arkiv.player.data.plugin.PluginHomeRow(pluginId, "P", 0L, "r1", "Row", emptyList())

    @Test fun `the Xuper notice shows only once the pass settled with no Xuper row at all`() {
        assertTrue(xuperHomeFailed(listOf(xuper()), emptyList(), settled = true))
        assertTrue("another plugin's rows don't count", xuperHomeFailed(listOf(xuper(), plugin(true)), listOf(row("demo")), settled = true))
        assertFalse("still loading", xuperHomeFailed(listOf(xuper()), emptyList(), settled = false))
        assertFalse(xuperHomeFailed(listOf(xuper()), listOf(row("xuper")), settled = true))
    }

    @Test fun `no Xuper notice without a usable recognized Xuper`() {
        assertFalse(xuperHomeFailed(emptyList(), emptyList(), settled = true))
        assertFalse(xuperHomeFailed(listOf(xuper(enabled = false)), emptyList(), settled = true))
        assertFalse("a copy from another repo is not Xuper", xuperHomeFailed(listOf(xuper(address = "someone/xuper")), emptyList(), settled = true))
        assertFalse("a plugin with no rows is not a failure", xuperHomeFailed(listOf(plugin(true)), emptyList(), settled = true))
    }

    @Test fun `another plugin's failed home is listed with its sentence, Xuper's is left to its own notice`() {
        val failed = mapOf("demo" to "Demo: el sitio respondió con error 403", "xuper" to "Xuper: x", "gone" to "Gone: y")
        assertEquals(listOf("Demo: el sitio respondió con error 403"), pluginHomeFailureLines(listOf(plugin(true), xuper()), failed))
        assertEquals("a disabled plugin has no notice", emptyList<String>(), pluginHomeFailureLines(listOf(plugin(false)), failed))
        assertEquals(emptyList<String>(), pluginHomeFailureLines(listOf(plugin(true)), emptyMap()))
    }

    @Test fun `the Xuper notice copy`() {
        assertEquals("No pudimos cargar el catálogo de Xuper, toca para reintentar", XUPER_HOME_FAILED_PHONE)
        assertEquals("Reintentar", XUPER_HOME_RETRY)
    }
}
