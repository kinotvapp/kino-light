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

    /** Fix round 2, finding 4's building block: [PluginSetupState.revision] must move on EVERY
     *  successful save, since it's what makes `InstalledPlugin` (and so the registry's `StateFlow`)
     *  visibly change even when a save touches only the password. */
    @Test fun `save bumps the revision on every successful save`() {
        assertNull(store.save("jf", settings, full))
        val r1 = store.setupState("jf", settings).revision
        assertNull(store.save("jf", settings, full + ("password" to "different")))
        val r2 = store.setupState("jf", settings).revision
        assertTrue("revision must move: was $r1, now $r2", r2 > r1)
        // A refused save must not bump it.
        store.save("jf", settings, full + ("password" to "  "))
        assertEquals(r2, store.setupState("jf", settings).revision)
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

    /**
     * Fix round 2, new breakage 1: round 1's finding-5 fix made `missing()` ask the [SecretStore]
     * directly, which meant `PluginRegistry.reload()` -- reachable from Main via
     * `graph.pluginsChanged`/`graph.pluginRegistry`, read directly from `viewModelFactory`
     * initializers during composition (HomeScreen/LibraryScreen/TvHomeScreen/PlayerScreen) -- could
     * end up opening the Keystore on the UI thread. Proven here with a trap store that fails the
     * test the instant `.get()` is called from `missing()`/`setupState()`: it must never happen,
     * no matter what the file says.
     */
    @Test fun `missing and setupState never touch the secret store, regardless of what config json lists`() {
        val trap = object : SecretStore {
            override fun get(key: String): String? = throw AssertionError("missing()/setupState() must never read the secret store: $key")
            override fun put(key: String, value: String) = throw AssertionError("unexpected write: $key")
            override fun remove(key: String) = throw AssertionError("unexpected remove: $key")
        }
        val trapped = PluginConfigStore({ id -> File(tmp.root, id) }, trap)
        File(tmp.root, "jf").mkdirs()
        File(tmp.root, "jf/config.json").writeText(
            """{"values":{"server":"http://192.168.1.10:8096","user":"ana"},"secrets":["password"],"revision":1}""",
        )
        // Reaching this line without the trap firing IS the assertion; these just confirm the
        // (Keystore-free) answer is still correct.
        assertEquals(emptyList<PluginSetting>(), trapped.missing("jf", settings))
        assertTrue(trapped.setupState("jf", settings).missing.isEmpty())
    }

    /**
     * Fix round 2, finding 5 (the real fix, moved out of the reload()-reachable path above): a
     * password `config.json` lists as set but the [SecretStore] can no longer actually produce (a
     * Keystore reset, a restored backup -- `EncryptedSecretStore`'s KDoc) is caught here, off the
     * hot path, by rewriting `config.json`'s own "secrets" list to drop it.
     */
    @Test fun `reconcileSecrets drops a password the secret store can no longer produce`() {
        assertNull(store.save("jf", settings, full))
        assertEquals(emptyList<PluginSetting>(), store.missing("jf", settings))
        secrets.map.remove("plugin.jf.password")
        // Not caught by the cheap check alone (proven above) -- reconciliation is what fixes it.
        assertEquals(emptyList<PluginSetting>(), store.missing("jf", settings))
        store.reconcileSecrets("jf", settings)
        assertEquals(listOf("password"), store.missing("jf", settings).map { it.key })
        assertEquals(listOf("password"), store.setupState("jf", settings).missing)
        assertFalse(File(tmp.root, "jf/config.json").readText().contains("\"password\""))
    }

    @Test fun `reconcileSecrets is a no-op, no write, when nothing has actually changed`() {
        assertNull(store.save("jf", settings, full))
        val before = File(tmp.root, "jf/config.json").readText()
        store.reconcileSecrets("jf", settings)
        assertEquals(before, File(tmp.root, "jf/config.json").readText())
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

    /**
     * Final review I2: AppGraph reconciles every plugin inside the warm-up that pre-builds Xuper's
     * lazies off Main, and before every plugin's update check. A secret store that throws (Keystore
     * or Tink on a cheap box) for one plugin must neither escape to that caller nor stop the others.
     */
    @Test fun `reconcileAllSecrets survives a throwing secret store and still reconciles the other plugins`() {
        val flaky = object : SecretStore {
            val map = LinkedHashMap<String, String>()
            override fun get(key: String): String? = if (key.startsWith("plugin.bad.")) throw SecurityException("keystore") else map[key]
            override fun put(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
        }
        val s = PluginConfigStore({ id -> File(tmp.root, id) }, flaky)
        assertNull(s.save("bad", settings, full))
        assertNull(s.save("good", settings, full))
        flaky.map.remove("plugin.good.password") // good's Keystore entry is gone: reconcile must notice
        val failed = mutableListOf<String>()
        // Returning normally at all (no throw) is what lets the caller's own work go on.
        s.reconcileAllSecrets(listOf("bad" to settings, "good" to settings)) { id, e ->
            assertTrue(e is SecurityException)
            failed += id
        }
        assertEquals(listOf("bad"), failed)
        assertEquals("the plugin after the failing one is still reconciled", listOf("password"), s.missing("good", settings).map { it.key })
        assertEquals("the failing plugin's file is left as it was", emptyList<PluginSetting>(), s.missing("bad", settings))
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
