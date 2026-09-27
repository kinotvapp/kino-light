package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.XuperPrivilege
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // The install sheet (PluginConsentDialog) keys on InstallPreview.address.canonical: whatever
    // form the person typed, it must agree with the installed row and with XuperPrivilege.grants.
    /** A plugin whose only reach is the person's own server lists no host at all (never an empty header). */
    @Test fun `a plugin with no declared host says it reaches only the servers the person types`() {
        assertEquals("Se conectará solo a los servidores que escribas en su configuración", pluginConsentHostLine("kinotvapp/kino-plugin-own-server", ""))
    }

    @Test fun `the install sheet never lists a reserved invalid host`() {
        assertEquals(listOf("api.example.com"), pluginConsentHosts(listOf("tu-servidor.invalid", "api.example.com")))
        assertEquals(emptyList<String>(), pluginConsentHosts(listOf("tu-servidor.invalid")))
        assertEquals(emptyList<String>(), pluginConsentHosts(emptyList()))
    }

    @Test fun `the install sheet gets the protected line for every way of typing the Xuper repo`() {
        listOf(
            "kinotvapp/kino-plugin-xuper",
            "https://github.com/kinotvapp/kino-plugin-xuper",
            "  kinotvapp/kino-plugin-xuper/  ",
        ).forEach { typed ->
            val canonical = PluginAddress.parse(typed)!!.canonical
            assertEquals(typed, pluginConsentHostLine(canonical, "x"), pluginConsentProtectedLine(canonical))
        }
    }

    @Test fun `the install sheet keeps the host list for anything the gate would not grant`() {
        listOf(
            "kinotvapp/kino-plugin-xuper@dev",
            "kinotvapp/kino-plugin-xuper/sub",
            "someone-else/kino-plugin-xuper",
            "kinotvapp/kino-plugin-archive",
        ).forEach { typed ->
            assertNull(typed, pluginConsentProtectedLine(PluginAddress.parse(typed)!!.canonical))
        }
    }
}
