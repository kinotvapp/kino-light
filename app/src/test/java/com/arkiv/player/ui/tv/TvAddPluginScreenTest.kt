package com.arkiv.player.ui.tv

import com.arkiv.player.ui.plugin.CatalogAction
import org.junit.Assert.assertEquals
import org.junit.Test

class TvAddPluginScreenTest {
    @Test fun `each catalog action reads as a sentence with the plugin name`() {
        assertEquals("Instalar Internet Archive", catalogRowLabel(CatalogAction.INSTALL, "Internet Archive"))
        assertEquals("Configurar Mi servidor", catalogRowLabel(CatalogAction.CONFIGURE, "Mi servidor"))
        assertEquals("Activar Xuper", catalogRowLabel(CatalogAction.ENABLE, "Xuper"))
    }

    @Test fun `an installed plugin reads as a status, not as an action`() {
        assertEquals("Xuper: instalado", catalogRowLabel(CatalogAction.INSTALLED, "Xuper"))
    }
}
