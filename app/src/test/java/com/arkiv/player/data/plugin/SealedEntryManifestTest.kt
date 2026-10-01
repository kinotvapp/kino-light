package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedEntryManifestTest {
    private fun v5(extra: JSONObject.() -> Unit) = JSONObject()
        .put("id", "demo").put("name", "Demo").put("version", "1.0.0").put("apiVersion", 5)
        .put("hosts", JSONArray(listOf("example.com"))).put("capabilities", JSONArray(listOf("search", "resolve"))).apply(extra).toString()

    private fun invalid(text: String) = ManifestParser.parse(text) as ManifestResult.Invalid

    @Test fun `apiVersion 5 accepts a sealedEntry kjs path`() {
        val m = (ManifestParser.parse(v5 { put("sealedEntry", "dist/plugin.kjs") }) as ManifestResult.Valid).manifest
        assertTrue(m.entrySealed)
        assertEquals("dist/plugin.kjs", m.entry)
        assertEquals(5, m.apiVersion)
    }

    @Test fun `entry and sealedEntry together are refused`() {
        val r = invalid(v5 { put("sealedEntry", "plugin.kjs"); put("entry", "plugin.js") })
        assertEquals("entry", r.field)
        assertEquals(ManifestParser.BOTH_ENTRIES, r.message)
    }

    @Test fun `sealedEntry must be a safe relative kjs path`() {
        for (bad in listOf<Any>("plugin.js", "../plugin.kjs", "/plugin.kjs", "a\\b.kjs", "", 5, JSONObject.NULL)) {
            val r = invalid(v5 { put("sealedEntry", bad) })
            assertEquals(bad.toString(), "sealedEntry", r.field)
            assertEquals(ManifestParser.SEALED_ENTRY_PATH, r.message)
        }
    }

    @Test fun `apiVersion 5 without any entry gets the entry message`() {
        assertEquals("entry", invalid(v5 { }).field)
    }

    @Test fun `older Kino's refusal of apiVersion 5 is the clear update message`() {
        // What Kino 0.9.45 and older answer (SUPPORTED_API was 4): the same rule, one version up.
        val r = invalid(v5 { put("sealedEntry", "plugin.kjs"); put("apiVersion", ManifestParser.SUPPORTED_API + 1) })
        assertEquals("apiVersion", r.field)
        assertEquals("Este plugin necesita una versión más nueva de Kino", r.message)
    }
}
