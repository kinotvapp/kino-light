package com.arkiv.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's tabs: "En vivo" follows the live module (any provider), "Caracol" the device's country. */
class VisibleTabsTest {

    @Test fun `en vivo shows only while the live module has a provider`() {
        assertTrue("live" in visibleTabRoutes(isColombia = true, liveModule = true))
        assertFalse("live" in visibleTabRoutes(isColombia = true, liveModule = false))
        assertFalse("live" in visibleTabRoutes(isColombia = false, liveModule = false))
    }

    @Test fun `caracol does not depend on the live module`() {
        assertTrue("caracol" in visibleTabRoutes(isColombia = true, liveModule = false))
        assertFalse("caracol" in visibleTabRoutes(isColombia = false, liveModule = true))
    }

    @Test fun `the rest of the tabs never move`() {
        assertEquals(
            listOf("home", "categorias_home", "library", "downloads", "plugins", "settings"),
            visibleTabRoutes(isColombia = false, liveModule = false),
        )
        assertEquals(
            listOf("home", "categorias_home", "library", "downloads", "live", "caracol", "plugins", "settings"),
            visibleTabRoutes(isColombia = true, liveModule = true),
        )
    }

    @Test fun `the En vivo tab follows the whole module, not only Xuper`() {
        assertTrue("live" in visibleTabRoutes(isColombia = false, liveModule = true))
        assertFalse("live" in visibleTabRoutes(isColombia = true, liveModule = false))
    }
}
