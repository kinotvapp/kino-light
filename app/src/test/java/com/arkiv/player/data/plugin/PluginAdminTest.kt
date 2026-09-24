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
        registry = PluginRegistry(store)
        pool = PluginRuntimePool(open = { FakeRuntime() }, onUnresponsive = {}, scope = CoroutineScope(Dispatchers.Unconfined))
        val installer = PluginInstaller(store, fetcher, probe = { setOf("search", "resolve") }, clock = { 1_000L })
        admin = DefaultPluginAdmin(registry, installer, pool)
    }

    private fun publish(version: String) {
        files[base + "kino-plugin.json"] = JSONObject()
            .put("id", "demo").put("name", "Demo").put("version", version).put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).toString().toByteArray()
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
}
