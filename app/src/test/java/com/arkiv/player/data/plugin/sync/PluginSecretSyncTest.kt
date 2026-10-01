package com.arkiv.player.data.plugin.sync

import com.arkiv.player.companion.PairingCrypto
import com.arkiv.player.companion.PeerKeyStore
import com.arkiv.player.data.plugin.PluginConfigStore
import com.arkiv.player.data.plugin.PluginSetting
import com.arkiv.player.data.plugin.SecretStore
import com.arkiv.player.data.plugin.SettingType
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class Keys : PeerKeyStore {
    val map = HashMap<String, ByteArray>()
    override fun get(deviceId: String) = map[deviceId]
    override fun put(deviceId: String, key: ByteArray) { map[deviceId] = key }
    override fun remove(deviceId: String) { map.remove(deviceId) }
}

private class Stamps : SecretStamps {
    val map = HashMap<String, Long>()
    override fun get(id: String) = map[id] ?: 0L
    override fun set(id: String, stamp: Long) { map[id] = maxOf(stamp, get(id)) }
}

class PluginSecretSyncTest {
    private val key = ByteArray(32) { (it * 3).toByte() }

    // The phone: has the plugin with a password the person saved at 50.
    private val phoneDao = FakePluginInstallDao()
    private val phoneHost = FakeSyncHost()
    private val phoneKeys = Keys().apply { put("tv", key) }
    private val phone = PluginSecretSync(phoneDao, phoneKeys, phoneHost, Stamps()) {}

    // The TV: knows the plugin's row, holds the same key for the phone.
    private val tvDao = FakePluginInstallDao()
    private val tvHost = FakeSyncHost()
    private val tvKeys = Keys().apply { put("phone", key) }
    private val tvStamps = Stamps()
    private val tv = PluginSecretSync(tvDao, tvKeys, tvHost, tvStamps) {}

    private suspend fun setUp() {
        phoneHost.plugins["srv"] = installed("srv", address = "kinotvapp/kino-plugin-own-server")
        phoneHost.secrets["srv"] = mapOf("password" to "s3cr3t")
        phoneDao.save(row("srv", address = "kinotvapp/kino-plugin-own-server", updatedAt = 50).copy(secretsAt = 50))
        tvDao.save(row("srv", address = "kinotvapp/kino-plugin-own-server", updatedAt = 50).copy(secretsAt = 50))
    }

    @Test fun `a password travels sealed and arrives opened on the other device`() = runTest {
        setUp()
        val rows = phone.changedSince(0, "tv")
        assertEquals(1, rows.size)
        assertFalse(rows.single().toString().contains("s3cr3t"))
        assertEquals(50L, rows.single().getLong("updatedAt"))
        assertTrue(tv.apply(rows.single(), "phone"))
        assertEquals(mapOf("password" to "s3cr3t"), tvHost.secrets["srv"])
        assertEquals(50L, tvStamps.get("srv"))
    }

    @Test fun `nothing is sealed for a peer without a key, nor applied from one`() = runTest {
        setUp()
        assertFalse(phone.ready("other-tv"))
        assertTrue(phone.changedSince(0, "other-tv").isEmpty())
        val rows = phone.changedSince(0, "tv")
        tvKeys.map.clear()
        assertFalse(tv.apply(rows.single(), "phone"))
        assertNull(tvHost.secrets["srv"])
    }

    @Test fun `the same passwords coming back, or older ones, are not applied again`() = runTest {
        setUp()
        val rows = phone.changedSince(0, "tv")
        tvStamps.set("srv", 50)
        assertTrue(tv.apply(rows.single(), "phone"))
        assertNull(tvHost.secrets["srv"])
    }

    @Test fun `a value sealed under another key, or moved to another plugin or setting, is refused`() = runTest {
        setUp()
        val row = phone.changedSince(0, "tv").single()
        // Another key on the TV side (the pairing was redone): not applied, the cursor must stay.
        tvKeys.put("phone", ByteArray(32) { 1 })
        assertFalse(tv.apply(row, "phone"))
        tvKeys.put("phone", key)
        // The sealed password moved to another setting: refused.
        val sealed = row.getJSONObject("values").getString("password")
        val moved = JSONObject(row.toString()).put("values", JSONObject().put("user", sealed))
        assertFalse(tv.apply(moved, "phone"))
        // ...or to another plugin the TV has.
        tvDao.save(row("jf", address = "someone/jellyfin", updatedAt = 50))
        val otherPlugin = JSONObject(row.toString()).put("id", "jf")
        assertFalse(tv.apply(otherPlugin, "phone"))
        assertNull(tvHost.secrets["srv"])
        assertNull(tvHost.secrets["jf"])
    }

    @Test fun `a plugin the receiver has no live row for, or a reserved id, gets nothing`() = runTest {
        setUp()
        val row = phone.changedSince(0, "tv").single()
        tvDao.save(tvDao.get("srv")!!.copy(deleted = true, updatedAt = 60))
        assertTrue(tv.apply(row, "phone"))
        assertNull(tvHost.secrets["srv"])
        assertTrue(tv.apply(JSONObject(row.toString()).put("id", "xuper/../x"), "phone"))
        assertTrue(tvHost.secrets.isEmpty())
    }

    @Test fun `only rows whose passwords changed after the cursor are sealed`() = runTest {
        setUp()
        assertTrue(phone.changedSince(50, "tv").isEmpty())
        phoneHost.secrets["srv"] = emptyMap()
        assertTrue(phone.changedSince(0, "tv").isEmpty()) // nothing set: nothing sent
    }

    @Test fun `the sealed value is bound to the plugin and setting`() {
        val sealed = PairingCrypto.seal(key, "x", PluginSecretSync.aad("srv", "password"))
        assertEquals("x", PairingCrypto.open(key, sealed, PluginSecretSync.aad("srv", "password")))
        assertNull(PairingCrypto.open(key, sealed, PluginSecretSync.aad("srv", "token")))
    }
}

class PluginConfigSecretsTest {
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
        PluginSetting("password", "Contraseña", SettingType.PASSWORD, required = true),
    )

    @Test fun `the person's passwords are read for sealing, only the ones set`() {
        assertTrue(store.secretValues("own", settings).isEmpty())
        store.save("own", settings, mapOf("server" to "http://192.168.1.10:8096", "password" to "s3cr3t"))
        assertEquals(mapOf("password" to "s3cr3t"), store.secretValues("own", settings))
    }

    @Test fun `a received password is stored, listed as set, and never clears one`() {
        assertEquals(listOf("server", "password"), store.missing("own", settings).map { it.key })
        assertTrue(store.applySecrets("own", settings, mapOf("password" to "s3cr3t", "undeclared" to "x")))
        assertEquals("s3cr3t", store.read("own", settings).values["password"])
        assertEquals(listOf("server"), store.missing("own", settings).map { it.key })
        assertFalse(store.applySecrets("own", settings, mapOf("password" to "s3cr3t"))) // same: no change
        assertFalse(store.applySecrets("own", settings, emptyMap()))
        assertEquals("s3cr3t", store.read("own", settings).values["password"])
        assertNull(secrets.map["plugin.own.undeclared"])
    }

    @Test fun `passwords received before the install are adopted once it is installed`() {
        store.storeSecrets("own", mapOf("password" to "s3cr3t", "bad key!" to "x"))
        assertEquals(setOf("plugin.own.password"), secrets.map.keys)
        assertTrue(store.adoptSecrets("own", settings))
        assertEquals("s3cr3t", store.read("own", settings).values["password"])
        assertFalse(store.adoptSecrets("own", settings))
    }
}
