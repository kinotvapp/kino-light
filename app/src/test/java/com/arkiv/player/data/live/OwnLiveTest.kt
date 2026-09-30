package com.arkiv.player.data.live

import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.plugin.PluginIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnLiveTest {
    @Test fun `the provider is the plugin-shaped id of the reserved plugin`() {
        assertEquals("plugin:own", OwnLive.PROVIDER)
        assertEquals(LiveChannelKeys.pluginProvider(OwnLive.PLUGIN_ID), OwnLive.PROVIDER)
        assertTrue(LiveChannelKeys.isValidProvider(OwnLive.PROVIDER))
    }

    @Test fun `live codes of the provider parse back to it, never to xuper`() {
        val code = LiveChannelKeys.liveCode(OwnLive.PROVIDER, "3f1c2b7e-aaaa-bbbb-cccc-000000000001")
        assertEquals(OwnLive.PROVIDER to "3f1c2b7e-aaaa-bbbb-cccc-000000000001", LiveChannelKeys.parse(code))
        val playlist = LiveChannelKeys.liveCode(OwnLive.PROVIDER, "~ab12cd34.c1")
        assertEquals(OwnLive.PROVIDER to "~ab12cd34.c1", LiveChannelKeys.parse(playlist))
    }

    @Test fun `the player episode id is a live plugin episode of the reserved plugin`() {
        val ep = PluginIds.liveEpisodeId(OwnLive.PLUGIN_ID, "3f1c2b7e-aaaa")
        assertTrue(PluginIds.isLiveEpisode(ep))
        assertEquals("own", PluginIds.pluginIdOfEpisode(ep))
    }

    @Test fun `access lets a live stream reach any public host and nothing else`() {
        val a = OwnLive.access()
        assertTrue(a.liveHosts.anyPublicLiveHost)
        assertFalse(a.hosts.anyPublicLiveHost)     // subtitles, licences, side files stay strict
        assertFalse(a.xuper)
        assertTrue(a.liveHosts.user.isEmpty())     // no typed servers: the LAN stays out
    }
}
