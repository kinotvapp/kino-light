package com.arkiv.player.ui

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.XuperPrivilege
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's tabs: "En vivo" follows the live module (any provider); "Caracol" is hidden for now. */
class VisibleTabsTest {

    @Test fun `en vivo shows only while the live module has a provider`() {
        assertTrue("live" in visibleTabRoutes(isColombia = true, liveModule = true))
        assertFalse("live" in visibleTabRoutes(isColombia = true, liveModule = false))
        assertFalse("live" in visibleTabRoutes(isColombia = false, liveModule = false))
    }

    @Test fun `caracol is hidden for now, regardless of country -- a plugin will replace it`() {
        assertTrue(com.arkiv.player.data.ditu.CaracolVisibility.HIDDEN)
        assertFalse("caracol" in visibleTabRoutes(isColombia = true, liveModule = false))
        assertFalse("caracol" in visibleTabRoutes(isColombia = false, liveModule = true))
        assertFalse("caracol" in visibleTabRoutes(isColombia = true, liveModule = true, caracolVisible = false))
    }

    @Test fun `with the switch back on, caracol shows only in Colombia`() {
        assertTrue("caracol" in visibleTabRoutes(isColombia = true, liveModule = false, caracolVisible = true))
        assertFalse("caracol" in visibleTabRoutes(isColombia = false, liveModule = true, caracolVisible = true))
    }

    @Test fun `the rest of the tabs never move`() {
        assertEquals(
            listOf("home", "categorias_home", "library", "downloads", "plugins", "settings"),
            visibleTabRoutes(isColombia = false, liveModule = false),
        )
        assertEquals(
            listOf("home", "categorias_home", "library", "downloads", "live", "plugins", "settings"),
            visibleTabRoutes(isColombia = true, liveModule = true, caracolVisible = false),
        )
    }

    @Test fun `the En vivo tab follows the whole module, not only Xuper`() {
        assertTrue("live" in visibleTabRoutes(isColombia = false, liveModule = true))
        assertFalse("live" in visibleTabRoutes(isColombia = true, liveModule = false))
    }

    private fun plugin(id: String, address: String, enabled: Boolean = true) = InstalledPlugin(
        PluginManifest(id, id.uppercase(), "1.0.0", 3, "plugin.js", "", "", "", listOf("example.com"), setOf("home", "resolve"), null, null),
        InstalledRecord(address, "1.0.0", "sha", listOf("example.com"), 1L, enabled = enabled),
        null,
    )

    private val xuper = plugin("xuper", XuperPrivilege.SOURCE_REPO)

    @Test fun `Categorias is a Xuper catalog, so its tab is there only while Xuper is usable`() {
        assertTrue("categorias_home" in visibleTabRoutes(isColombia = false, liveModule = false, categoriesModule = true))
        assertFalse("categorias_home" in visibleTabRoutes(isColombia = false, liveModule = false, categoriesModule = false))
        assertFalse("categorias_home" in visibleTabRoutes(isColombia = true, liveModule = true, categoriesModule = false))
    }

    @Test fun `hiding Categorias moves no other tab`() {
        assertEquals(
            listOf("home", "library", "downloads", "live", "plugins", "settings"),
            visibleTabRoutes(isColombia = true, liveModule = true, categoriesModule = false, caracolVisible = false),
        )
    }

    @Test fun `the categories tab needs the usable Xuper plugin or a plugin row to browse`() {
        assertTrue(categoriesTabAvailable(listOf(xuper), hasGenreTiles = false))
        assertTrue("another plugin with browsable rows is enough", categoriesTabAvailable(listOf(plugin("tv1", "o/tv1")), hasGenreTiles = true))
        assertFalse(categoriesTabAvailable(emptyList(), hasGenreTiles = false))
        assertFalse("a plugin with nothing to browse", categoriesTabAvailable(listOf(plugin("tv1", "o/tv1")), hasGenreTiles = false))
        assertFalse("Xuper switched off", categoriesTabAvailable(listOf(plugin("xuper", XuperPrivilege.SOURCE_REPO, enabled = false)), hasGenreTiles = false))
    }
}
