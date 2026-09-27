package com.arkiv.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's tabs: "En vivo" follows the Xuper plugin, "Caracol" the device's country. */
class VisibleTabsTest {

    @Test fun `en vivo shows only while xuper live is on`() {
        assertTrue("live" in visibleTabRoutes(isColombia = true, xuperLive = true))
        assertFalse("live" in visibleTabRoutes(isColombia = true, xuperLive = false))
        assertFalse("live" in visibleTabRoutes(isColombia = false, xuperLive = false))
    }

    @Test fun `caracol does not depend on the xuper plugin`() {
        assertTrue("caracol" in visibleTabRoutes(isColombia = true, xuperLive = false))
        assertFalse("caracol" in visibleTabRoutes(isColombia = false, xuperLive = true))
    }

    @Test fun `the rest of the tabs never move`() {
        assertEquals(
            listOf("home", "categorias_home", "library", "downloads", "settings"),
            visibleTabRoutes(isColombia = false, xuperLive = false),
        )
        assertEquals(
            listOf("home", "categorias_home", "library", "downloads", "live", "caracol", "settings"),
            visibleTabRoutes(isColombia = true, xuperLive = true),
        )
    }
}
