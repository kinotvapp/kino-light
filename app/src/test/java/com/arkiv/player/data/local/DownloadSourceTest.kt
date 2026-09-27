package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourceTest {

    @Test
    fun `a magis chapter downloads with the magis strategy`() {
        assertEquals("magis", DownloadSource.sourceFor("magis:2AD2591D4242471D96B68FF04FFD2784::e6"))
    }

    @Test
    fun `an archive chapter downloads with the archive strategy`() {
        assertEquals("archive", DownloadSource.sourceFor("dragon-ball-gt_s01e01"))
    }

    @Test fun `an unknown source keeps the persisted download source value`() {
        assertEquals("archive", DownloadSource.sourceFor("some-old-archive-identifier"))
    }

    @Test
    fun `a caracol chapter doesn't fall into the archive strategy`() {
        assertEquals("ditu", DownloadSource.sourceFor("ditu:12345::e1"))
    }

    @Test fun `a plugin chapter has its own download source, and no strategy offers it`() {
        assertEquals("plugin", DownloadSource.sourceFor("plugin:demo:m1::0"))
        assertFalse(DownloadSource.canDownload("plugin:demo:m1::0", setOf("magis", "ditu"), noXuper))
    }

    /** No installed plugin is the recognized Xuper install. */
    private val noXuper: (String) -> Boolean = { false }

    /** What `AppGraph.downloadStrategies` has today, minus the plugin keys: Magis only. */
    private val strategies = setOf("magis")

    // ---- plugins that declare the `download` capability (apiVersion 2) ----

    private val demoEpisode = "plugin:demo:m1::0"

    /** The installed `demo` plugin is usable and declares `download`; no other plugin does. */
    private val demoDownloads: (String) -> Boolean = { it == "demo" }

    @Test fun `a plugin that offers downloads routes to the generic plugin strategy`() {
        assertEquals(DownloadSource.PLUGIN_DOWNLOAD, DownloadSource.sourceFor(demoEpisode, noXuper, demoDownloads))
        assertTrue(DownloadSource.canDownload(demoEpisode, strategies + DownloadSource.PLUGIN_DOWNLOAD, noXuper, demoDownloads))
        // A build without the generic strategy shows no button, so nothing lands FAILED as "Fuente no soportada".
        assertFalse(DownloadSource.canDownload(demoEpisode, strategies + DownloadSource.XUPER, noXuper, demoDownloads))
    }

    @Test fun `a plugin without the download capability stays on plugin, which has no strategy`() {
        assertEquals("plugin", DownloadSource.sourceFor("plugin:other:m1::0", noXuper, demoDownloads))
        assertFalse(DownloadSource.canDownload("plugin:other:m1::0", strategies + DownloadSource.PLUGIN_DOWNLOAD, noXuper, demoDownloads))
    }

    /** The Downloads screen offers a group's missing chapters only when its item's source can download them. */
    @Test fun `a downloads group offers its missing chapters only for an item source with a strategy`() {
        val all = strategies + DownloadSource.PLUGIN_DOWNLOAD + DownloadSource.XUPER
        val xuper: (String) -> Boolean = { it == "xuper" }
        assertTrue(DownloadSource.canDownloadItem("magis", all, xuper, demoDownloads))
        assertTrue(DownloadSource.canDownloadItem("plugin:xuper", all, xuper, demoDownloads))
        assertTrue(DownloadSource.canDownloadItem("plugin:demo", all, xuper, demoDownloads))
        // Caracol (no strategy) and a plugin that never declared `download`: no button, nothing queued to fail.
        assertFalse(DownloadSource.canDownloadItem("ditu", all, xuper, demoDownloads))
        assertFalse(DownloadSource.canDownloadItem("plugin:other", all, xuper, demoDownloads))
        // A plugin that stopped offering downloads since (disabled, uninstalled, needs setup).
        assertFalse(DownloadSource.canDownloadItem("plugin:demo", all, xuper) { false })
    }

    @Test fun `without the download predicate every plugin fails closed`() {
        assertEquals("plugin", DownloadSource.sourceFor(demoEpisode, noXuper))
        assertEquals("plugin:demo", DownloadSource.sourceForItem("plugin:demo", noXuper))
    }

    @Test fun `the recognized Xuper install keeps its own key even when it declares download`() {
        val xuperEpisode = "plugin:xuper:26A13B36463F46D48002E304FB909D1C::0"
        assertEquals(DownloadSource.XUPER, DownloadSource.sourceFor(xuperEpisode, { it == "xuper" }, { true }))
        assertEquals(DownloadSource.XUPER, DownloadSource.sourceForItem("plugin:xuper", { it == "xuper" }, { true }))
    }

    @Test fun `library rows of a downloading plugin map by the same rule`() {
        assertEquals(DownloadSource.PLUGIN_DOWNLOAD, DownloadSource.sourceForItem("plugin:demo", noXuper, demoDownloads))
        assertEquals("plugin:other", DownloadSource.sourceForItem("plugin:other", noXuper, demoDownloads))
        assertEquals("magis", DownloadSource.sourceForItem("magis", noXuper, demoDownloads))
    }

    @Test fun `non-plugin sources ignore both plugin predicates`() {
        val always: (String) -> Boolean = { true }
        assertEquals("magis", DownloadSource.sourceFor("magis:2AD2591D4242471D96B68FF04FFD2784::e6", always, always))
        assertEquals("ditu", DownloadSource.sourceFor("ditu:12345::e1", always, always))
        assertEquals("archive", DownloadSource.sourceFor("dragon-ball-gt_s01e01", always, always))
    }

    @Test
    fun `magis is offered for download`() {
        assertTrue(DownloadSource.canDownload("magis:2AD2591D4242471D96B68FF04FFD2784::e6", strategies, noXuper))
        assertTrue(DownloadSource.hasStrategy("magis", strategies))
    }

    /** Widevine: there's nothing to download it with, so the option stays hidden instead of failing later. */
    @Test
    fun `caracol is not offered for download`() {
        assertFalse(DownloadSource.canDownload("ditu:12345::e1", strategies, noXuper))
        assertFalse(DownloadSource.canDownload("ditu:P1::0", strategies, noXuper))
        assertFalse(DownloadSource.hasStrategy("ditu", strategies))
    }

    /** The rule is "has a strategy", not a list of names: a future source is covered on its own. */
    @Test
    fun `a source with no strategy is not offered, whatever its name`() {
        assertFalse(DownloadSource.canDownload("dragon-ball-gt_s01e01", strategies, noXuper))
        assertFalse(DownloadSource.hasStrategy("fuente_nueva", strategies))
        assertTrue(DownloadSource.hasStrategy("fuente_nueva", strategies + "fuente_nueva"))
    }
}
