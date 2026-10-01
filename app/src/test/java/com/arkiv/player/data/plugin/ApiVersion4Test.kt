package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * apiVersion 4 was claimed by two lines of work at once (streamHosts "any" and the `list` setting on
 * main, sealed `secrets` on the stream-host branch); the merge made it ONE version carrying all three.
 * This pins that no feature was lost, and that the installed record keeps every flag of both sides.
 */
class ApiVersion4Test {
    private fun manifest(api: Int) = JSONObject()
        .put("id", "demo").put("name", "Demo").put("version", "1.0.0").put("apiVersion", api)
        .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
        .put("capabilities", JSONArray(listOf("search", "resolve")))
        .put("streamHosts", "any")
        .put("secrets", JSONObject().put("apiKey", TestSealing.seal("v", "owner/repo", "apiKey")))
        .put(
            "settings",
            JSONArray().put(
                JSONObject().put("key", "sources").put("label", "Direcciones").put("type", "list")
                    .put("fields", JSONArray().put(JSONObject().put("key", "url").put("label", "Dirección").put("type", "url"))),
            ),
        )

    @Test fun `one version, every feature on its own gate`() {
        assertEquals(5, ManifestParser.SUPPORTED_API) // apiVersion 5 (sealed code) moved only the ceiling: every v4 gate stays at 4
        assertEquals(4, ManifestParser.STREAM_HOSTS_API_VERSION)
        assertEquals(4, ManifestParser.SECRETS_API_VERSION)
        assertEquals(4, PluginSettings.LIST_API_VERSION)
    }

    @Test fun `an apiVersion 4 manifest carries streamHosts, a list setting and secrets together`() {
        val m = (ManifestParser.parse(manifest(4).toString()) as ManifestResult.Valid).manifest
        assertTrue(m.streamHostsAny)
        assertEquals(setOf("apiKey"), m.secrets.keys)
        assertEquals(SettingType.LIST, m.settings.single().type)
    }

    @Test fun `below apiVersion 4 the two fields are ignored and the list setting is refused`() {
        val r = ManifestParser.parse(manifest(3).toString()) as ManifestResult.Invalid
        assertEquals("settings", r.field)
        val noList = manifest(3).apply { remove("settings") }
        val m = (ManifestParser.parse(noList.toString()) as ManifestResult.Valid).manifest
        assertFalse(m.streamHostsAny)
        assertTrue(m.secrets.isEmpty())
    }

    @Test fun `the installed record keeps both sides' flags through a JSON round trip`() {
        val r = InstalledRecord(
            "o/r", "1.0.0", "abc", listOf("example.com"), 1L,
            streamHostsAny = true, pendingStreamHostsAny = true,
            sealedSecrets = true, pendingSealedSecrets = true,
            anyVideoHost = true,
        )
        assertEquals(r, InstalledRecord.fromJson(r.toJson()))
        // A record written before the merge (either side) reads the missing flags as off.
        val older = JSONObject(r.toJson()).apply {
            remove("streamHostsAny"); remove("pendingStreamHostsAny"); remove("sealedSecrets"); remove("pendingSealedSecrets"); remove("anyVideoHost")
        }
        val back = InstalledRecord.fromJson(older.toString())!!
        assertFalse(back.streamHostsAny || back.pendingStreamHostsAny || back.sealedSecrets || back.pendingSealedSecrets || back.anyVideoHost)
        assertFalse(back.videoFromAnyHost)
    }
}
