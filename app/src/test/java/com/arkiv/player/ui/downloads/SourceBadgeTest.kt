package com.arkiv.player.ui.downloads

import com.arkiv.player.data.local.DownloadSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Descargas badge. It is fed two different columns: a row's `downloads.source` (the strategy
 * key: "magis", "xuper", "plugin-download"…) and a group header's `items.source` (the library's:
 * "magis", "ditu", "plugin:<id>"…). Both must name where the title really came from.
 */
class SourceBadgeTest {

    private val xuperIsInstalledAs: (String) -> Boolean = { it == "xuper" }

    private fun badge(source: String) = sourceBadge(source, xuperIsInstalledAs)

    @Test fun `a third-party plugin download row is badged PLUGIN, not ARCHIVE`() {
        assertEquals("PLUGIN", badge(DownloadSource.PLUGIN_DOWNLOAD))
    }

    @Test fun `Xuper and Magis download rows keep the XUPER badge`() {
        assertEquals("XUPER", badge(DownloadSource.XUPER))
        assertEquals("XUPER", badge("magis"))
    }

    @Test fun `the recognized Xuper install's group header is badged XUPER`() {
        assertEquals("XUPER", badge("plugin:xuper"))
    }

    @Test fun `any other plugin's group header is badged PLUGIN, whatever its id`() {
        assertEquals("PLUGIN", badge("plugin:demo"))
        assertEquals("PLUGIN", badge("plugin:tu-servidor"))
        // Only the installed record makes a plugin Xuper, never its id.
        assertEquals("PLUGIN", sourceBadge("plugin:xuper") { false })
    }

    @Test fun `legacy and fallback values are unchanged`() {
        assertEquals("TORRENT", badge("torrent"))
        assertEquals("WEB", badge("web"))
        assertEquals("ARCHIVE", badge("archive"))
    }
}
