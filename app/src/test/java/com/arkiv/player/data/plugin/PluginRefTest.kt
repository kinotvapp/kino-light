package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRefTest {
    @Test fun `movie ref round-trips and carries its prefix`() {
        val r = PluginRef("archive-org", "Metropolis_1927", PluginRef.MOVIE, "opaque|ref with spaces/ü")
        val s = r.encode()
        assertTrue(s.startsWith(PluginRef.prefixFor("archive-org")))
        assertEquals(r, PluginRef.decode(s))
    }

    @Test fun `episode ref keeps season and number`() {
        val r = PluginRef("p1", "show", PluginRef.EPISODE, "ep-ref", season = 2, number = 7)
        assertEquals(r, PluginRef.decode(r.encode()))
    }

    @Test fun `live ref round-trips as its own kind`() {
        val r = PluginRef("p1", "canal-1", PluginRef.LIVE, "ch:1")
        assertEquals(r, PluginRef.decode(r.encode()))
        assertEquals("live", PluginRef.LIVE)
    }

    @Test fun `a live channel's player id is the item's id with a live suffix, and only that is live`() {
        val id = PluginIds.liveEpisodeId("demo", "canal-1")
        assertEquals("plugin:demo:canal-1::live", id)
        assertTrue(PluginIds.isLiveEpisode(id))
        assertEquals("demo", PluginIds.pluginIdOfEpisode(id))
        // A movie, a chapter, and even an item whose own id is "live" are never live channels.
        listOf("plugin:demo:canal-1::0", "plugin:demo:canal-1::e1", "plugin:demo:live::0", "plugin:demo:live", "live:caracoltv", "ditu:vivo:5").forEach {
            assertEquals(it, false, PluginIds.isLiveEpisode(it))
        }
    }

    @Test fun `foreign or broken refs decode to null`() {
        listOf("ditu1:VOD:1", "plg1:", "plg1:p1", "plg1:p1:%%%", "plg1:p1:e30", "magis:x").forEach {
            assertNull(it, PluginRef.decode(it))
        }
    }

    @Test fun `ids and sources`() {
        assertEquals("plugin:archive-org", PluginIds.sourceFor("archive-org"))
        assertEquals("archive-org", PluginIds.pluginIdOfSource("plugin:archive-org"))
        assertNull(PluginIds.pluginIdOfSource("plugin:"))
        assertNull(PluginIds.pluginIdOfSource("plugin:a:b"))
        assertNull(PluginIds.pluginIdOfSource("ditu"))
        assertEquals("plugin:archive-org:Metropolis_1927", PluginIds.itemIdFor("archive-org", "Metropolis_1927"))
        assertEquals("archive-org", PluginIds.pluginIdOfEpisode("plugin:archive-org:Metropolis_1927::0"))
        assertNull(PluginIds.pluginIdOfEpisode("ditu:1::0"))
    }

    @Test fun `colors parse to opaque ARGB with a neutral default`() {
        assertEquals(0xFFE0A030, PluginColors.parse("#E0A030"))
        assertEquals(0xFFE0A030, PluginColors.parse("#e0a030"))
        assertEquals(PluginColors.DEFAULT, PluginColors.parse(null))
        assertEquals(PluginColors.DEFAULT, PluginColors.parse("orange"))
    }
}
