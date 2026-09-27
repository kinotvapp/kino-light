package com.arkiv.player.data.local

import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginRegistry
import com.arkiv.player.data.plugin.PluginStore
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.data.plugin.sha256Hex
import kotlinx.coroutines.test.runTest
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
 * Only the recognized Xuper plugin's titles download; an ordinary third-party plugin's never do.
 * Decided through a REAL [PluginRegistry.isXuper] (what `AppGraph.isXuperPlugin` calls), over a
 * temp [PluginStore], against the key set `AppGraph.downloadStrategies` really has.
 */
class XuperDownloadRoutingTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: PluginStore
    private lateinit var registry: PluginRegistry

    /** `AppGraph.downloadStrategies`' keys. */
    private val strategies = setOf("magis", "ditu", DownloadSource.XUPER)

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        registry = PluginRegistry(store)
    }

    private fun install(id: String, address: String) {
        val manifest = JSONObject().put("id", id).put("name", id).put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).toString()
        val script = "export async function resolve(){}".toByteArray()
        val staging = store.newStaging(id)
        store.writeFiles(staging, manifest, "plugin.js", script, null,
            InstalledRecord(address, "1.0.0", sha256Hex(script), listOf("example.com"), 1L))
        store.commit(staging, id)
        registry.reload()
    }

    private val xuperEpisode = "plugin:xuper:26A13B36463F46D48002E304FB909D1C::0"
    private val demoEpisode = "plugin:demo:m1::0"

    @Test fun `the Xuper plugin's episode downloads, a third-party plugin's does not`() {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        install("demo", "someone/kino-plugin-demo")

        assertEquals(DownloadSource.XUPER, DownloadSource.sourceFor(xuperEpisode, registry::isXuper))
        assertTrue(DownloadSource.canDownload(xuperEpisode, strategies, registry::isXuper))

        assertEquals("plugin", DownloadSource.sourceFor(demoEpisode, registry::isXuper))
        assertFalse(DownloadSource.canDownload(demoEpisode, strategies, registry::isXuper))
    }

    @Test fun `another repo's plugin under the xuper id gets no download`() {
        install("xuper", "someone-else/kino-plugin-xuper")
        assertEquals("plugin", DownloadSource.sourceFor(xuperEpisode, registry::isXuper))
        assertFalse(DownloadSource.canDownload(xuperEpisode, strategies, registry::isXuper))
    }

    @Test fun `an uninstalled plugin's episode gets no download`() {
        assertEquals("plugin", DownloadSource.sourceFor(xuperEpisode, registry::isXuper))
    }

    @Test fun `legacy Magis and Caracol chapters keep their own sources`() {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        assertEquals("magis", DownloadSource.sourceFor("magis:7CE83F59C51B4F2E80EC9ECBE49EADC0::e3", registry::isXuper))
        assertTrue(DownloadSource.canDownload("magis:7CE83F59C51B4F2E80EC9ECBE49EADC0::e3", strategies, registry::isXuper))
        assertEquals("ditu", DownloadSource.sourceFor("ditu:12345::e1", registry::isXuper))
    }

    @Test fun `the one-argument sourceFor fails closed, Xuper included`() {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        assertEquals("plugin", DownloadSource.sourceFor(xuperEpisode))
    }

    @Test fun `library rows map by the same rule`() {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        install("demo", "someone/kino-plugin-demo")
        assertEquals(DownloadSource.XUPER, DownloadSource.sourceForItem("plugin:xuper", registry::isXuper))
        assertEquals("plugin:demo", DownloadSource.sourceForItem("plugin:demo", registry::isXuper))
        assertFalse(DownloadSource.hasStrategy(DownloadSource.sourceForItem("plugin:demo", registry::isXuper), strategies))
        assertEquals("magis", DownloadSource.sourceForItem("magis", registry::isXuper))
    }

    private class RecordingStrategy : DownloadStrategy {
        val downloaded = mutableListOf<String>()
        override suspend fun download(episodeId: String, alreadyConfirmed: Boolean, targetDir: File, onProgress: (Long, Long) -> Unit): DownloadOutcome {
            downloaded += episodeId
            return DownloadOutcome.Done(File(targetDir, episodeId))
        }
    }

    @Test fun `the xuper strategy re-checks the install before downloading`() = runTest {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        install("demo", "someone/kino-plugin-demo")
        val inner = RecordingStrategy()
        val strategy = XuperPluginDownloadStrategy(inner, registry::isXuper)

        assertTrue(strategy.download(xuperEpisode, false, tmp.root) { _, _ -> } is DownloadOutcome.Done)
        // A row that reached this key for any other plugin (or no plugin) never downloads.
        assertTrue(strategy.download(demoEpisode, false, tmp.root) { _, _ -> } is DownloadOutcome.Failed)
        assertTrue(strategy.download("magis:ABC::e1", false, tmp.root) { _, _ -> } is DownloadOutcome.Failed)
        assertEquals(listOf(xuperEpisode), inner.downloaded)
    }

    @Test fun `a queued Xuper row stops downloading once the install is no longer Xuper`() = runTest {
        install("xuper", XuperPrivilege.SOURCE_REPO)
        val inner = RecordingStrategy()
        val strategy = XuperPluginDownloadStrategy(inner, registry::isXuper)
        registry.uninstall("xuper")
        install("xuper", "someone-else/kino-plugin-xuper")
        assertTrue(strategy.download(xuperEpisode, false, tmp.root) { _, _ -> } is DownloadOutcome.Failed)
        assertTrue(inner.downloaded.isEmpty())
    }
}
