package com.arkiv.player.ui.player

import com.arkiv.player.data.plugin.XuperPrivilege
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldOfferPluginConfigurar]: the "Falta configurar" dialog's Configurar button is meaningless
 * for the recognized Xuper install (no Xuper-specific settings screen exists, and `MagisSession`
 * already retries a dead session on its own) but is exactly right for every other plugin, which
 * really does have a settings screen at `plugin_config/{id}`.
 */
class PluginConfigurarRoutingTest {
    @Test fun `Configurar is suppressed for the recognized Xuper plugin`() {
        assertFalse(shouldOfferPluginConfigurar(address = XuperPrivilege.SOURCE_REPO))
    }

    @Test fun `Configurar is offered for any other plugin`() {
        assertTrue(shouldOfferPluginConfigurar(address = "kinotvapp/kino-plugin-archive"))
    }

    @Test fun `Configurar is offered when there is no installed record to check`() {
        assertTrue(shouldOfferPluginConfigurar(address = null))
    }
}
