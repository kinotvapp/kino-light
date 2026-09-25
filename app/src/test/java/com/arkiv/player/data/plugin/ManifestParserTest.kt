package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParserTest {
    private fun base() = JSONObject()
        .put("id", "archive-org").put("name", "Internet Archive").put("version", "1.0.0")
        .put("apiVersion", 1).put("entry", "plugin.js")
        .put("description", "  Películas de dominio público  ").put("author", "lordmacu")
        .put("hosts", JSONArray(listOf("archive.org", "*.archive.org")))
        .put("capabilities", JSONArray(listOf("search", "home", "episodes", "resolve")))
        .put("color", "#e0a030").put("icon", "icon.png")

    private fun invalidField(json: JSONObject): String =
        (ManifestParser.parse(json.toString()) as ManifestResult.Invalid).field

    @Test fun `a complete manifest is valid and normalized`() {
        val m = (ManifestParser.parse(base().toString()) as ManifestResult.Valid).manifest
        assertEquals("archive-org", m.id)
        assertEquals("Películas de dominio público", m.description)
        assertEquals("#E0A030", m.color)
        assertEquals(setOf("search", "home", "episodes", "resolve"), m.capabilities)
    }

    @Test fun `id rules`() {
        listOf("A", "a", "-abc", "has_underscore", "x".repeat(41), "magis", "ditu", "live", "local", "unknown", "plugin")
            .forEach { assertEquals(it, "id", invalidField(base().put("id", it))) }
    }

    @Test fun `name must be 1 to 40 chars`() {
        assertEquals("name", invalidField(base().put("name", "   ")))
        assertEquals("name", invalidField(base().put("name", "x".repeat(41))))
    }

    @Test fun `version must be semver`() = assertEquals("version", invalidField(base().put("version", "1.0")))

    @Test fun `apiVersion above the supported one says Kino must be updated`() {
        val r = ManifestParser.parse(base().put("apiVersion", 2).toString()) as ManifestResult.Invalid
        assertEquals("apiVersion", r.field)
        assertEquals("Este plugin necesita una versión más nueva de Kino", r.message)
        assertEquals("apiVersion", invalidField(base().put("apiVersion", "1")))
        assertEquals("apiVersion", invalidField(base().put("apiVersion", 0)))
    }

    @Test fun `entry must be a safe relative js path`() {
        listOf("../plugin.js", "/plugin.js", "plugin.mjs", "a/../b.js", "", "dir\\p.js")
            .forEach { assertEquals(it, "entry", invalidField(base().put("entry", it))) }
        assertTrue(ManifestParser.parse(base().put("entry", "dist/plugin.js").toString()) is ManifestResult.Valid)
    }

    @Test fun `hosts rules`() {
        assertEquals("hosts", invalidField(base().put("hosts", JSONArray())))
        assertEquals("hosts", invalidField(base().put("hosts", JSONArray(listOf("*")))))
        assertEquals("hosts", invalidField(base().put("hosts", JSONArray(listOf("localhost")))))
        assertEquals("hosts", invalidField(base().put("hosts", JSONArray((1..21).map { "h$it.example.com" }))))
        assertEquals("hosts", invalidField(base().apply { remove("hosts") }))
    }

    @Test fun `capabilities rules`() {
        assertEquals("capabilities", invalidField(base().put("capabilities", JSONArray(listOf("search")))))
        assertEquals("capabilities", invalidField(base().put("capabilities", JSONArray(listOf("resolve", "episodes")))))
        assertEquals("capabilities", invalidField(base().put("capabilities", JSONArray(listOf("resolve", "search", "download")))))
        assertTrue(ManifestParser.parse(base().put("capabilities", JSONArray(listOf("home", "resolve"))).toString()) is ManifestResult.Valid)
    }

    @Test fun `color and icon are optional but checked`() {
        assertEquals("color", invalidField(base().put("color", "orange")))
        assertEquals("icon", invalidField(base().put("icon", "../icon.png")))
        val m = (ManifestParser.parse(base().apply { remove("color"); remove("icon") }.toString()) as ManifestResult.Valid).manifest
        assertEquals(null, m.color)
        assertEquals(null, m.icon)
    }

    @Test fun `not json and oversized are refused`() {
        assertEquals("kino-plugin.json", (ManifestParser.parse("{nope") as ManifestResult.Invalid).field)
        assertEquals("kino-plugin.json", invalidField(base().put("description", "x".repeat(17_000))))
    }
}
