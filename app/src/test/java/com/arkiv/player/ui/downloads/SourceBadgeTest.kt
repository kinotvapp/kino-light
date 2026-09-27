package com.arkiv.player.ui.downloads

import com.arkiv.player.data.local.DownloadSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceBadgeTest {

    @Test
    fun `a third-party plugin download is badged PLUGIN, not ARCHIVE`() {
        assertEquals("PLUGIN", sourceBadge(DownloadSource.PLUGIN_DOWNLOAD))
    }

    @Test
    fun `Xuper and Magis downloads keep the XUPER badge`() {
        assertEquals("XUPER", sourceBadge(DownloadSource.XUPER))
        assertEquals("XUPER", sourceBadge("magis"))
    }

    @Test
    fun `legacy and fallback values are unchanged`() {
        assertEquals("TORRENT", sourceBadge("torrent"))
        assertEquals("WEB", sourceBadge("web"))
        assertEquals("ARCHIVE", sourceBadge("archive"))
    }
}
