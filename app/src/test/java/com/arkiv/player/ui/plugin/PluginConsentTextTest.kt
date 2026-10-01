package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.XuperPrivilege
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginConsentTextTest {
    @Test fun `an installed plugin says it may play video from any server only when the person granted it`() {
        val record = com.arkiv.player.data.plugin.InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L)
        assertEquals(null, installedAnyVideoHostLine(record))
        assertEquals("Puede reproducir video desde cualquier servidor", installedAnyVideoHostLine(record.copy(anyVideoHost = true)))
    }

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

    @Test fun `both the new and the legacy Xuper address get the protected line`() {
        for (address in listOf("xuper-plugin/kino-plugin-xuper", "kinotvapp/kino-plugin-xuper")) {
            assertEquals(address, "Este plugin usa la conexión protegida de Xuper dentro de la app; no se conecta a internet por su cuenta.", pluginConsentProtectedLine(address))
        }
        for (address in listOf("xuper-plugin/kino-plugin-xuper@dev", "xuper-plugin/kino-plugin-xuper/sub", "xuper-plugin/other")) {
            assertNull(address, pluginConsentProtectedLine(address))
        }
    }

    private fun hosts(n: Int) = (1..n).map { "h$it.example" }

    @Test fun `no hosts means nothing to fold and no toggle`() {
        val s = pluginConsentHostSummary(emptyList(), emptyList(), expanded = false)
        assertEquals(ConsentHostSummary(emptyList(), 0, 0, collapsible = false), s)
        assertNull(pluginConsentHiddenHostsLine(s))
    }

    @Test fun `one and three hosts are all shown with no toggle`() {
        for (n in listOf(1, 3)) {
            val s = pluginConsentHostSummary(hosts(n), emptyList(), expanded = false)
            assertEquals(hosts(n), s.visible)
            assertEquals(false, s.collapsible)
            assertNull(pluginConsentHiddenHostsLine(s))
            assertEquals("Se va a conectar con:", pluginConsentHostsHeader(n, s.collapsible))
        }
    }

    @Test fun `four hosts fold to three plus one more, and expand to all four`() {
        val folded = pluginConsentHostSummary(hosts(4), emptyList(), expanded = false)
        assertEquals(hosts(3), folded.visible)
        assertEquals(true, folded.collapsible)
        assertEquals("y 1 más", pluginConsentHiddenHostsLine(folded))
        assertEquals("Se va a conectar con 4 servidores:", pluginConsentHostsHeader(4, folded.collapsible))
        val open = pluginConsentHostSummary(hosts(4), emptyList(), expanded = true)
        assertEquals(hosts(4), open.visible)
        assertEquals(true, open.collapsible)
        assertNull(pluginConsentHiddenHostsLine(open))
    }

    @Test fun `twenty hosts fold to three plus seventeen more`() {
        val s = pluginConsentHostSummary(hosts(20), emptyList(), expanded = false)
        assertEquals(hosts(3), s.visible)
        assertEquals("y 17 más", pluginConsentHiddenHostsLine(s))
        assertEquals(20, pluginConsentHostSummary(hosts(20), emptyList(), expanded = true).visible.size)
    }

    // No host-count limit since 0.9.45: a long list still folds to three, counted up front.
    @Test fun `a hundred and five hundred hosts fold to three, and an update counts its new ones`() {
        for (n in listOf(100, 500)) {
            val s = pluginConsentHostSummary(hosts(n), emptyList(), expanded = false)
            assertEquals(hosts(3), s.visible)
            assertEquals("y ${n - 3} más", pluginConsentHiddenHostsLine(s))
            assertEquals("Se va a conectar con $n servidores:", pluginConsentHostsHeader(n, s.collapsible))
            assertEquals(n, pluginConsentHostSummary(hosts(n), emptyList(), expanded = true).visible.size)
        }
        val all = hosts(500)
        val update = pluginConsentHostSummary(all, all.takeLast(250), expanded = false)
        assertEquals(all.takeLast(250).take(3), update.visible)
        assertEquals("y 497 más (247 nuevos)", pluginConsentHiddenHostsLine(update))
    }

    @Test fun `an update shows its new hosts first when folded and counts the new ones it hides`() {
        val all = hosts(10)
        val one = pluginConsentHostSummary(all, listOf("h9.example"), expanded = false)
        assertEquals(listOf("h9.example", "h1.example", "h2.example"), one.visible)
        assertEquals("y 7 más", pluginConsentHiddenHostsLine(one))

        val five = listOf("h4.example", "h6.example", "h7.example", "h8.example", "h10.example")
        val many = pluginConsentHostSummary(all, five, expanded = false)
        assertEquals(listOf("h4.example", "h6.example", "h7.example"), many.visible)
        assertEquals("y 7 más (2 nuevos)", pluginConsentHiddenHostsLine(many))

        val oneHiddenNew = pluginConsentHostSummary(all, five.take(4), expanded = false)
        assertEquals("y 7 más (1 nuevo)", pluginConsentHiddenHostsLine(oneHiddenNew))

        // Expanded keeps the same new-first order, so nothing jumps around when the list opens.
        assertEquals(five + all.filterNot { it in five }, pluginConsentHostSummary(all, five, expanded = true).visible)
    }
}
