package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.plugin.PluginConfigStore
import com.arkiv.player.data.plugin.PluginSetting
import com.arkiv.player.data.plugin.SecretStore
import com.arkiv.player.data.plugin.SettingOption
import com.arkiv.player.data.plugin.SettingType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MapSecrets : SecretStore {
        val map = LinkedHashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private val secrets = MapSecrets()
    private val store by lazy { PluginConfigStore({ id -> File(tmp.root, id) }, secrets) }
    private val settings = listOf(
        PluginSetting("server", "Servidor", SettingType.URL, required = true),
        PluginSetting("user", "Usuario", SettingType.TEXT, required = true),
        PluginSetting("password", "Contraseña", SettingType.PASSWORD, required = true),
        PluginSetting("hd", "Solo HD", SettingType.TOGGLE, default = false),
        PluginSetting("quality", "Calidad", SettingType.SELECT, default = "auto", options = listOf(SettingOption("auto", "A"), SettingOption("720", "720p"))),
        PluginSetting("tags", "Etiquetas", SettingType.LIST, fields = listOf(PluginSetting("tag", "Tag", SettingType.TEXT)), max = 5),
        PluginSetting("mirrors", "Espejos", SettingType.LIST, fields = listOf(PluginSetting("url", "Url", SettingType.URL)), max = 5),
    )

    @Test fun `passwords, typed servers and lists of servers never travel`() {
        assertEquals(listOf("user", "hd", "quality", "tags"), settings.filter(SharedSettings::isShared).map { it.key })
    }

    @Test fun `shared values are only what the person set, never a default or a secret`() {
        store.save("jf", settings, mapOf("server" to "http://192.168.1.10:8096", "user" to "ana", "password" to "s3cr3t", "quality" to "720", "tags" to listOf(mapOf("tag" to "x"))))
        assertEquals(mapOf("user" to "ana", "quality" to "720", "tags" to listOf(mapOf("tag" to "x"))), store.sharedValues("jf", settings))
    }

    @Test fun `applying a peer's answers overlays them and leaves password and server alone`() {
        store.save("jf", settings, mapOf("server" to "http://192.168.1.10:8096", "user" to "ana", "password" to "s3cr3t", "hd" to true))
        val changed = store.applyShared("jf", settings, mapOf("user" to "beto", "quality" to "720", "server" to "http://evil.example.com", "password" to "x"))
        assertTrue(changed)
        val values = store.read("jf", settings).values
        assertEquals("beto", values["user"])
        assertEquals("720", values["quality"])
        assertEquals(true, values["hd"]) // the peer had no value: kept
        assertEquals("http://192.168.1.10:8096", values["server"])
        assertEquals("s3cr3t", values["password"])
    }

    @Test fun `a value that does not fit this manifest is skipped and nothing changes`() {
        store.save("jf", settings, mapOf("server" to "http://192.168.1.10:8096", "user" to "ana", "password" to "s3cr3t"))
        assertFalse(store.applyShared("jf", settings, mapOf("quality" to "4k", "hd" to "yes")))
        assertFalse(store.applyShared("jf", settings, mapOf("user" to "ana")))
    }

    @Test fun `a peer's json is read defensively`() {
        val o = JSONObject().put("user", "ana").put("hd", true).put("n", 3)
            .put("tags", JSONArray().put(JSONObject().put("tag", "x")))
            .put("bad", JSONArray().put("not an object"))
            .put("x".repeat(65), "too long a key")
        assertEquals(mapOf("user" to "ana", "hd" to true, "tags" to listOf(mapOf("tag" to "x"))), SharedSettings.fromJson(o))
        assertEquals(SharedSettings.fromJson(o), SharedSettings.fromJson(SharedSettings.toJson(SharedSettings.fromJson(o))))
    }
}
