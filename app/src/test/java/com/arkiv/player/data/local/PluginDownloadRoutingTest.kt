package com.arkiv.player.data.local

import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SearchEvent
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginRegistry
import com.arkiv.player.data.plugin.PluginSetupState
import com.arkiv.player.data.plugin.PluginStore
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.data.plugin.sha256Hex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A plugin that declares the `download` capability (apiVersion 2) and is usable gets the generic
 * plugin download strategy; any other plugin gets none; the recognized Xuper install keeps its own
 * privileged key. Decided through a REAL [PluginRegistry] over a temp [PluginStore], against the key
 * set `AppGraph.downloadStrategies` really has.
 */
class PluginDownloadRoutingTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: PluginStore
    private lateinit var registry: PluginRegistry
    private var missingSettings: Map<String, List<String>> = emptyMap()

    /** `AppGraph.downloadStrategies`' keys. */
    private val strategies = setOf("magis", "ditu", DownloadSource.XUPER, DownloadSource.PLUGIN_DOWNLOAD)

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        registry = PluginRegistry(store) { stored -> PluginSetupState(missing = missingSettings[stored.manifest.id].orEmpty()) }
    }

    private fun install(id: String, address: String = "someone/kino-plugin-$id", apiVersion: Int = 2, capabilities: List<String> = listOf("search", "resolve", "download"), enabled: Boolean = true) {
        val manifest = JSONObject().put("id", id).put("name", id).put("version", "1.0.0").put("apiVersion", apiVersion)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(capabilities)).toString()
        val script = "export async function resolve(){}".toByteArray()
        val staging = store.newStaging(id)
        store.writeFiles(staging, manifest, "plugin.js", script, null,
            InstalledRecord(address, "1.0.0", sha256Hex(script), listOf("example.com"), 1L, enabled = enabled, capabilities = capabilities))
        store.commit(staging, id)
        registry.reload()
    }

    private val demoEpisode = "plugin:demo:m1::0"
    private val plainEpisode = "plugin:plain:m1::0"
    private val xuperEpisode = "plugin:xuper:26A13B36463F46D48002E304FB909D1C::0"

    @Test fun `a usable plugin that declares download routes to the generic plugin strategy`() {
        install("demo")
        install("plain", capabilities = listOf("search", "resolve"))

        assertTrue(registry.offersDownloads("demo"))
        assertEquals(DownloadSource.PLUGIN_DOWNLOAD, DownloadSource.sourceFor(demoEpisode, registry::isXuper, registry::offersDownloads))
        assertTrue(DownloadSource.canDownload(demoEpisode, strategies, registry::isXuper, registry::offersDownloads))

        assertFalse(registry.offersDownloads("plain"))
        assertEquals("plugin", DownloadSource.sourceFor(plainEpisode, registry::isXuper, registry::offersDownloads))
        assertFalse(DownloadSource.canDownload(plainEpisode, strategies, registry::isXuper, registry::offersDownloads))
    }

    @Test fun `a disabled, uninstalled or unconfigured plugin offers no download`() {
        install("demo", enabled = false)
        assertFalse(registry.offersDownloads("demo"))
        assertEquals("plugin", DownloadSource.sourceFor(demoEpisode, registry::isXuper, registry::offersDownloads))

        registry.setEnabled("demo", true)
        assertTrue(registry.offersDownloads("demo"))

        missingSettings = mapOf("demo" to listOf("server"))
        registry.reload()
        assertFalse("a plugin waiting for its settings cannot resolve anything", registry.offersDownloads("demo"))

        missingSettings = emptyMap()
        registry.uninstall("demo")
        assertFalse(registry.offersDownloads("demo"))
        assertEquals("plugin", DownloadSource.sourceFor(demoEpisode, registry::isXuper, registry::offersDownloads))
    }

    @Test fun `the recognized Xuper install keeps its own key even when it declares download`() {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        assertEquals(DownloadSource.XUPER, DownloadSource.sourceFor(xuperEpisode, registry::isXuper, registry::offersDownloads))
        assertEquals(DownloadSource.XUPER, DownloadSource.sourceForItem("plugin:xuper", registry::isXuper, registry::offersDownloads))
    }

    @Test fun `another repo's plugin under the xuper id downloads only through the generic path`() {
        install("xuper", "someone-else/kino-plugin-xuper")
        assertEquals(DownloadSource.PLUGIN_DOWNLOAD, DownloadSource.sourceFor(xuperEpisode, registry::isXuper, registry::offersDownloads))
    }

    @Test fun `library rows map by the same rule`() {
        install("demo")
        install("plain", capabilities = listOf("search", "resolve"))
        assertEquals(DownloadSource.PLUGIN_DOWNLOAD, DownloadSource.sourceForItem("plugin:demo", registry::isXuper, registry::offersDownloads))
        assertTrue(DownloadSource.hasStrategy(DownloadSource.sourceForItem("plugin:demo", registry::isXuper, registry::offersDownloads), strategies))
        assertEquals("plugin:plain", DownloadSource.sourceForItem("plugin:plain", registry::isXuper, registry::offersDownloads))
        assertFalse(DownloadSource.hasStrategy(DownloadSource.sourceForItem("plugin:plain", registry::isXuper, registry::offersDownloads), strategies))
        assertEquals("magis", DownloadSource.sourceForItem("magis", registry::isXuper, registry::offersDownloads))
    }

    private class RecordingSource : ContentSource {
        val resolved = mutableListOf<String>()
        override fun recognizes(ref: String) = true
        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = emptyFlow()
        override suspend fun resolve(ref: String): GatewayPlayable { resolved += ref; return GatewayPlayable("plugin", "https://example.com/v.mpd") }
        override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> = emptyList<GatewayEpisode>() to null
    }

    @Test fun `the generic strategy re-checks the install when the download runs`() = runTest {
        install("demo")
        val source = RecordingSource()
        val strategy = PluginDownloadStrategy(
            refForEpisode = { "ref" },
            source = source,
            downloaderFor = { HttpRangeDownloader(OkHttpClient()) },
            offersDownloads = registry::offersDownloads,
        )

        // Usable and declaring download: it resolves (and this stream is then refused as DASH).
        val refused = strategy.download(demoEpisode, false, tmp.root) { _, _ -> } as DownloadOutcome.Failed
        assertEquals(PluginDownloadEligibility.NOT_DOWNLOADABLE, refused.reason)
        assertEquals(listOf("ref"), source.resolved)

        // A queued row outlives the plugin being disabled: nothing of the plugin runs any more.
        registry.setEnabled("demo", false)
        assertTrue(strategy.download(demoEpisode, false, tmp.root) { _, _ -> } is DownloadOutcome.Failed)
        assertEquals(1, source.resolved.size)
    }
}
