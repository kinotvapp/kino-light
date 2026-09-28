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
        assertEquals(HomeEmptyCopy(EMPTY_HOME_TITLE, EMPTY_HOME_LINE, EMPTY_HOME_ACTION), homeEmptyCopy(emptyList()))
        val broken = homeEmptyCopy(listOf(plugin(enabled = false)))
        assertEquals("Tus fuentes no están funcionando", broken.title)
        assertEquals("Revisa tus plugins en Ajustes ▸ Plugins o agrega otro.", broken.line)
        assertEquals("Agregar plugin", broken.action)
    }

    @Test fun `on the TV the empty state's button is the default landing`() {
        assertEquals(TvHomeLanding.ADD_SOURCES, tvHomeDefaultLanding(homeEmpty = true))
        assertEquals(TvHomeLanding.TOP_BAR, tvHomeDefaultLanding(homeEmpty = false))
    }

    @Test fun `focus goes back to the top bar only when the button vanished with it`() {
        assertTrue(emptyStateNeedsRefocus(wasEmpty = true, isEmpty = false, screenHasFocus = false))
        assertFalse(emptyStateNeedsRefocus(wasEmpty = true, isEmpty = false, screenHasFocus = true))
        assertFalse(emptyStateNeedsRefocus(wasEmpty = false, isEmpty = true, screenHasFocus = false))
        assertFalse(emptyStateNeedsRefocus(wasEmpty = true, isEmpty = true, screenHasFocus = false))
    }
}
