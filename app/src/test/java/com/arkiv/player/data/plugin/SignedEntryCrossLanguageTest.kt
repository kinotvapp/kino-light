package com.arkiv.player.data.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-language proof: a signature made by the Node kit's `seal.mjs` (`signEntry`, what `--sign`
 * runs) parses through the manifest and verifies here with Kotlin's own [SignedEntry] and [Ed25519].
 * The fixture was produced once and committed as-is; nothing here regenerates it, so a real
 * regression on either side (not a matched pair of bugs) fails this test.
 */
class SignedEntryCrossLanguageTest {
    private val f = JSONObject(File("src/test/resources/plugin/signed-entry/fixture.json").readText())
    private val script = f.getString("script").toByteArray(Charsets.UTF_8)
    private val signature = (ManifestParser.parse(
        JSONObject().put("id", f.getString("id")).put("name", "Demo").put("version", f.getString("version")).put("apiVersion", 5)
            .put("entry", "plugin.js").put("hosts", org.json.JSONArray(listOf("example.com")))
            .put("capabilities", org.json.JSONArray(listOf("search", "resolve"))).put("signature", f.getJSONObject("signature")).toString(),
    ) as ManifestResult.Valid).manifest.signature!!

    @Test fun `seal-mjs's signature verifies for its binding, id and version, and names its author`() {
        assertTrue(SignedEntry.verify(signature, f.getString("binding"), f.getString("id"), f.getString("version"), script))
        assertEquals(f.getString("fingerprint"), signature.fingerprint)
    }

    @Test fun `the fixture refuses another script, binding, id or version`() {
        assertFalse(SignedEntry.verify(signature, f.getString("binding"), "demo", "1.2.3", script + 32))
        assertFalse(SignedEntry.verify(signature, "owner/repo", "demo", "1.2.3", script))
        assertFalse(SignedEntry.verify(signature, f.getString("binding"), "other", "1.2.3", script))
        assertFalse(SignedEntry.verify(signature, f.getString("binding"), "demo", "1.2.4", script))
    }
}
