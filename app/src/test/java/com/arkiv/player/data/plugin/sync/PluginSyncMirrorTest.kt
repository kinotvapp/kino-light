package com.arkiv.player.data.plugin.sync

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSyncMirrorTest {
    private val dao = FakePluginInstallDao()
    private val host = FakeSyncHost()
    private var now = 1_000L

    private fun TestScope.mirror() = PluginSyncMirror(dao, host, backgroundScope, clock = { now }, log = {})

    @Test fun `an install writes the whole row from this device`() = runTest {
        val m = mirror()
        host.plugins["archive"] = installed(hosts = listOf("archive.org", "ia.example.com"))
        host.settings["archive"] = mapOf("quality" to "720")
        m.recordInstall("archive")
        runCurrent()
        val r = dao.rows.getValue("archive")
        assertEquals("kinotvapp/kino-plugin-archive", r.address)
        assertEquals(listOf("archive.org", "ia.example.com"), PluginReach.fromJson(JSONObject(r.approvedJson)).hosts)
        assertEquals("720", JSONObject(r.settingsJson).getString("quality"))
        assertEquals(1_000L, r.updatedAt)
        assertFalse(r.deleted)
    }

    @Test fun `an install keeps what the row already approved for the same plugin`() = runTest {
        val m = mirror()
        dao.save(row(reach = PluginReach(hosts = listOf("archive.org", "peer.example.com"), anyVideoHost = true), settings = """{"user":"ana"}"""))
        host.plugins["archive"] = installed()
        m.recordInstall("archive")
        runCurrent()
        val reach = PluginReach.fromJson(JSONObject(dao.rows.getValue("archive").approvedJson))
        assertEquals(listOf("archive.org", "peer.example.com"), reach.hosts)
        assertTrue(reach.anyVideoHost)
        assertEquals("ana", JSONObject(dao.rows.getValue("archive").settingsJson).getString("user"))
    }

    @Test fun `a switch changes only the switch and moves the clock past the old one`() = runTest {
        val m = mirror()
        dao.save(row(updatedAt = 5_000))
        host.plugins["archive"] = installed(enabled = false)
        m.recordEnabled("archive")
        runCurrent()
        val r = dao.rows.getValue("archive")
        assertFalse(r.enabled)
        assertEquals(5_001L, r.updatedAt)
    }

    @Test fun `nothing changed means nothing written`() = runTest {
        val m = mirror()
        host.plugins["archive"] = installed()
        m.recordInstall("archive")
        runCurrent()
        now = 2_000L
        m.recordEnabled("archive")
        m.recordInstall("archive")
        m.recordApprovals("archive")
        m.recordSettings("archive")
        runCurrent()
        assertEquals(1_000L, dao.rows.getValue("archive").updatedAt)
    }

    @Test fun `an uninstall writes a tombstone, even for a plugin never recorded`() = runTest {
        val m = mirror()
        m.recordUninstall("archive", installed())
        runCurrent()
        assertTrue(dao.rows.getValue("archive").deleted)
    }

    @Test fun `a switch never brings back a plugin removed on another device`() = runTest {
        val m = mirror()
        dao.save(row(id = "xuper", address = "xuper-plugin/kino-plugin-xuper", deleted = true, updatedAt = 9))
        host.plugins["xuper"] = installed(id = "xuper", address = "xuper-plugin/kino-plugin-xuper", enabled = false)
        m.recordEnabled("xuper")
        m.backfill()
        runCurrent()
        assertTrue(dao.rows.getValue("xuper").deleted)
        assertEquals(9L, dao.rows.getValue("xuper").updatedAt)
    }

    @Test fun `approvals join hosts and take this device's refusals and broad permission`() = runTest {
        val m = mirror()
        dao.save(row(reach = PluginReach(hosts = listOf("archive.org", "peer.example.com"), rejectedHosts = listOf("old.example.com"), anyVideoHost = true)))
        host.plugins["archive"] = installed(hosts = listOf("archive.org", "mine.example.com")).let {
            it.copy(record = it.record.copy(rejectedHosts = emptyList(), anyVideoHost = false))
        }
        m.recordApprovals("archive")
        runCurrent()
        val reach = PluginReach.fromJson(JSONObject(dao.rows.getValue("archive").approvedJson))
        assertEquals(setOf("archive.org", "peer.example.com", "mine.example.com"), reach.hosts.toSet())
        assertEquals(emptyList<String>(), reach.rejectedHosts)
        assertFalse(reach.anyVideoHost)
    }

    @Test fun `backfill adds rows for plugins never recorded and newer versions only`() = runTest {
        val m = mirror()
        dao.save(row(id = "old", address = "a/old", version = "2.0.0", updatedAt = 3))
        dao.save(row(id = "upd", address = "a/upd", version = "1.0.0", updatedAt = 3))
        host.plugins["new"] = installed(id = "new", address = "a/new")
        host.plugins["old"] = installed(id = "old", address = "a/old", version = "1.0.0")
        host.plugins["upd"] = installed(id = "upd", address = "a/upd", version = "1.1.0")
        m.backfill()
        runCurrent()
        assertEquals("1.0.0", dao.rows.getValue("new").version)
        assertEquals("never an older version", 3L, dao.rows.getValue("old").updatedAt)
        assertEquals("1.1.0", dao.rows.getValue("upd").version)
    }

    @Test fun `settings saved here replace the row's shared settings`() = runTest {
        val m = mirror()
        dao.save(row(settings = """{"user":"ana"}"""))
        host.plugins["archive"] = installed()
        host.settings["archive"] = mapOf("user" to "beto")
        m.recordSettings("archive")
        runCurrent()
        assertEquals("beto", JSONObject(dao.rows.getValue("archive").settingsJson).getString("user"))
        assertNull(dao.rows["other"])
    }

    @Test fun `saving a plugin's settings stamps its passwords for sealing, only when it has password settings`() = runTest {
        val stamps = object : SecretStamps {
            val map = HashMap<String, Long>()
            override fun get(id: String) = map[id] ?: 0L
            override fun set(id: String, stamp: Long) { map[id] = stamp }
        }
        val m = PluginSyncMirror(dao, host, backgroundScope, clock = { now }, log = {}, secretStamps = stamps)
        val withPassword = installed("srv", address = "someone/server").let {
            it.copy(manifest = it.manifest.copy(settings = listOf(com.arkiv.player.data.plugin.PluginSetting("password", "Contraseña", com.arkiv.player.data.plugin.SettingType.PASSWORD))))
        }
        host.plugins["srv"] = withPassword
        host.plugins["archive"] = installed()
        m.recordSettings("srv")
        m.recordSettings("archive")
        runCurrent()
        assertEquals(1_000L, dao.rows.getValue("srv").secretsAt)
        assertEquals(1_000L, stamps.get("srv"))
        assertEquals(0L, dao.rows.getValue("archive").secretsAt)
        // A second save in the same millisecond still moves the clock.
        m.recordSettings("srv")
        runCurrent()
        assertEquals(1_001L, dao.rows.getValue("srv").secretsAt)
        // A re-install by the person keeps the stamp.
        m.recordInstall("srv")
        runCurrent()
        assertEquals(1_001L, dao.rows.getValue("srv").secretsAt)
    }
}
