package com.arkiv.player.data.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

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
    private lateinit var installer: PluginInstaller

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
        installer = PluginInstaller(store, fetcher, probe = { setOf("search", "resolve") }, clock = { 1_000L })
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

    /**
     * Fix round 1, finding 2: `saveSettings` must reload the registry BEFORE it forgets the
     * session, not after. Otherwise a runtime opened right after `forgetSession` retired the old
     * jar (but before the registry caught up) would read the NEW config but the OLD hosts -- and
     * `forgetSession` itself would be looking at a stale registry snapshot. Proven directly: the
     * hosts `forgetSession` sees, captured the instant it runs, must already be the saved ones.
     */
    @Test fun `saveSettings reloads the registry before forgetting the session, so forget sees the new hosts`() = runBlocking {
        publish("1.0.0", JSONArray("""[{"key":"server","label":"Servidor","type":"url","required":true}]"""))
        admin.install(admin.preview("o/r"))
        var hostsAtForget: EffectiveHosts? = null
        val tracking = DefaultPluginAdmin(
            registry, installer, pool, config,
            forgetSession = { id -> hostsAtForget = registry.find(id)!!.hosts; forgotten += id },
            io = Dispatchers.Unconfined,
        )
        assertEquals(null, tracking.saveSettings("demo", mapOf("server" to "http://192.168.1.10:8096")))
        assertEquals(
            EffectiveHosts(listOf("example.com"), listOf(UserHost("http", "192.168.1.10", 8096))),
            hostsAtForget,
        )
    }

    /**
     * Fix round 2, finding 3b's wiring: `afterSessionClosed` is a SEPARATE callback from
     * `forgetSession`, called once, after it -- source order in `saveSettings` places it after
     * `runtimes.close()` too (read that method's own comment for why: this test can't reliably
     * observe `runtimes.close`'s asynchronous internal effect via `Dispatchers.Unconfined`'s
     * nested-dispatch queueing, so it isn't asserted here). It exists specifically to bump the
     * Home-refresh session revision a second time once the pool's slot is guaranteed gone -- see
     * `PluginHomeRowsTest`'s "window b" test, which proves that END-TO-END property directly.
     */
    @Test fun `saveSettings calls afterSessionClosed exactly once, after forgetSession`() = runBlocking {
        publish("1.0.0", passwordSetting)
        admin.install(admin.preview("o/r"))
        pool.call("demo", "search", "{}", 1_000)
        registryAtClose.clear()
        val order = mutableListOf<String>()
        val tracking = DefaultPluginAdmin(
            registry, installer, pool, config,
            forgetSession = { order += "forget" },
            afterSessionClosed = { order += "afterClose" },
            io = Dispatchers.Unconfined,
        )
        assertEquals(null, tracking.saveSettings("demo", mapOf("password" to "s3cr3t")))
        assertEquals(listOf("forget", "afterClose"), order)
        // runtimes.close() itself already ran by the time saveSettings returns (the fake runtime's
        // own close() side effect, same evidence the OTHER tests in this file already rely on).
        assertEquals(listOf("1.0.0"), registryAtClose)
    }

    /**
     * Fix round 1, finding 6: `install`'s `registry.reload()` -- which now reads config.json for
     * EVERY plugin -- must run on [io], not on whatever dispatcher called `install` (Main, via
     * `PluginsViewModel.busy`/`viewModelScope`). Uses real dispatchers (not `Unconfined`, which
     * makes every coroutine "run" on the calling thread and would hide the bug) to prove reload()
     * actually executes on the io thread.
     */
    @Test fun `install reloads the registry on the io dispatcher, never the caller's thread`() = runBlocking {
        val callerThread = Thread.currentThread().name
        val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-io-test") }
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()
        try {
            var reloadThread: String? = null
            val trackingRegistry = PluginRegistry(store) { p ->
                reloadThread = Thread.currentThread().name
                config.setupState(p.manifest.id, p.manifest.settings)
            }
            val trackingAdmin = DefaultPluginAdmin(trackingRegistry, installer, pool, config, io = ioDispatcher)
            publish("1.0.0")
            trackingAdmin.install(trackingAdmin.preview("o/r"))
            // Coroutine debug mode suffixes the thread name ("plugin-io-test @coroutine#1"): check
            // it's OUR executor's thread, not demand an exact match against that suffix.
            assertTrue("reload ran on $reloadThread, not the io executor", reloadThread!!.startsWith("plugin-io-test"))
            assertNotEquals(callerThread, reloadThread)
        } finally {
            ioExecutor.shutdown()
        }
    }
}
