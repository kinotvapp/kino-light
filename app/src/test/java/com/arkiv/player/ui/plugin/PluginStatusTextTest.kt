package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstallException
import com.arkiv.player.data.plugin.PluginDamagedException
import com.arkiv.player.data.plugin.PluginScriptException
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.PluginTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class PluginStatusTextTest {
    @Test fun `every status has its Spanish text`() {
        assertEquals("Activo", pluginStatusText(PluginStatus.ACTIVE))
        assertEquals("Desactivado", pluginStatusText(PluginStatus.DISABLED))
        assertEquals("No responde — actívalo para volver a intentar", pluginStatusText(PluginStatus.UNRESPONSIVE))
        assertEquals("Actualización disponible — requiere tu aprobación", pluginStatusText(PluginStatus.UPDATE_PENDING))
        assertEquals("Archivos dañados, reinstálalo", pluginStatusText(PluginStatus.DAMAGED))
    }

    @Test fun `the installer's own Spanish message is shown as-is`() {
        assertEquals("No encontré kino-plugin.json en o/r", pluginErrorText(InstallException("No encontré kino-plugin.json en o/r")))
    }

    @Test fun `raw engine and network messages never reach the screen`() {
        // PluginTimeoutException's message embeds the capability name ("search no respondió…").
        assertEquals("El plugin no respondió a tiempo", pluginErrorText(PluginTimeoutException("search", 15_000)))
        assertEquals("Archivos dañados, reinstálalo", pluginErrorText(PluginDamagedException()))
        assertEquals("El plugin falló, vuelve a intentarlo", pluginErrorText(PluginScriptException("ReferenceError: foo is not defined")))
        assertEquals("No hay conexión, vuelve a intentarlo", pluginErrorText(IOException("Unable to resolve host")))
        assertEquals("No se pudo completar, vuelve a intentarlo", pluginErrorText(IllegalStateException("boom")))
    }
}
