package com.arkiv.player.ui.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginMoreTargetTest {
    @Test fun `a target is rebuilt from its route arguments`() {
        assertEquals(PluginMoreTarget.Browse("p", "Películas", "films"), PluginMoreTarget.fromRoute("p", "Películas", "films", null, null))
        assertEquals(PluginMoreTarget.Search("p", "T", "{\"q\":\"x\"}", "2"), PluginMoreTarget.fromRoute("p", "T", null, "{\"q\":\"x\"}", "2"))
        assertNull(PluginMoreTarget.fromRoute("", "T", "films", null, null))
        assertNull(PluginMoreTarget.fromRoute("p", "T", null, "{}", null))
    }
}
