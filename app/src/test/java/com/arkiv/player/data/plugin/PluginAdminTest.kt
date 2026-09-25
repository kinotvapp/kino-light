package com.arkiv.player.data.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Final review M1: uninstall forgets the session like a settings save does. Real jar, real
     * files: a call still in flight when the person uninstalls must not write `cookies.json` back
     * into the deleted data dir, where a reinstall of the same id would inherit it.
     */
    @Test fun `uninstall retires the live cookie jar and forgets the home cache, so a late write can't reach a reinstall`() {
        installAndOpen("1.0.0")
        val jars = PluginJarRegistry()
        val dataDir = store.dataDir("demo")
        val cookiesFile = File(dataDir, PluginCookies.FILE_NAME)
        val homeFile = File(dataDir, "home.json")
        val url = "https://example.com/".toHttpUrl()
        val jar = PluginCookies(cookiesFile, EffectiveHosts(listOf("example.com")))
        jars.put("demo", jar)
        jar.saveFromResponse(url, listOfNotNull(Cookie.parse(url, "sid=A")))
        jar.saveIfChanged()
        homeFile.writeText("{}")
        assertTrue(cookiesFile.exists())
        val order = mutableListOf<String>()
        val tracking = DefaultPluginAdmin(
            registry, installer, pool, config,
            // Wired like AppGraph's forgetPluginHomeCache / forgetPluginSession.
            forgetHomeCache = { id -> order += "home"; File(store.dataDir(id), "home.json").delete() },
            forgetSession = { id -> order += "session"; jars.forget(id); File(store.dataDir(id), PluginCookies.FILE_NAME).delete() },
            afterSessionClosed = { order += "afterClose" },
            io = Dispatchers.Unconfined,
        )
        tracking.uninstall("demo")
        assertEquals(listOf("home", "session", "afterClose"), order)
        assertEquals(listOf("gone"), registryAtClose)

        // The call that was still in flight finishes now, on the jar it opened with.
        jar.saveFromResponse(url, listOfNotNull(Cookie.parse(url, "sid=A-late")))
        jar.saveIfChanged()
        assertFalse("a late write must not resurrect cookies.json", cookiesFile.exists())
        assertFalse(homeFile.exists())
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
     * Fix round 3, "new breakage 1": finding 4's fix (round 2) made a settings save trigger a Home
     * re-fetch through `registry.reload()`'s emission (`pluginsChanged`) -- but `reload()` used to
     * run BEFORE the step that deletes `home.json`/bumps the session revision. A re-fetch that
     * `reload()` itself triggers could therefore read the PRE-forget `home.json` on EVERY save, not
     * just ones that change hosts. Proven directly, the same pattern as the finding-2 test above:
     * `forgetHomeCache` must have ALREADY run by the moment `reload()`'s own per-plugin `setup`
     * lambda executes -- the earliest point any real collector of `registry.plugins` could ever
     * observe its effects, so this is a reliable, non-flaky stand-in for "before ANY observer sees
     * the reload."
     */
    @Test fun `saveSettings forgets the home cache before reload, so a reload-triggered refresh can't see stale state`() = runBlocking {
        publish("1.0.0", passwordSetting)
        admin.install(admin.preview("o/r"))
        var homeCacheForgotten = false
        var homeCacheForgottenWhenReloadRan = false
        val trackingRegistry = PluginRegistry(store) { p ->
            homeCacheForgottenWhenReloadRan = homeCacheForgotten
            config.setupState(p.manifest.id, p.manifest.settings)
        }
        trackingRegistry.reload() // initial load of "demo", from the same on-disk store as `admin`
        val tracking = DefaultPluginAdmin(
            trackingRegistry, installer, pool, config,
            forgetHomeCache = { homeCacheForgotten = true },
            io = Dispatchers.Unconfined,
        )
        assertEquals(null, tracking.saveSettings("demo", mapOf("password" to "s3cr3t")))
        assertTrue(
            "the home cache must already be forgotten by the time reload() runs, so a reload-" +
                "triggered Home re-fetch can never observe the pre-forget state",
            homeCacheForgottenWhenReloadRan,
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
