package com.arkiv.player.data.plugin

import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    // Ids need 2+ chars (manifest rule), hence "pa", "pb"…
    private lateinit var store: PluginStore
    private lateinit var registry: PluginRegistry

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        registry = PluginRegistry(store)
    }

    private fun install(id: String, name: String, record: InstalledRecord.() -> InstalledRecord = { this }) {
        val manifest = JSONObject().put("id", id).put("name", name).put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).toString()
        val script = "export async function search(){}".toByteArray()
        val staging = store.newStaging(id)
        store.writeFiles(staging, manifest, "plugin.js", script, null,
            InstalledRecord("o/$id", "1.0.0", sha256Hex(script), listOf("example.com"), 1L).record())
        store.commit(staging, id)
        registry.reload()
    }

    @Test fun `status follows the record`() {
        install("pa", "A")
        install("pb", "B") { copy(enabled = false) }
        install("pc", "C") { copy(unresponsive = true) }
        install("pd", "D") { copy(pendingVersion = "2.0.0") }
        install("pe", "E") { copy(damaged = true) }
        assertEquals(
            listOf(PluginStatus.ACTIVE, PluginStatus.DISABLED, PluginStatus.UNRESPONSIVE, PluginStatus.UPDATE_PENDING, PluginStatus.DAMAGED),
            registry.plugins.value.map { it.status },
        )
        assertEquals(listOf("pa", "pd"), registry.usable().map { it.id })
    }

    @Test fun `enabling clears no responde`() {
        install("pc", "C") { copy(unresponsive = true, enabled = false) }
        registry.setEnabled("pc", true)
        assertEquals(PluginStatus.ACTIVE, registry.find("pc")!!.status)
    }

    @Test fun `uninstall removes files and data but remembers the name`() {
        install("pa", "Archivo")
        File(store.dataDir("pa"), "storage.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        registry.uninstall("pa")
        assertNull(registry.find("pa"))
        assertFalse(File(tmp.root, "plugins/pa").exists())
        assertFalse(store.dataDir("pa").exists())
        assertEquals("Archivo", registry.nameOf("pa"))
    }

    @Test fun `access and the message the player shows`() {
        install("ok", "Bueno")
        install("off", "Apagado") { copy(enabled = false) }
        install("bad", "Roto") { copy(damaged = true) }
        install("gone", "Ido")
        registry.uninstall("gone")
        assertNull(registry.accessFor("ok").blockedMessage())
        assertEquals("Activa el plugin Apagado para ver esto", registry.accessFor("off").blockedMessage())
        assertEquals("El plugin Roto tiene archivos dañados, reinstálalo", registry.accessFor("bad").blockedMessage())
        assertEquals("Esto venía del plugin Ido, que ya no está instalado", registry.accessFor("gone").blockedMessage())
        assertTrue(registry.accessFor(null) is PluginAccess.Uninstalled)
    }

    @Test fun `a ready plugin carries the hosts the person approved, from the installed record`() {
        install("pa", "A") { copy(hosts = listOf("approved.example.com", "*.cdn.example.com")) }
        assertEquals(
            PluginAccess.Ready("A", EffectiveHosts(listOf("approved.example.com", "*.cdn.example.com"))),
            registry.accessFor("pa"),
        )
    }

    /**
     * Fix round 2, finding 4 (still open after round 1's in-memory-only counter, which the
     * re-review proved can't work: a value that lives OUTSIDE `InstalledPlugin` can never change
     * whether TWO `InstalledPlugin` VALUES compare equal, and `registry.plugins` is a
     * `MutableStateFlow` -- setting it to something `.equals()` the current value is dropped
     * silently, so downstream collectors (`pluginsChanged`, Home) never even see the assignment,
     * let alone the side-channel counter). Tests the ACTUAL property end to end with a real
     * `PluginConfigStore`/`PluginRegistry`, not the key string's format: a settings save that
     * changes ONLY the password (same server, same user, already not missing anything -- every
     * OTHER field of `InstalledPlugin` stays bit-for-bit identical) must still make the registry's
     * live `StateFlow` collector receive a genuinely NEW emission.
     */
    @Test fun `changing only the password still makes the plugins flow emit a new value`() = runTest {
        val settingsJson = JSONArray(
            listOf(
                JSONObject().put("key", "server").put("label", "Servidor").put("type", "url").put("required", true),
                JSONObject().put("key", "user").put("label", "Usuario").put("type", "text").put("required", true),
                JSONObject().put("key", "password").put("label", "Contraseña").put("type", "password").put("required", true),
            ),
        )
        val manifest = JSONObject().put("id", "pa").put("name", "A").put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).put("settings", settingsJson).toString()
        val script = "export async function search(){}".toByteArray()
        val staging = store.newStaging("pa")
        store.writeFiles(staging, manifest, "plugin.js", script, null, InstalledRecord("o/pa", "1.0.0", sha256Hex(script), listOf("example.com"), 1L))
        store.commit(staging, "pa")

        val secrets = mutableMapOf<String, String>()
        val secretStore = object : SecretStore {
            override fun get(key: String) = secrets[key]
            override fun put(key: String, value: String) { secrets[key] = value }
            override fun remove(key: String) { secrets.remove(key) }
        }
        val config = PluginConfigStore({ id -> store.dataDir(id) }, secretStore)
        val liveRegistry = PluginRegistry(store) { p -> config.setupState(p.manifest.id, p.manifest.settings) }

        config.save("pa", listOf(url("server"), text("user"), password("password")), mapOf("server" to "http://192.168.1.10:8096", "user" to "ana", "password" to "s1"))
        liveRegistry.reload()
        val settings = liveRegistry.find("pa")!!.manifest.settings

        val emitted = mutableListOf<List<InstalledPlugin>>()
        val job = launch { liveRegistry.plugins.drop(1).collect { emitted += it } }
        testScheduler.runCurrent()
        assertEquals("the collector must not see anything before a real change happens", 0, emitted.size)

        // Same server, same user -- ONLY the password differs.
        assertNull(config.save("pa", settings, mapOf("server" to "http://192.168.1.10:8096", "user" to "ana", "password" to "s2")))
        liveRegistry.reload()
        testScheduler.runCurrent()

        assertEquals("a user/password-only save must still produce a new emission", 1, emitted.size)
        assertNotNull(emitted.firstOrNull()?.firstOrNull { it.id == "pa" })
        job.cancel()
    }

    /**
     * Fix round 2, "new breakage 1": `reload()` is reachable from Main (`graph.pluginsChanged`/
     * `graph.pluginRegistry`, read directly from `viewModelFactory` initializers in
     * HomeScreen/LibraryScreen/TvHomeScreen/PlayerScreen during composition). Round 1's finding-5
     * fix made `missing()` ask the Keystore-backed secret store directly, which would have made
     * THIS reload() do Keystore IO on whatever thread calls it. Proven with a trap store that fails
     * the moment anything in this call chain reads it.
     */
    @Test fun `reload never touches the secret store, even for a plugin with a password setting`() {
        val settings = listOf(
            PluginSetting("server", "Servidor", SettingType.URL, required = true),
            PluginSetting("password", "Contraseña", SettingType.PASSWORD, required = true),
        )
        val manifest = JSONObject().put("id", "pa").put("name", "A").put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve")))
            .put("settings", JSONArray(listOf(
                JSONObject().put("key", "server").put("label", "Servidor").put("type", "url").put("required", true),
                JSONObject().put("key", "password").put("label", "Contraseña").put("type", "password").put("required", true),
            ))).toString()
        val script = "export async function search(){}".toByteArray()
        val staging = store.newStaging("pa")
        store.writeFiles(staging, manifest, "plugin.js", script, null, InstalledRecord("o/pa", "1.0.0", sha256Hex(script), listOf("example.com"), 1L))
        store.commit(staging, "pa")
        File(store.dataDir("pa"), "config.json").apply { parentFile!!.mkdirs() }
            .writeText("""{"values":{"server":"http://192.168.1.10:8096"},"secrets":["password"],"revision":3}""")

        val trap = object : SecretStore {
            override fun get(key: String): String? = throw AssertionError("reload() must never read the secret store: $key")
            override fun put(key: String, value: String) = throw AssertionError("unexpected write: $key")
            override fun remove(key: String) = throw AssertionError("unexpected remove: $key")
        }
        val trappedConfig = PluginConfigStore({ id -> store.dataDir(id) }, trap)
        val trappedRegistry = PluginRegistry(store) { p -> trappedConfig.setupState(p.manifest.id, p.manifest.settings) }

        // Reaching this line without the trap firing IS the assertion.
        trappedRegistry.reload()
        val p = trappedRegistry.find("pa")!!
        assertFalse("password IS actually set: this must read as configured, from config.json alone", p.needsSetup)
        assertEquals(3, p.configRevision)
    }

    private fun url(key: String) = PluginSetting(key, key, SettingType.URL, required = true)
    private fun text(key: String) = PluginSetting(key, key, SettingType.TEXT, required = true)
    private fun password(key: String) = PluginSetting(key, key, SettingType.PASSWORD, required = true)
}
