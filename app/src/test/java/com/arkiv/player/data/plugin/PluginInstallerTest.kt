package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class PluginInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files = mutableMapOf<String, ByteArray>()
    private val fetcher = PluginFetcher { url, max ->
        val bytes = files[url] ?: throw FileNotFoundException(url)
        if (bytes.size > max) throw IOException("archivo demasiado grande")
        bytes
    }
    private var exports: (String) -> Set<String> = { setOf("search", "resolve") }
    private var now = 1_000L
    private lateinit var store: PluginStore
    private lateinit var installer: PluginInstaller
    private val base = "https://raw.githubusercontent.com/o/r/HEAD/"

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        installer = PluginInstaller(store, fetcher, probe = { exports(it) }, clock = { now })
    }

    private fun publish(
        version: String = "1.0.0",
        hosts: List<String> = listOf("example.com"),
        script: String = "export async function search(){}\nexport async function resolve(){}",
        prefix: String = base,
        api: Int = 1,
    ) {
        files[prefix + "kino-plugin.json"] = JSONObject()
            .put("id", "demo").put("name", "Demo").put("version", version).put("apiVersion", api)
            .put("entry", "plugin.js").put("hosts", JSONArray(hosts))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).toString().toByteArray()
        files[prefix + "plugin.js"] = script.toByteArray()
    }

    private fun installFresh() = runBlocking { installer.install(installer.preview("o/r")) }

    @Test fun `install writes the files and a verified record`() = runBlocking {
        publish()
        val preview = installer.preview("o/r")
        assertFalse(preview.isUpdate)
        assertEquals(listOf("example.com"), preview.newHosts)
        val record = installer.install(preview)
        val stored = store.get("demo")!!
        assertEquals("o/r", stored.record.address)
        assertEquals(sha256Hex(files[base + "plugin.js"]!!), record.sha256)
        assertTrue(store.readVerifiedScript("demo").contains("resolve"))
        assertTrue(File(tmp.root, "plugins").list()!!.none { it.startsWith(".staging") || it.startsWith(".old") })
    }

    @Test fun `bad address, missing manifest and invalid manifest are explained in Spanish`() {
        val bad = assertThrows(InstallException::class.java) { runBlocking { installer.preview("nope") } }
        assertTrue(bad.message!!.contains("usuario/repositorio"))
        val missing = assertThrows(InstallException::class.java) { runBlocking { installer.preview("o/r") } }
        assertTrue(missing.message!!.contains("kino-plugin.json"))
        publish()
        files[base + "kino-plugin.json"] = "{\"id\":\"X\"}".toByteArray()
        val invalid = assertThrows(InstallException::class.java) { runBlocking { installer.preview("o/r") } }
        assertTrue(invalid.message!!.contains("\"id\""))
    }

    @Test fun `a new version missing a declared export is refused and the installed one survives`() {
        publish("1.0.0"); installFresh()
        publish("1.1.0", script = "export async function search(){}")
        exports = { setOf("search") }
        val e = assertThrows(InstallException::class.java) { runBlocking { installer.install(installer.preview("o/r")) } }
        assertEquals("El plugin no carga: le falta resolve", e.message)
        assertEquals("1.0.0", store.get("demo")!!.record.version)
        assertTrue(store.readVerifiedScript("demo").contains("resolve"))
    }

    @Test fun `a script that does not load is refused with the engine message`() {
        publish()
        exports = { throw PluginScriptException("SyntaxError: unexpected token") }
        val e = assertThrows(InstallException::class.java) { installFresh() }
        assertEquals("El plugin no carga: SyntaxError: unexpected token", e.message)
        assertNull(store.get("demo"))
    }

    @Test fun `the same id from another address is refused`() {
        publish(); installFresh()
        publish(prefix = "https://raw.githubusercontent.com/other/r/HEAD/")
        val e = assertThrows(InstallException::class.java) { runBlocking { installer.preview("other/r") } }
        assertTrue(e.message!!.startsWith("Ya hay un plugin con ese id"))
    }

    @Test fun `an update with the same hosts is applied silently and keeps the enabled flag`() = runBlocking {
        publish("1.0.0"); installFresh()
        store.writeRecord("demo", store.get("demo")!!.record.copy(enabled = false))
        publish("1.1.0")
        assertEquals(UpdateOutcome.Applied("1.1.0"), installer.checkUpdate("demo"))
        assertEquals("1.1.0", store.get("demo")!!.record.version)
        assertFalse(store.get("demo")!!.record.enabled)
    }

    @Test fun `an update with new hosts waits for approval`() = runBlocking {
        publish("1.0.0"); installFresh()
        publish("2.0.0", hosts = listOf("example.com", "cdn.example.net"))
        val outcome = installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        assertEquals(listOf("cdn.example.net"), outcome.preview.newHosts)
        assertEquals("1.0.0", store.get("demo")!!.record.version)
        assertEquals("2.0.0", store.get("demo")!!.record.pendingVersion)
        installer.install(outcome.preview) // the person approved on the consent sheet
        val after = store.get("demo")!!.record
        assertEquals("2.0.0", after.version)
        assertNull(after.pendingVersion)
        assertEquals(listOf("example.com", "cdn.example.net"), after.hosts)
    }

    @Test fun `same or older version is up to date`() = runBlocking {
        publish("1.0.0"); installFresh()
        assertEquals(UpdateOutcome.UpToDate, installer.checkUpdate("demo"))
        publish("0.9.0")
        assertEquals(UpdateOutcome.UpToDate, installer.checkUpdate("demo"))
    }

    @Test fun `an update needing a newer Kino is reported, not applied`() = runBlocking {
        publish("1.0.0"); installFresh()
        publish("2.0.0", api = 2)
        val o = installer.checkUpdate("demo") as UpdateOutcome.Failed
        assertEquals("Este plugin necesita una versión más nueva de Kino", o.message)
        assertEquals("1.0.0", store.get("demo")!!.record.version)
    }

    @Test fun `a change made while checkUpdate is still fetching is not lost (no stale write-back)`() = runBlocking {
        publish("1.0.0"); installFresh()
        // A fetcher that simulates the person disabling the plugin WHILE the manifest fetch that
        // this checkUpdate call kicked off is still in flight -- checkUpdate must not silently
        // revert that disable when it later writes back its own result (lastUpdateCheckAt here,
        // since the manifest is unchanged so the outcome is UpToDate).
        val racyFetcher = PluginFetcher { url, max ->
            val bytes = fetcher.fetch(url, max)
            if (url == base + "kino-plugin.json") {
                store.writeRecord("demo", store.get("demo")!!.record.copy(enabled = false))
            }
            bytes
        }
        val racyInstaller = PluginInstaller(store, racyFetcher, probe = { exports(it) }, clock = { now })
        assertEquals(UpdateOutcome.UpToDate, racyInstaller.checkUpdate("demo"))
        assertFalse(store.get("demo")!!.record.enabled)
    }

    @Test fun `a tampered script is detected`() {
        publish(); installFresh()
        File(store.get("demo")!!.dir, "plugin.js").writeText("evil")
        assertThrows(PluginDamagedException::class.java) { store.readVerifiedScript("demo") }
    }

    @Test fun `due updates are checked at most once per 24 hours`() = runBlocking {
        publish("1.0.0"); installFresh()
        publish("1.1.0")
        now += 23 * 3_600_000L
        assertEquals(emptyList<Pair<String, UpdateOutcome>>(), installer.checkDueUpdates())
        now += 2 * 3_600_000L
        assertEquals(listOf("demo" to UpdateOutcome.Applied("1.1.0")), installer.checkDueUpdates())
    }
}
