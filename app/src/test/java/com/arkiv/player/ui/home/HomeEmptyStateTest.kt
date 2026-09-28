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

    @Test fun `loading shows while a usable plugin is still answering and nothing else fills Home`() {
        val usable = listOf(plugin(enabled = true))
        assertTrue(homeShowsLoading(usable, pluginRowCount = 0, pluginRowsSettled = false, hasOtherContent = false))
        // The first plugin row arrived: the spinner leaves at once, even if other plugins still answer.
        assertFalse(homeShowsLoading(usable, pluginRowCount = 1, pluginRowsSettled = false, hasOtherContent = false))
        // Every plugin answered with nothing or failed: no endless spinner, Home falls back to today's behaviour.
        assertFalse(homeShowsLoading(usable, pluginRowCount = 0, pluginRowsSettled = true, hasOtherContent = false))
        // A live row, continue watching or the library already fill Home: no spinner over them.
        assertFalse(homeShowsLoading(usable, pluginRowCount = 0, pluginRowsSettled = false, hasOtherContent = true))
    }

    @Test fun `loading never shows without a usable plugin, so never together with the empty state`() {
        for (plugins in listOf(emptyList(), listOf(plugin(enabled = false)))) {
            assertFalse(homeShowsLoading(plugins, pluginRowCount = 0, pluginRowsSettled = false, hasOtherContent = false))
            assertTrue(homeShowsEmptyState(plugins, pluginRowCount = 0, liveAvailable = false))
        }
        val usable = listOf(plugin(enabled = true))
        assertTrue(homeShowsLoading(usable, 0, pluginRowsSettled = false, hasOtherContent = false))
        assertFalse(homeShowsEmptyState(usable, pluginRowCount = 0, liveAvailable = false))
    }

    @Test fun `the loading copy`() {
        assertEquals("Cargando tus fuentes…", HOME_LOADING_LINE)
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
}
