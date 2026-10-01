package com.arkiv.player.data.plugin.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginReachTest {
    @Test fun `a fetched manifest within the approval is covered`() {
        val reach = PluginReach(hosts = listOf("archive.org", "*.cdn.example.com"))
        assertTrue(reach.covers(preview(manifest(hosts = listOf("archive.org", "a.cdn.example.com")))))
    }

    @Test fun `one more host than approved is not`() {
        assertFalse(PluginReach(hosts = listOf("archive.org")).covers(preview(manifest(hosts = listOf("archive.org", "evil.example.com")))))
    }

    @Test fun `a new pattern is never covered by its own subdomain`() {
        assertFalse(PluginReach(hosts = listOf("a.example.com")).covers(preview(manifest(hosts = listOf("*.example.com")))))
    }

    @Test fun `capabilities that need consent must have been approved`() {
        val m = manifest(caps = setOf("search", "resolve", "download"))
        assertFalse(PluginReach(hosts = listOf("archive.org")).covers(preview(m)))
        assertTrue(PluginReach(hosts = listOf("archive.org"), capabilities = listOf("download")).covers(preview(m)))
    }

    @Test fun `every any flag and sealed secrets must have been approved`() {
        val any = manifest(streamHostsAny = true)
        assertFalse(PluginReach(hosts = listOf("archive.org")).covers(preview(any)))
        assertTrue(PluginReach(hosts = listOf("archive.org"), streamHostsAny = true).covers(preview(any)))
        val sealed = manifest(secrets = mapOf("k" to "kino-sealed:v1:x"))
        assertFalse(PluginReach(hosts = listOf("archive.org")).covers(preview(sealed)))
        assertTrue(PluginReach(hosts = listOf("archive.org"), sealedSecrets = true).covers(preview(sealed)))
    }

    @Test fun `fetchHosts any counts only for a nuvio conversion`() {
        val m = manifest(fetchHostsAny = true)
        assertTrue("a hand-written plugin gets nothing from it", PluginReach(hosts = listOf("archive.org")).covers(preview(m)))
        assertFalse(PluginReach(hosts = listOf("archive.org")).covers(preview(m, nuvio = true)))
        assertTrue(PluginReach(hosts = listOf("archive.org"), fetchHostsAny = true).covers(preview(m, nuvio = true)))
    }

    @Test fun `a peer's approval drops what is not a public host or a short name`() {
        val o = JSONObject()
            .put("hosts", JSONArray(listOf("archive.org", "192.168.1.10", "localhost", "evil.local", "8.8.8.8", 3)))
            .put("permissions", JSONArray(listOf("ok", "Not Valid!")))
            .put("anyVideoHost", true)
        val r = PluginReach.fromJson(o)
        assertEquals(listOf("archive.org", "8.8.8.8"), r.hosts)
        assertEquals(listOf("ok"), r.permissions)
        assertTrue(r.anyVideoHost)
        assertEquals(r, PluginReach.fromJson(r.toJson()))
    }

    @Test fun `union joins lists and flags`() {
        val u = PluginReach(hosts = listOf("a.com"), anyVideoHost = true).union(PluginReach(hosts = listOf("b.com", "a.com"), streamHostsAny = true))
        assertEquals(listOf("a.com", "b.com"), u.hosts)
        assertTrue(u.anyVideoHost && u.streamHostsAny)
    }
}
