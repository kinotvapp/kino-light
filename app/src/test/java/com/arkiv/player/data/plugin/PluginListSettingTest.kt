package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `list` setting (apiVersion 4): a growing list of entries, each with the fields the manifest names. */
class PluginListSettingTest {
    private fun field(key: String, label: String, type: String, required: Boolean = false) =
        JSONObject().put("key", key).put("label", label).put("type", type).apply { if (required) put("required", true) }

    private fun list(extra: JSONObject.() -> Unit = {}) = JSONObject()
        .put("key", "sources").put("label", "Direcciones").put("type", "list")
        .put("fields", JSONArray().put(field("url", "Dirección", "url", required = true)).put(field("category", "Categoría", "text")))
        .apply(extra)

    private fun manifest(api: Int = 4, setting: JSONObject = list()): String = JSONObject()
        .put("id", "demo").put("name", "Demo").put("version", "1.0.0").put("apiVersion", api)
        .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
        .put("capabilities", JSONArray(listOf("search", "resolve")))
        .put("settings", JSONArray().put(setting)).toString()

    private fun valid(json: String) = (ManifestParser.parse(json) as ManifestResult.Valid).manifest
    private fun invalid(json: String) = ManifestParser.parse(json) as ManifestResult.Invalid

    @Test fun `a list with its fields parses on apiVersion 4`() {
        val s = valid(manifest()).settings.single()
        assertEquals(SettingType.LIST, s.type)
        assertEquals(listOf("url", "category"), s.fields.map { it.key })
        assertEquals(listOf(SettingType.URL, SettingType.TEXT), s.fields.map { it.type })
        assertEquals(listOf(true, false), s.fields.map { it.required })
        assertEquals(PluginSettings.DEFAULT_LIST_ENTRIES, s.max)
    }

    @Test fun `max sets how many entries the person can add`() {
        assertEquals(30, valid(manifest(setting = list { put("max", 30) })).settings.single().max)
        for (bad in listOf(0, 51, -1)) assertEquals("settings", invalid(manifest(setting = list { put("max", bad) })).field)
    }

    @Test fun `a list needs apiVersion 4, older Kino would not know the type`() {
        for (api in 1..3) {
            val r = invalid(manifest(api = api))
            assertEquals("settings", r.field)
            assertTrue(r.message, r.message.contains("apiVersion 4"))
        }
    }

    @Test fun `apiVersion 6 is still newer than this Kino`() {
        assertEquals("apiVersion", invalid(manifest(api = 6)).field)
    }

    @Test fun `a list needs one to four fields, of type text or url, with their own keys and names`() {
        fun broken(f: JSONArray?) = invalid(manifest(setting = list { if (f == null) remove("fields") else put("fields", f) })).field
        assertEquals("settings", broken(null))
        assertEquals("settings", broken(JSONArray()))
        assertEquals("settings", broken(JSONArray((1..5).map { field("f$it", "Campo $it", "text") })))
        assertEquals("settings", broken(JSONArray().put(field("a", "A", "password"))))
        assertEquals("settings", broken(JSONArray().put(field("a", "A", "toggle"))))
        assertEquals("settings", broken(JSONArray().put(field("a", "A", "list"))))
        assertEquals("settings", broken(JSONArray().put(field("a", "A", "text")).put(field("a", "B", "text"))))
        assertEquals("settings", broken(JSONArray().put(field("Bad Key", "A", "text"))))
        assertEquals("settings", broken(JSONArray().put(field("a", "", "text"))))
    }

    @Test fun `a list cannot have a default value, and fields cannot either`() {
        assertEquals("settings", invalid(manifest(setting = list { put("default", "x") })).field)
        val withDefault = field("a", "A", "text").put("default", "x")
        assertEquals("settings", invalid(manifest(setting = list { put("fields", JSONArray().put(withDefault)) })).field)
    }

    @Test fun `fields only belong to a list`() {
        val text = JSONObject().put("key", "k").put("label", "K").put("type", "text").put("fields", JSONArray().put(field("a", "A", "text")))
        assertEquals("settings", invalid(manifest(api = 4, setting = text)).field)
    }

    private val setting get() = valid(manifest(setting = list { put("max", 3) })).settings.single()

    private fun entry(url: String, category: String = "") = mapOf("url" to url, "category" to category)

    @Test fun `entries with valid fields are accepted, up to max`() {
        assertNull(PluginSettings.validateValue(setting, listOf(entry("https://archive.org/details/a", "Cine"), entry("https://archive.org/details/b"))))
        assertNull(PluginSettings.validateValue(setting, emptyList<Map<String, String>>()))
        assertTrue(PluginSettings.validateValue(setting, (1..4).map { entry("https://archive.org/details/$it") })!!.contains("3"))
    }

    @Test fun `an entry with a bad address or a missing required field says which`() {
        assertTrue(PluginSettings.validateValue(setting, listOf(entry("no es una url")))!!.contains("Dirección"))
        assertTrue(PluginSettings.validateValue(setting, listOf(entry("", "Solo categoría")))!!.contains("Dirección"))
    }

    @Test fun `a value that is not a list of entries is refused`() {
        assertTrue(PluginSettings.validateValue(setting, "texto") != null)
        assertTrue(PluginSettings.validateValue(setting, listOf("suelto")) != null)
    }

    @Test fun `a required list needs at least one entry`() {
        val required = valid(manifest(setting = list { put("required", true) })).settings.single()
        assertEquals(listOf("sources"), PluginSettings.missingRequired(listOf(required), mapOf("sources" to emptyList<Map<String, String>>())).map { it.key })
        assertEquals(emptyList<String>(), PluginSettings.missingRequired(listOf(required), mapOf("sources" to listOf(entry("https://a.example/x")))).map { it.key })
    }

    @Test fun `the addresses inside a list become allowed hosts, like a url setting`() {
        val hosts = PluginHosts.effective(
            listOf("example.com"), listOf(setting),
            mapOf("sources" to listOf(entry("https://my.server:8443/x", "Uno"), entry("https://other.example/y"), entry("https://my.server:8443/z"))),
        )
        assertEquals(listOf("https://my.server:8443", "https://other.example:443"), hosts.user.map { it.label })
    }
}
