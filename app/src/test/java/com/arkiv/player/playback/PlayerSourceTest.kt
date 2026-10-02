package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSourceTest {
    @Test fun `an id with no known prefix is an unknown source`() {
        assertEquals(SourceKind.UNKNOWN, PlayerSource.kindFor("some-old-archive-identifier"))
    }

    @Test fun old_ditu_prefix_is_unknown_after_plugin_migration() {
        // The plugin migration rewrites library rows from `ditu:…` to `plugin:caracol-tv:…`. An id
        // that wasn't migrated (or was kept in some forgotten cache) is no longer a known source:
        // the player answers it with "no longer available", which `kindFor` calls `unmapped`.
        assertEquals(SourceKind.UNKNOWN, PlayerSource.kindFor("ditu:12345::e1"))
    }

    @Test fun magis_prefix_is_still_magis() {
        assertEquals(SourceKind.MAGIS, PlayerSource.kindFor("magis:2AD2591D4242471D96B68FF04FFD2784::e6"))
    }

    @Test fun `a plugin title is a plugin source and never live`() {
        assertEquals(SourceKind.PLUGIN, PlayerSource.kindFor("plugin:archive-org:Metropolis_1927::0"))
        assertEquals(SourceKind.PLUGIN, PlayerSource.kindFor("plugin:demo:s1::t2e1"))
        assertFalse(PlayerSource.isLiveChannel("plugin:demo:s1::e1"))
        assertFalse(PlayerSource.isLiveChannel("plugin:demo:s1::0"))
    }

    /** A plugin's live channel keeps the plugin's player path (its host gate) and the live UI. */
    @Test fun `a plugin live channel is a plugin source AND a live channel`() {
        val id = com.arkiv.player.data.plugin.PluginIds.liveEpisodeId("demo", "canal-1")
        assertEquals(SourceKind.PLUGIN, PlayerSource.kindFor(id))
        assertTrue(PlayerSource.isLiveChannel(id))
    }

    // --- is it a live channel? ------------------------------------------------------------------

    @Test fun magis_live_is_a_live_channel() {
        assertTrue(PlayerSource.isLiveChannel("${PlayerSource.LIVE_PREFIX}caracoltv"))
    }

    /** Plugin live channels still go through [PluginLive], which keeps the legacy id shape alive. */
    @Test fun plugin_live_is_a_live_channel() {
        assertTrue(PlayerSource.isLiveChannel("${PlayerSource.LIVE_PREFIX}12345"))
    }

    @Test fun plugin_vod_is_not_a_live_channel() {
        assertFalse(PlayerSource.isLiveChannel("plugin:caracol-tv:cditu1:VOD:12345"))
    }

    @Test fun magis_vod_is_not_a_live_channel() {
        assertFalse(PlayerSource.isLiveChannel("magis:2AD2591D4242471D96B68FF04FFD2784::e6"))
        assertFalse(PlayerSource.isLiveChannel("someitem::3"))
    }
}
