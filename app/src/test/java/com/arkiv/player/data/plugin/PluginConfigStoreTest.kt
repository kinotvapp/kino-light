package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginConfigStoreTest {
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
    )
    private val full = mapOf("server" to " http://192.168.1.10:8096 ", "user" to "ana", "password" to "s3cr3t", "hd" to true, "quality" to "720")

    @Test fun `nothing saved means defaults only and every required setting missing`() {
        assertEquals(mapOf("hd" to false, "quality" to "auto"), store.read("jf", settings).values)
        assertEquals(listOf("server", "user", "password"), store.missing("jf", settings).map { it.key })
        assertEquals(PluginSetupState(emptyList(), listOf("server", "user", "password")), store.setupState("jf", settings))
    }

    @Test fun `saving splits secrets from the file and reads everything back`() {
        assertNull(store.save("jf", settings, full))
        val file = File(tmp.root, "jf/config.json").readText()
        assertFalse("the password must never be in config.json", file.contains("s3cr3t"))
        assertTrue(file.contains("\"secrets\":[\"password\"]"))
        assertEquals("s3cr3t", secrets.map["plugin.jf.password"])
        assertEquals(
            mapOf("server" to "http://192.168.1.10:8096", "user" to "ana", "password" to "s3cr3t", "hd" to true, "quality" to "720"),
            store.read("jf", settings).values,
        )
        assertEquals(emptyList<PluginSetting>(), store.missing("jf", settings))
        assertEquals(listOf(UserHost("http", "192.168.1.10", 8096)), store.setupState("jf", settings).userHosts)
    }

    @Test fun `a required value left blank refuses the whole save`() {
        assertEquals("Completa \"Contraseña\"", store.save("jf", settings, full + ("password" to "  ")))
        assertFalse(File(tmp.root, "jf/config.json").exists())
        assertTrue(secrets.map.isEmpty())
    }

    @Test fun `a bad url, a loopback server or an oversized value refuses the save`() {
        assertTrue(store.save("jf", settings, full + ("server" to "no es url"))!!.contains("no es una dirección válida"))
        assertTrue(store.save("jf", settings, full + ("server" to "http://127.0.0.1:8096"))!!.contains("no es una dirección válida"))
        assertTrue(store.save("jf", settings, full + ("user" to "u".repeat(501)))!!.contains("500"))
        assertTrue(store.save("jf", settings, full + ("quality" to "4k"))!!.contains("Calidad"))
        assertFalse(File(tmp.root, "jf/config.json").exists())
    }

    @Test fun `clearing an optional password removes it from the secret store`() {
        val optional = listOf(PluginSetting("token", "Token", SettingType.PASSWORD))
        assertNull(store.save("api", optional, mapOf("token" to "t1")))
        assertEquals("t1", store.read("api", optional).values["token"])
        assertNull(store.save("api", optional, mapOf("token" to "")))
        assertNull(secrets.map["plugin.api.token"])
        assertEquals(emptyMap<String, Any>(), store.read("api", optional).values)
    }

    @Test fun `clear removes the file and the plugin's secrets only`() {
        store.save("jf", settings, full)
        secrets.put("plugin.other.password", "keep")
        store.clear("jf", settings)
        assertFalse(File(tmp.root, "jf/config.json").exists())
        assertNull(secrets.map["plugin.jf.password"])
        assertEquals("keep", secrets.map["plugin.other.password"])
    }

    @Test fun `a tampered config file is read defensively`() {
        File(tmp.root, "jf").mkdirs()
        File(tmp.root, "jf/config.json").writeText("""{"values":{"server":"http://127.0.0.1","hd":"yes","quality":"4k","user":42},"secrets":["password"]}""")
        // Invalid stored values fall back to defaults; a listed secret that isn't in the store is simply absent.
        assertEquals(mapOf("hd" to false, "quality" to "auto"), store.read("jf", settings).values)
        assertEquals(emptyList<UserHost>(), store.setupState("jf", settings).userHosts)
        File(tmp.root, "jf/config.json").writeText("garbage")
        assertEquals(3, store.missing("jf", settings).size)
    }

    @Test fun `config JSON handed to the plugin holds only what is set`() {
        store.save("jf", settings, full)
        val json = org.json.JSONObject(store.read("jf", settings).toJson())
        assertEquals(setOf("server", "user", "password", "hd", "quality"), json.keySet())
    }
}
