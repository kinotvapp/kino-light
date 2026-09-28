package com.arkiv.player.ui.player

import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.playback.DituLive
import com.arkiv.player.playback.PlayerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the player does when the Xuper live gate is (or turns) closed: only a native Xuper channel
 * stops, with the gate's message; Caracol live, plugin live and every VOD title never do.
 */
class XuperLiveStopTest {
    private val off = "Activa el plugin Xuper para ver este canal"
    private val xuperChannel = "${PlayerSource.LIVE_PREFIX}ch1"

    @Test fun `the gate turning off while a xuper channel plays stops it with the message`() {
        assertEquals(off, xuperLiveStopMessage(xuperChannel, off))
    }

    @Test fun `a gate closed right after the open returns means no playback`() {
        // The post-open recheck asks with the channel it just resolved.
        assertEquals(off, xuperLiveStopMessage(xuperChannel, gateMessage = off))
    }

    @Test fun `an open gate never stops a xuper channel`() {
        assertNull(xuperLiveStopMessage(xuperChannel, null))
    }

    @Test fun `caracol live, plugin live and vod are never stopped by the xuper gate`() {
        assertNull(xuperLiveStopMessage("${DituLive.PREFIX}canal-caracol", off))
        assertNull(xuperLiveStopMessage(PluginIds.liveEpisodeId("demo", "ch9"), off))
        assertNull(xuperLiveStopMessage("magis:123", off))
        assertNull(xuperLiveStopMessage("plugin:xuper:abc", off))
        assertNull(xuperLiveStopMessage("/sdcard/file.mp4", off))
    }

    @Test fun `a plugin channel of the En vivo module is never stopped by the xuper gate`() {
        assertNull(xuperLiveStopMessage("${PlayerSource.LIVE_PREFIX}plugin:tv:ch1", off))
    }

    @Test fun `nothing loaded yet is never stopped`() {
        assertNull(xuperLiveStopMessage(null, off))
    }
}
