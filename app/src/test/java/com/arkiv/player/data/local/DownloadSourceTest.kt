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

    /** What `AppGraph.downloadStrategies` has today: Magis only. */
    private val strategies = setOf("magis")

    @Test
    fun `magis is offered for download`() {
        assertTrue(DownloadSource.canDownload("magis:2AD2591D4242471D96B68FF04FFD2784::e6", strategies))
        assertTrue(DownloadSource.hasStrategy("magis", strategies))
    }

    /** Widevine: there's nothing to download it with, so the option stays hidden instead of failing later. */
    @Test
    fun `caracol is not offered for download`() {
        assertFalse(DownloadSource.canDownload("ditu:12345::e1", strategies))
        assertFalse(DownloadSource.canDownload("ditu:P1::0", strategies))
        assertFalse(DownloadSource.hasStrategy("ditu", strategies))
    }

    /** The rule is "has a strategy", not a list of names: a future source is covered on its own. */
    @Test
    fun `a source with no strategy is not offered, whatever its name`() {
        assertFalse(DownloadSource.canDownload("dragon-ball-gt_s01e01", strategies))
        assertFalse(DownloadSource.hasStrategy("fuente_nueva", strategies))
        assertTrue(DownloadSource.hasStrategy("fuente_nueva", strategies + "fuente_nueva"))
    }
}
