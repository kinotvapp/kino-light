package com.arkiv.player.data.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/**
 * The registry is reloaded BEFORE the old runtime is closed: a call landing in between would
 * otherwise open the NEW script with the OLD registry entry (old approved hosts) and keep that
 * runtime until it idles out. Each fake runtime records what the registry said when it was closed.
 */
class PluginAdminTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = "https://raw.githubusercontent.com/o/r/HEAD/"
    private val files = mutableMapOf<String, ByteArray>()
    private val fetcher = PluginFetcher { url, _ -> files[url] ?: throw FileNotFoundException(url) }
    private val registryAtClose = mutableListOf<String>()
    private lateinit var store: PluginStore
    private lateinit var registry: PluginRegistry
    private lateinit var pool: PluginRuntimePool
    private lateinit var admin: DefaultPluginAdmin
    private val secrets = mutableMapOf<String, String>()
    private val forgotten = mutableListOf<String>()
    private lateinit var config: PluginConfigStore

    private inner class FakeRuntime : ScriptRuntime {
        override val exports = setOf("search", "resolve")
        override var isDiscarded = false
        override suspend fun call(function: String, argJson: String, timeoutMs: Long) = "[]"
        override fun close() {
            registryAtClose += registry.find("demo")?.record?.version ?: "gone"
            isDiscarded = true
        }
    }

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        config = PluginConfigStore({ store.dataDir(it) }, object : SecretStore {
            override fun get(key: String) = secrets[key]
            override fun put(key: String, value: String) { secrets[key] = value }
            override fun remove(key: String) { secrets.remove(key) }
        })
        registry = PluginRegistry(store) { p -> config.setupState(p.manifest.id, p.manifest.settings) }
        pool = PluginRuntimePool(open = { FakeRuntime() }, onUnresponsive = {}, scope = CoroutineScope(Dispatchers.Unconfined))
        val installer = PluginInstaller(store, fetcher, probe = { setOf("search", "resolve") }, clock = { 1_000L })
        admin = DefaultPluginAdmin(registry, installer, pool, config, forgetSession = { forgotten += it }, io = Dispatchers.Unconfined)
    }

    private val passwordSetting = JSONArray("""[{"key":"password","label":"Contraseña","type":"password","required":true}]""")

    private fun publish(version: String, settings: JSONArray? = null) {
        files[base + "kino-plugin.json"] = JSONObject()
            .put("id", "demo").put("name", "Demo").put("version", version).put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve")))
            .apply { settings?.let { put("settings", it) } }
            .toString().toByteArray()
        files[base + "plugin.js"] = "export async function search(){}\nexport async function resolve(){}".toByteArray()
    }

    private fun installAndOpen(version: String) = runBlocking {
        publish(version)
        admin.install(admin.preview("o/r"))
        pool.call("demo", "search", "{}", 1_000)
        registryAtClose.clear()
    }

    @Test fun `an approved update reloads the registry before closing the old runtime`() = runBlocking {
        installAndOpen("1.0.0")
        publish("1.1.0")
        admin.install(admin.preview("o/r"))
        assertEquals(listOf("1.1.0"), registryAtClose)
    }

    @Test fun `a silent update reloads the registry before closing the old runtime`() = runBlocking {
        installAndOpen("1.0.0")
        publish("1.1.0")
        assertEquals(UpdateOutcome.Applied("1.1.0"), admin.checkUpdate("demo"))
        assertEquals(listOf("1.1.0"), registryAtClose)
    }

    @Test fun `uninstall removes the plugin from the registry before closing its runtime`() {
        installAndOpen("1.0.0")
        admin.uninstall("demo")
        assertEquals(listOf("gone"), registryAtClose)
    }

    @Test fun `saving settings closes the runtime, forgets the session and clears Falta configurar`() = runBlocking {
        publish("1.0.0", passwordSetting)
        admin.install(admin.preview("o/r"))
        assertEquals(true, registry.find("demo")!!.needsSetup)
        assertEquals(null, admin.saveSettings("demo", mapOf("password" to "s3cr3t")))
        assertEquals(false, registry.find("demo")!!.needsSetup)
        assertEquals(listOf("demo"), forgotten)
        assertEquals("s3cr3t", secrets["plugin.demo.password"])
        assertEquals(mapOf<String, Any>("password" to "s3cr3t"), admin.settingsOf("demo")!!.values)
    }

    @Test fun `a refused save changes nothing`() = runBlocking {
        publish("1.0.0", passwordSetting)
        admin.install(admin.preview("o/r"))
        pool.call("demo", "search", "{}", 1_000)
        registryAtClose.clear()
        assertEquals("Completa \"Contraseña\"", admin.saveSettings("demo", mapOf("password" to "")))
        assertEquals(emptyList<String>(), forgotten)
        assertEquals(emptyList<String>(), registryAtClose)
    }

    @Test fun `uninstall forgets the plugin's passwords`() = runBlocking {
        publish("1.0.0", passwordSetting)
        admin.install(admin.preview("o/r"))
        admin.saveSettings("demo", mapOf("password" to "s3cr3t"))
        admin.uninstall("demo")
        assertEquals(emptyMap<String, String>(), secrets)
    }
}
