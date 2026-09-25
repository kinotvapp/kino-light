package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSetupGateTest {
    @Test fun `a plugin that needs setup is never called`() {
        val calls = mutableListOf<String>()
        val gate = SetupGatedCaller({ it == "jf" }, PluginCaller { id, fn, _, _ -> calls += "$id.$fn"; "[]" })
        val e = assertThrows(PluginErrorException::class.java) { runBlocking { gate.call("jf", "search", "{}", 1_000) } }
        assertEquals(PluginErrors.AUTH_REQUIRED, e.code)
        assertEquals("[]", runBlocking { gate.call("ok", "search", "{}", 1_000) })
        assertEquals(listOf("ok.search"), calls)
    }

    @Test fun `a stream is resolved again only past its expiry, once`() {
        val e = PluginStreamExpiry(resolvedAtMs = 1_000, expiresInSeconds = 60)
        assertFalse(e.shouldResolveAgain(60_999))
        assertTrue(e.shouldResolveAgain(61_000))
        assertFalse(e.copy(retried = true).shouldResolveAgain(1_000_000))
        assertFalse(PluginStreamExpiry(1_000, 0).shouldResolveAgain(1_000_000))
    }

    @Test fun `each freshly re-resolved stream gets its own one retry, not just the title's first one`() {
        // Review round 1, finding 2: PlayerViewModel.loadPlugin used to carry `retried = afterExpiry`
        // into the NEW expiry it records after a retry's own resolve, so a title's second expiry
        // hard-failed even though this age gate alone already stops a tight retry loop. The fix
        // records every freshly-resolved stream -- including a retry's own republish -- with
        // `retried = false` (the default), never `retried = afterExpiry`.
        val first = PluginStreamExpiry(resolvedAtMs = 0, expiresInSeconds = 60)
        assertTrue(first.shouldResolveAgain(60_000))

        // What loadPlugin recorded for the RETRY's own republish, before the fix: `afterExpiry` is
        // true on that call, so `retried = afterExpiry` started the renewed stream already "used
        // up" -- it could never retry again, no matter how long the movie played after that.
        val beforeTheFix = PluginStreamExpiry(resolvedAtMs = 60_000, expiresInSeconds = 60, retried = true)
        assertFalse("the bug: a renewed stream started pre-retried and could never retry again", beforeTheFix.shouldResolveAgain(120_000))

        // What loadPlugin now records instead (retried defaults to false): the renewed stream gets
        // its own single retry when ITS OWN age gate is met -- the fix.
        val afterTheFix = PluginStreamExpiry(resolvedAtMs = 60_000, expiresInSeconds = 60)
        assertTrue("the fix: a long movie's SECOND expiry retries too, not just the first", afterTheFix.shouldResolveAgain(120_000))
        assertFalse("but still only once for THAT stream", afterTheFix.copy(retried = true).shouldResolveAgain(10_000_000))
    }

    @Test fun `consent lines disclose passwords, typed servers and each permission`() {
        val m = PluginManifest("jf", "Jellyfin", "1.0.0", 1, "plugin.js", "", "", "", listOf("jellyfin.org"), setOf("search", "resolve"), null, null,
            permissions = listOf("local-network"),
            settings = listOf(PluginSetting("server", "Servidor", SettingType.URL, true), PluginSetting("password", "Clave", SettingType.PASSWORD, true)))
        val lines = PluginConsent.extraLines(InstallPreview(PluginAddress("o", "r"), m, "{}", isUpdate = true, newHosts = emptyList(), newPermissions = listOf("local-network")))
        assertEquals(
            listOf(
                ConsentLine("Permiso: local-network", warning = true, isNew = true),
                ConsentLine("Este plugin usa tu usuario y contraseña"),
                ConsentLine("Se conectará a los servidores que escribas en su configuración"),
            ),
            lines,
        )
        val plain = m.copy(permissions = emptyList(), settings = emptyList())
        assertEquals(emptyList<ConsentLine>(), PluginConsent.extraLines(InstallPreview(PluginAddress("o", "r"), plain, "{}", false, emptyList())))
    }
}
