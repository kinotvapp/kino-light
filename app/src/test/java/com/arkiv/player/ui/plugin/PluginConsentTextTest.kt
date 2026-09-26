package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.XuperPrivilege
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginConsentTextTest {
    @Test fun `the recognized Xuper repo gets the protected-connection line, not a host list`() {
        val line = pluginConsentHostLine(address = XuperPrivilege.SOURCE_REPO, hostsLabel = "example.org")
        assertEquals("Este plugin usa la conexión protegida de Xuper dentro de la app; no se conecta a internet por su cuenta.", line)
    }

    @Test fun `any other repo still gets the normal host list`() {
        val line = pluginConsentHostLine(address = "kinotvapp/kino-plugin-archive", hostsLabel = "archive.org, *.archive.org")
        assertEquals("Se conectará a: archive.org, *.archive.org", line)
    }
}
