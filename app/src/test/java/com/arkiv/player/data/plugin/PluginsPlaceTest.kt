package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the person finds their plugins: a drawer item on the phone, a tab of Ajustes on the TV. */
class PluginsPlaceTest {
    @Test fun `the phone points at the drawer item, the TV at the Ajustes tab`() {
        assertEquals("Plugins, en el menú", PluginsPlace.of(isTv = false))
        assertEquals("Ajustes ▸ Plugins", PluginsPlace.of(isTv = true))
    }

    @Test fun `typed errors and live notices name the place they are given`() {
        assertEquals("Configura Jellyfin en Plugins, en el menú", PluginErrors.userMessage("auth_required", "Jellyfin", PluginsPlace.of(isTv = false)))
        assertEquals("Configura Jellyfin en Ajustes ▸ Plugins", PluginErrors.userMessage("auth_required", "Jellyfin", PluginsPlace.of(isTv = true)))
    }
}
