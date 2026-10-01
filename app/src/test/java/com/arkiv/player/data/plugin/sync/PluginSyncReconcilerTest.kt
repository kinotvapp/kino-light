package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.plugin.InstallException
import com.arkiv.player.data.plugin.NuvioPluginConverter
import com.arkiv.player.data.plugin.PluginFailure
import com.arkiv.player.data.plugin.UpdateOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSyncReconcilerTest {
    private val dao = FakePluginInstallDao()
    private val host = FakeSyncHost()
    private val reports = mutableListOf<PluginFailure>()

    private fun TestScope.reconciler() = PluginSyncReconciler(dao, host, backgroundScope, report = { reports += it }, log = {})

    @Test fun `a plugin from another device installs silently when it asks for nothing more`() = runTest {
        val r = reconciler()
        dao.save(row(reach = PluginReach(hosts = listOf("archive.org"), anyVideoHost = true), settings = """{"quality":"720"}"""))
        host.previews["archive"] = preview()
        r.onIncoming("archive")
        runCurrent()
        assertEquals(listOf("install:archive", "settings:archive"), host.actions)
        assertTrue(host.grants.single().second.anyVideoHost)
        assertEquals(mapOf("quality" to "720"), host.settings["archive"])
        assertNull(r.statuses.value["archive"])
    }

    @Test fun `asking for more than was approved waits for consent here`() = runTest {
        val r = reconciler()
        dao.save(row(reach = PluginReach(hosts = listOf("archive.org"))))
        host.previews["archive"] = preview(manifest(hosts = listOf("archive.org", "new.example.com")))
        r.onIncoming("archive")
        runCurrent()
        assertTrue(host.actions.isEmpty())
        assertEquals(PeerOfferStatus.NEEDS_CONSENT, r.statuses.value["archive"])
    }

    @Test fun `a disabled plugin arrives disabled`() = runTest {
        val r = reconciler()
        dao.save(row(enabled = false))
        host.previews["archive"] = preview()
        r.onIncoming("archive")
        runCurrent()
        assertEquals(listOf("install:archive", "enabled:archive=false"), host.actions)
    }

    @Test fun `a preview that names another plugin or address is refused and reported without the address`() = runTest {
        val r = reconciler()
        dao.save(row())
        host.previews["archive"] = preview(manifest(id = "other"))
        r.onIncoming("archive")
        runCurrent()
        assertTrue(host.actions.isEmpty())
        assertEquals(PeerOfferStatus.FAILED, r.statuses.value["archive"])
        val event = reports.single()
        assertEquals("sync:mismatch", event.function)
        assertTrue("kinotvapp/kino-plugin-archive" in event.privateText)
        assertNull(event.raw)
    }

    @Test fun `a failed fetch is marked failed and retried later`() = runTest {
        val r = reconciler()
        dao.save(row())
        host.previewError = InstallException("No hay conexión a internet")
        r.onIncoming("archive")
        runCurrent()
        assertEquals(PeerOfferStatus.FAILED, r.statuses.value["archive"])
        assertEquals("sync:preview", reports.single().function)
        host.previewError = null
        host.previews["archive"] = preview()
        r.retryPending()
        runCurrent()
        assertEquals(listOf("install:archive"), host.actions)
    }

    @Test fun `a nuvio failure from a private repo never names it`() = runTest {
        val r = reconciler()
        val repo = "someone/private-scrapers"
        val id = NuvioPluginConverter.idFor("s", repo)
        dao.save(row(id = id, address = repo, nuvioScraperId = "s"))
        host.previewError = InstallException("x")
        r.onIncoming(id)
        runCurrent()
        assertEquals("nuvio-import", reports.single().pluginId)
    }

    @Test fun `a tombstone uninstalls here but never xuper`() = runTest {
        val r = reconciler()
        host.plugins["archive"] = installed()
        host.plugins["xuper"] = installed(id = "xuper", address = "kinotvapp/kino-plugin-xuper")
        dao.save(row(deleted = true))
        dao.save(row(id = "xuper", address = "xuper-plugin/kino-plugin-xuper", deleted = true))
        r.onIncoming("archive")
        r.onIncoming("xuper")
        runCurrent()
        assertEquals(listOf("uninstall:archive"), host.actions)
        assertTrue("xuper" in host.plugins)
    }

    @Test fun `a tombstone for a same-id plugin from another address leaves it alone`() = runTest {
        val r = reconciler()
        host.plugins["archive"] = installed(address = "someone/archive-fork")
        dao.save(row(deleted = true))
        r.onIncoming("archive")
        runCurrent()
        assertTrue(host.actions.isEmpty())
    }

    @Test fun `the switch is shared, but xuper is never switched off from another device`() = runTest {
        val r = reconciler()
        host.plugins["archive"] = installed()
        host.plugins["xuper"] = installed(id = "xuper", address = "xuper-plugin/kino-plugin-xuper")
        dao.save(row(enabled = false))
        dao.save(row(id = "xuper", address = "xuper-plugin/kino-plugin-xuper", enabled = false))
        r.onIncoming("archive")
        r.onIncoming("xuper")
        runCurrent()
        assertEquals(listOf("enabled:archive=false"), host.actions)
        assertTrue(host.plugins.getValue("xuper").record.enabled)

        dao.save(row(id = "xuper", address = "xuper-plugin/kino-plugin-xuper", enabled = true))
        host.plugins["xuper"] = host.plugins.getValue("xuper").let { it.copy(record = it.record.copy(enabled = false)) }
        r.onIncoming("xuper")
        runCurrent()
        assertTrue("switching it ON is fine", host.plugins.getValue("xuper").record.enabled)
    }

    @Test fun `a newer version on the other device checks for an update once`() = runTest {
        val r = reconciler()
        host.plugins["archive"] = installed(version = "1.0.0")
        dao.save(row(version = "1.1.0"))
        r.onIncoming("archive")
        r.onIncoming("archive")
        runCurrent()
        assertEquals(listOf("check:archive"), host.actions)
    }

    @Test fun `an update that needs approval applies only within what was approved there`() = runTest {
        val r = reconciler()
        host.plugins["archive"] = installed(version = "1.0.0")
        dao.save(row(version = "1.1.0", reach = PluginReach(hosts = listOf("archive.org", "new.example.com"))))
        host.updateOutcome = UpdateOutcome.NeedsApproval(preview(manifest(hosts = listOf("archive.org", "new.example.com"), version = "1.1.0")))
        r.onIncoming("archive")
        runCurrent()
        assertEquals(listOf("check:archive", "install:archive"), host.actions)

        host.actions.clear()
        dao.save(row(version = "1.2.0", reach = PluginReach(hosts = listOf("archive.org"))))
        host.updateOutcome = UpdateOutcome.NeedsApproval(preview(manifest(hosts = listOf("archive.org", "more.example.com"), version = "1.2.0")))
        r.onIncoming("archive")
        runCurrent()
        assertEquals(listOf("check:archive"), host.actions)
    }

    @Test fun `an older version on the other device does nothing`() = runTest {
        val r = reconciler()
        host.plugins["archive"] = installed(version = "2.0.0")
        dao.save(row(version = "1.0.0"))
        r.onIncoming("archive")
        runCurrent()
        assertFalse(host.actions.any { it.startsWith("check") })
    }

    @Test fun `the list offers what is not installed here`() {
        val offers = peerOffers(
            listOf(row(id = "b"), row(id = "a"), row(id = "c", deleted = true), row(id = "d")),
            installedIds = setOf("d"),
            statuses = mapOf("b" to PeerOfferStatus.NEEDS_CONSENT),
        )
        assertEquals(listOf("a" to PeerOfferStatus.WAITING, "b" to PeerOfferStatus.NEEDS_CONSENT), offers.map { it.id to it.status })
    }
}
