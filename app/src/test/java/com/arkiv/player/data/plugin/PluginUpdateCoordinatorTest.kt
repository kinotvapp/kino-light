package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class PluginUpdateCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun install(store: PluginStore, id: String, address: String, nuvioRepo: String?, nuvioScraperId: String?) {
        val manifest = org.json.JSONObject().put("id", id).put("name", id).put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", org.json.JSONArray(listOf("example.com")))
            .put("capabilities", org.json.JSONArray(listOf("search", "resolve"))).toString()
        val script = "export async function search(){} export async function resolve(){}".toByteArray()
        val staging = store.newStaging(id)
        store.writeFiles(staging, manifest, "plugin.js", script, null,
            InstalledRecord(address, "1.0.0", sha256Hex(script), listOf("example.com"), 1L, nuvioRepo = nuvioRepo, nuvioScraperId = nuvioScraperId))
        store.commit(staging, id)
    }

    @Test fun `routes a Nuvio-origin record to NuvioPluginInstaller, everything else to PluginInstaller`() = runBlocking {
        val store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        install(store, "normal", "owner/normal", nuvioRepo = null, nuvioScraperId = null)
        install(store, "fromnuvio", "owner/nuvio-repo", nuvioRepo = "owner/nuvio-repo", nuvioScraperId = "fakesrc")

        var kinoFetchCalls = 0
        val kino = PluginInstaller(store, PluginFetcher { _, _ -> kinoFetchCalls++; throw FileNotFoundException("no update host in this test") }, probe = { _, _ -> emptySet() })
        var nuvioFetchCalls = 0
        val nuvio = NuvioPluginInstaller(kino, PluginFetcher { _, _ -> nuvioFetchCalls++; throw FileNotFoundException("no update host in this test") })

        val coordinator = PluginUpdateCoordinator(store, kino, nuvio)
        coordinator.checkUpdate("normal")
        assertEquals(1, kinoFetchCalls)
        assertEquals(0, nuvioFetchCalls)
        coordinator.checkUpdate("fromnuvio")
        assertEquals(1, nuvioFetchCalls)
    }

    /**
     * [NuvioPluginInstaller.checkUpdate] never touches `lastUpdateCheckAt` itself on `UpToDate` or
     * `Failed` (unlike [PluginInstaller.checkUpdate]'s own `touch()`, which always does) -- Task 5
     * review note. Without the coordinator patching it after the fact, a Nuvio-origin plugin that's
     * already current or that keeps failing to reach GitHub would be re-checked (a full network
     * re-conversion, not a cheap re-fetch) on EVERY [PluginUpdateCoordinator.checkDueUpdates] cycle,
     * never respecting `maxAgeMs`, unlike a normal plugin right next to it. Proven across BOTH
     * origins in one pass, using the same "the fetcher throws" setup as the routing test above --
     * for the Nuvio one this fails at `previewScraper`'s manifest fetch, surfacing as `Failed`.
     */
    @Test fun `checkDueUpdates checks each plugin, of either origin, at most once per maxAgeMs`() = runBlocking {
        val store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        install(store, "normal", "owner/normal", nuvioRepo = null, nuvioScraperId = null)
        install(store, "fromnuvio", "owner/nuvio-repo", nuvioRepo = "owner/nuvio-repo", nuvioScraperId = "fakesrc")

        var now = 1_000L
        val maxAgeMs = 1_000L
        var kinoFetchCalls = 0
        // Shares `{ now }` with the coordinator: PluginInstaller.checkUpdate's own touch() patches
        // lastUpdateCheckAt using ITS OWN clock, not the coordinator's -- in production both are
        // System::currentTimeMillis so they naturally agree, but a test using a fake clock for one
        // and real wall-clock time for the other would make "due" comparisons meaningless.
        val kino = PluginInstaller(store, PluginFetcher { _, _ -> kinoFetchCalls++; throw FileNotFoundException("offline") }, probe = { _, _ -> emptySet() }, clock = { now })
        var nuvioFetchCalls = 0
        val nuvio = NuvioPluginInstaller(kino, PluginFetcher { _, _ -> nuvioFetchCalls++; throw FileNotFoundException("offline") })

        val coordinator = PluginUpdateCoordinator(store, kino, nuvio, clock = { now })

        // lastUpdateCheckAt starts at 0 (the fixture never sets it): both plugins are due immediately.
        val first = coordinator.checkDueUpdates(maxAgeMs)
        assertEquals(2, first.size)
        assertEquals(1, kinoFetchCalls)
        assertEquals(1, nuvioFetchCalls)

        // Same `now`: neither is due yet. If the coordinator didn't patch lastUpdateCheckAt on the
        // Nuvio path, nuvioFetchCalls would climb to 2 here even though kinoFetchCalls stays at 1.
        val second = coordinator.checkDueUpdates(maxAgeMs)
        assertEquals(emptyList<Pair<String, UpdateOutcome>>(), second)
        assertEquals(1, kinoFetchCalls)
        assertEquals(1, nuvioFetchCalls)

        // maxAgeMs elapses: both are due again.
        now += maxAgeMs
        val third = coordinator.checkDueUpdates(maxAgeMs)
        assertEquals(2, third.size)
        assertEquals(2, kinoFetchCalls)
        assertEquals(2, nuvioFetchCalls)
    }
}
