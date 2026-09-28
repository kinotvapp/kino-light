package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.liveCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveZappingTest {
    private val list = listOf(
        LiveChannel("c1", "Uno", 1, null),
        LiveChannel("c2", "Dos", 2, null),
        LiveChannel("c3", "Tres", 3, null),
    )

    @Test
    fun `advances and wraps around at the end`() {
        val z = LiveZapping(list, 2)
        assertEquals("c1", z.next().code)
    }

    @Test
    fun `goes back and wraps around at the start`() {
        val z = LiveZapping(list, 0)
        assertEquals("c3", z.previous().code)
    }

    @Test
    fun `the neighbors are the one before and the one after`() {
        assertEquals(setOf("c1", "c3"), LiveZapping(list, 1).neighbors().map { it.code }.toSet())
    }

    @Test
    fun `with a single channel zapping doesn't move or fail`() {
        val z = LiveZapping(listOf(list[0]), 0)
        assertEquals("c1", z.next().code)
        assertEquals(emptyList<LiveChannel>(), z.neighbors())
    }

    private fun ch(code: String, provider: String = "xuper") = LiveChannel(code, code, 1, null, provider = provider)

    @Test fun `zapping from a mixed list walks only the chosen channel's provider`() {
        val entry = listOf(ch("a"), ch("x", "plugin:tv"), ch("b"), ch("y", "plugin:tv"))
        assertEquals(listOf("plugin:tv:x", "plugin:tv:y"), zappingListFor(entry, ch("x", "plugin:tv")).map { it.liveCode })
        assertEquals(listOf("a", "b"), zappingListFor(entry, ch("b")).map { it.liveCode })
    }

    @Test fun `a channel missing from the entry list is still the one that plays`() {
        assertEquals(listOf("plugin:tv:z", "plugin:tv:x"), zappingListFor(listOf(ch("x", "plugin:tv"), ch("a")), ch("z", "plugin:tv")).map { it.liveCode })
        assertEquals(listOf("c"), zappingListFor(emptyList(), ch("c")).map { it.liveCode })
    }

    @Test fun `a live code finds its channel in the entry list, else is rebuilt from the code`() {
        val entry = listOf(LiveChannel("c1", "Canal Uno", 7, null, provider = "plugin:tv"))
        assertEquals(entry[0], channelForLiveCode("plugin:tv:c1", entry))
        assertEquals(LiveChannel("c1", "c1", 0, null), channelForLiveCode("c1", entry))
        assertEquals(null, channelForLiveCode("plugin:tv", entry))
    }

    @Test fun `a channel is gone when its provider left the module`() {
        assertTrue(providerGone(ch("x", "plugin:tv"), listOf("xuper")))
        assertFalse(providerGone(ch("x", "plugin:tv"), listOf("xuper", "plugin:tv")))
        assertFalse(providerGone(null, emptyList()))
    }

    @Test fun `a direct channel never goes to resolve, a ref channel does, neither is missing`() {
        val playable = com.arkiv.player.data.gateway.GatewayPlayable(kind = "plugin", url = "https://cdn.example.com/a.m3u8")
        val direct = com.arkiv.player.playback.PluginLiveChannel("plugin:tv:a::live", "tv", "", "A", "", direct = playable)
        assertEquals(PluginLivePlay.Direct(playable), pluginLivePlay(direct))
        assertEquals(PluginLivePlay.Resolve("plg1:tv:x"), pluginLivePlay(direct.copy(direct = null, ref = "plg1:tv:x")))
        assertEquals(PluginLivePlay.Missing, pluginLivePlay(direct.copy(direct = null)))
        assertEquals(PluginLivePlay.Missing, pluginLivePlay(null))
    }
}
