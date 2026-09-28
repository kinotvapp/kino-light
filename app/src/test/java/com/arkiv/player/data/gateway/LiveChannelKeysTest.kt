package com.arkiv.player.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelKeysTest {
    @Test fun `a xuper channel's live code is its bare code, as before`() {
        assertEquals("cyx-RCNHD", LiveChannel("cyx-RCNHD", "RCN", 1, null).liveCode)
        assertEquals(LiveChannelKeys.XUPER to "cyx-RCNHD", LiveChannelKeys.parse("cyx-RCNHD"))
    }

    @Test fun `a plugin channel's live code names its plugin and round-trips`() {
        val c = LiveChannel("c1", "Canal", 1, null, provider = LiveChannelKeys.pluginProvider("own-server"))
        assertEquals("plugin:own-server:c1", c.liveCode)
        assertEquals("plugin:own-server" to "c1", LiveChannelKeys.parse(c.liveCode))
        assertEquals("own-server", LiveChannelKeys.pluginIdOf(c.provider))
    }

    @Test fun `malformed plugin codes and providers are refused`() {
        assertNull(LiveChannelKeys.parse("plugin:own-server"))
        assertNull(LiveChannelKeys.parse("plugin::c1"))
        assertNull(LiveChannelKeys.parse("plugin:own-server:a:b"))
        assertNull(LiveChannelKeys.parse(""))
        assertTrue(LiveChannelKeys.isValidProvider("xuper"))
        assertTrue(LiveChannelKeys.isValidProvider("plugin:own-server"))
        assertFalse(LiveChannelKeys.isValidProvider("plugin:"))
        assertFalse(LiveChannelKeys.isValidProvider("plugin:a:b"))
        assertFalse(LiveChannelKeys.isValidProvider("ditu"))
    }
}
