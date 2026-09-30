package com.arkiv.player.data.plugin

import com.arkiv.player.ui.player.resolvingText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A converted Nuvio scraper chains several page fetches and hoster extractions inside one
 * `getStreams` (PelisPlusHD tries its titles one by one): 20 s is too short for it. Nuvio-origin
 * plugins ([InstalledRecord.nuvioScraperId]) get [PluginContentSource.NUVIO_RESOLVE_TIMEOUT_MS];
 * every other plugin keeps [PluginContentSource.RESOLVE_TIMEOUT_MS].
 */
class NuvioResolveTimeoutTest {
    private val manifest = PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "a", "", listOf("example.com"), setOf("search", "resolve"), null, null)
    private val record = InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L)
    private val movie = PluginRef("demo", "m1", PluginRef.MOVIE, "R1").encode()

    private fun resolveTimeout(record: InstalledRecord): Pair<Long, PluginContentSource> {
        var asked = 0L
        val source = PluginContentSource(InstalledPlugin(manifest, record, null), { _, fn, _, t -> if (fn == "resolve") asked = t; """{"url":"https://example.com/v.mp4"}""" }, log = {})
        runBlocking { source.resolve(movie) }
        return asked to source
    }

    @Test fun `a Nuvio-origin plugin's resolve gets 75 s, any other plugin keeps 20 s`() {
        val (nuvio, nuvioSource) = resolveTimeout(record.copy(nuvioRepo = "D3PR3D4DOR/pelisplus-latino-nuvio", nuvioScraperId = "pelisplushd"))
        assertEquals(75_000L, nuvio)
        val (plain, plainSource) = resolveTimeout(record)
        assertEquals(20_000L, plain)
        // The search backstop covers a search queued behind one whole resolve of the same plugin.
        assertTrue(nuvioSource.searchTimeoutMs!! >= PluginContentSource.SEARCH_TIMEOUT_MS + 75_000L)
        assertEquals(
            PluginContentSource.SEARCH_TIMEOUT_MS + 20_000L + PluginContentSource.SEARCH_BACKSTOP_GRACE_MS,
            plainSource.searchTimeoutMs,
        )
    }

    @Test fun `the download queue's resolve gets twice the player's limit`() {
        fun downloadTimeout(record: InstalledRecord): Long {
            var asked = 0L
            val source = PluginContentSource(InstalledPlugin(manifest, record, null), { _, fn, _, t -> if (fn == "resolve") asked = t; """{"url":"https://example.com/v.mp4"}""" }, log = {})
            runBlocking { kotlinx.coroutines.withContext(BackgroundPluginCall + PluginDownloadCall) { source.resolve(movie) } }
            return asked
        }
        assertEquals(150_000L, downloadTimeout(record.copy(nuvioRepo = "D3PR3D4DOR/pelisplus-latino-nuvio", nuvioScraperId = "pelisplushd")))
        assertEquals(40_000L, downloadTimeout(record))
    }

    @Test fun `the player keeps the person informed through a long resolve`() {
        assertEquals("Resolviendo fuente PelisPlusHD…", resolvingText("PelisPlusHD", 0L))
        assertEquals("Resolviendo fuente PelisPlusHD…", resolvingText("PelisPlusHD", 4_999L))
        assertEquals("Resolviendo fuente PelisPlusHD… buscando enlaces (5 s)", resolvingText("PelisPlusHD", 5_000L))
        assertEquals("Resolviendo fuente PelisPlusHD… buscando enlaces (38 s)", resolvingText("PelisPlusHD", 38_700L))
    }
}
