package com.arkiv.player.playback

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.plugin.PluginRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The path a plugin's live channel takes to the player. See [PluginLive]'s KDoc. */
class PluginLiveTest {

    private fun result(
        kind: String = "live",
        pluginId: String = "demo",
        itemId: String = "canal-1",
        ref: String = PluginRef(pluginId, itemId, if (kind == "live") PluginRef.LIVE else PluginRef.MOVIE, "ch-1").encode(),
        extra: Map<String, String> = mapOf("pluginItemId" to itemId, "pluginName" to "Demo", "poster" to "https://x/c1.png"),
    ) = GatewayResult(source = "plugin:$pluginId", title = "Canal Uno", ref = ref, kind = kind, extra = extra)

    @Test fun `a live result becomes a channel with the live player id, its ref and its art`() {
        val channel = PluginLive.channelOf(result())!!
        assertEquals(PluginIds.liveEpisodeId("demo", "canal-1"), channel.episodeId)
        assertEquals("demo", channel.pluginId)
        assertEquals(result().ref, channel.ref)
        assertEquals("Canal Uno", channel.title)
        assertEquals("https://x/c1.png", channel.logo)
    }

    @Test fun `a movie or series result is not a channel`() {
        assertNull(PluginLive.channelOf(result(kind = "movie")))
        assertNull(PluginLive.channelOf(result(kind = "series", ref = PluginRef("demo", "s1", PluginRef.SERIES, "s").encode())))
    }

    @Test fun `a live result whose ref is not that plugin's own, or has no item id, is not a channel`() {
        // Says live but carries another plugin's ref: nothing may play it.
        assertNull(PluginLive.channelOf(result(ref = PluginRef("other", "canal-1", PluginRef.LIVE, "ch-1").encode())))
        // Says live but the ref is a movie's: the kinds must agree.
        assertNull(PluginLive.channelOf(result(ref = PluginRef("demo", "canal-1", PluginRef.MOVIE, "ch-1").encode())))
        assertNull(PluginLive.channelOf(result(extra = mapOf("pluginItemId" to ""))))
        assertNull(PluginLive.channelOf(result().copy(source = "ditu")))
    }

    /** Without this `PlayerViewModel.load` wouldn't route it to `loadPlugin`, nor would the screen go live. */
    @Test fun `the id left for the player is a plugin id and a live channel`() {
        val id = PluginLive.leave(PluginLive.channelOf(result())!!)
        assertEquals(SourceKind.PLUGIN, PlayerSource.kindFor(id))
        assertTrue(PlayerSource.isLiveChannel(id))
    }

    @Test fun `the channel that was left is taken with its id, and a different id returns nothing`() {
        val one = PluginLive.channelOf(result())!!
        val oldId = PluginLive.leave(PluginLive.channelOf(result(itemId = "canal-2"))!!)
        val id = PluginLive.leave(one)
        assertEquals(one, PluginLive.take(id))
        assertNull(PluginLive.take(oldId))
        assertNull(PluginLive.take("plugin:demo:canal-1::0"))
    }

    /** A cut stream resolves the same channel again through `loadPlugin`: it must still be here. */
    @Test fun `taking does not clear the channel`() {
        val one = PluginLive.channelOf(result())!!
        val id = PluginLive.leave(one)
        PluginLive.take(id)
        assertEquals(one, PluginLive.take(id))
    }
}
