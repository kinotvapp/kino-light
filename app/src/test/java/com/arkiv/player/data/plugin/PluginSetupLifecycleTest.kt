package com.arkiv.player.data.plugin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/** Installer re-approval for permissions, settings on update, and the registry's setup state. */
class PluginSetupLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files = mutableMapOf<String, ByteArray>()
    private val base = "https://raw.githubusercontent.com/o/r/HEAD/"
    private val store by lazy { PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data")) }
    private fun installer(known: Set<String> = emptySet()) = PluginInstaller(
        store,
        PluginFetcher { url, _ -> files[url] ?: throw FileNotFoundException(url) },
        probe = { setOf("search", "resolve") },
        clock = { 1_000L },
        knownPermissions = known,
    )

    private fun publish(version: String, permissions: List<String> = emptyList(), settings: JSONArray? = null, hosts: List<String> = listOf("example.com")) {
        files[base + "kino-plugin.json"] = JSONObject()
            .put("id", "demo").put("name", "Demo").put("version", version).put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(hosts))
            .put("capabilities", JSONArray(listOf("search", "resolve")))
            .apply { if (permissions.isNotEmpty()) put("permissions", JSONArray(permissions)) }
            .apply { settings?.let { put("settings", it) } }
            .toString().toByteArray()
        files[base + "plugin.js"] = "export async function search(){}\nexport async function resolve(){}".toByteArray()
    }

    @Test fun `SDK v1 refuses every permission at install`() {
        publish("1.0.0", permissions = listOf("local-network"))
        val e = runCatching { runBlocking { installer().preview("o/r") } }.exceptionOrNull()
        assertEquals("permiso desconocido: local-network", e?.message)
    }

    @Test fun `an update that adds a permission waits for approval, like a new host`() = runBlocking {
        publish("1.0.0")
        installer(setOf("test-permission")).let { it.install(it.preview("o/r")) }
        publish("1.1.0", permissions = listOf("test-permission"))
        val outcome = installer(setOf("test-permission")).checkUpdate("demo")
        assertTrue(outcome is UpdateOutcome.NeedsApproval)
        val preview = (outcome as UpdateOutcome.NeedsApproval).preview
        assertEquals(listOf("test-permission"), preview.newPermissions)
        assertEquals(emptyList<String>(), preview.newHosts)
        with(store.get("demo")!!.record) {
            assertEquals("1.1.0", pendingVersion)
            assertEquals(listOf("test-permission"), pendingPermissions)
            assertEquals("1.0.0", version)
        }
    }

    @Test fun `an update that only adds a required setting applies, and the plugin then needs setup`() = runBlocking {
        publish("1.0.0")
        installer().let { it.install(it.preview("o/r")) }
        publish("1.1.0", settings = JSONArray("""[{"key":"token","label":"Token","type":"password","required":true}]"""))
        assertEquals(UpdateOutcome.Applied("1.1.0"), installer().checkUpdate("demo"))
        val config = PluginConfigStore({ id -> store.dataDir(id) }, object : SecretStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = Unit
            override fun remove(key: String) = Unit
        })
        val registry = PluginRegistry(store) { p -> config.setupState(p.manifest.id, p.manifest.settings) }.also { it.reload() }
        val p = registry.find("demo")!!
        assertEquals(listOf("token"), p.missingSettings)
        assertTrue(p.needsSetup)
        assertEquals(PluginStatus.NEEDS_SETUP, p.status)
        assertTrue(p.isUsable)
    }

    @Test fun `the registry carries typed servers into the player's access`() = runBlocking {
        publish("1.0.0", settings = JSONArray("""[{"key":"server","label":"Servidor","type":"url","required":true}]"""))
        installer().let { it.install(it.preview("o/r")) }
        File(store.dataDir("demo"), "config.json").apply { parentFile!!.mkdirs() }
            .writeText("""{"values":{"server":"http://192.168.1.10:8096"},"secrets":[]}""")
        val config = PluginConfigStore({ id -> store.dataDir(id) }, object : SecretStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = Unit
            override fun remove(key: String) = Unit
        })
        val registry = PluginRegistry(store) { p -> config.setupState(p.manifest.id, p.manifest.settings) }.also { it.reload() }
        val access = registry.accessFor("demo") as PluginAccess.Ready
        assertEquals(EffectiveHosts(listOf("example.com"), listOf(UserHost("http", "192.168.1.10", 8096))), access.hosts)
        assertFalse(registry.find("demo")!!.needsSetup)
    }

    @Test fun `a record from before SDK v1 reads with no permissions`() {
        val old = """{"address":"o/r","version":"1.0.0","sha256":"x","hosts":["a.example"],"installedAt":1}"""
        with(InstalledRecord.fromJson(old)!!) {
            assertEquals(emptyList<String>(), permissions)
            assertEquals(emptyList<String>(), pendingPermissions)
        }
        val round = InstalledRecord("o/r", "1", "x", listOf("a.example"), 1, permissions = listOf("p"), pendingPermissions = listOf("q"))
        assertEquals(round, InstalledRecord.fromJson(round.toJson()))
    }

    @Test fun `home rows carry the browse ref and skip a plugin that needs setup`() = runTest {
        fun plugin(id: String, missing: List<String> = emptyList()) = InstalledPlugin(
            PluginManifest(id, id.uppercase(), "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("home", "browse", "resolve"), null, null),
            InstalledRecord("o/$id", "1.0.0", "x", listOf("example.com"), 0L), null, missingSettings = missing,
        )
        var asked = 0
        val rows = PluginHomeRows(
            { listOf(plugin("a"), plugin("b", missing = listOf("server"))) },
            PluginCaller { _, _, _, _ -> asked++; """[{"id":"top","title":"T","ref":"films","items":[{"id":"m","ref":"r","title":"M","kind":"movie"}]}]""" },
            cacheFileFor = { File(tmp.root, "$it/home.json") }, log = {},
        ).rows().toList().last()
        assertEquals(listOf("a"), rows.map { it.pluginId })
        assertEquals("films", rows.single().ref)
        assertEquals(1, asked)
    }
}
