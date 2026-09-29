package com.arkiv.player.ui.player

import androidx.media3.common.PlaybackException
import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.HostNotAllowedException
import com.arkiv.player.data.plugin.UndeclaredPlaybackHostException
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackHostRebuildTest {
    // ExoPlayer hands the gate's refusal over wrapped: PlaybackException > HttpDataSourceException
    // (an IOException; android.net.Uri, which a real one needs, is a stub on the JVM) > ours.
    @Test fun `the askable refusal is found inside ExoPlayer's wrapping`() {
        val ours = UndeclaredPlaybackHostException("demo", "seg.other.example")
        val wrapped = PlaybackException("Source error", java.io.IOException("open failed", ours), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(ours, undeclaredPlaybackHost(wrapped))
        assertNull(undeclaredPlaybackHost(PlaybackException("x", HostNotAllowedException("seg.other.example"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertNull(undeclaredPlaybackHost(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
    }

    private val item = PlayerData(
        episodeId = "plugin:demo:m1", itemId = "plugin:demo:m1", title = "T", subtitle = "", mediaUrl = "https://example.com/v.m3u8",
        castUrl = null, artworkUrl = "", openingStartMs = null, openingEndMs = null, endingStartMs = null,
        kind = SourceKind.PLUGIN, pluginHosts = EffectiveHosts(listOf("example.com"), insecure = setOf("example.com")), startPositionMs = 1_000L,
    )

    // The player is keyed on its StreamHttp (StreamExoPlayer's `remember(..., http, ...)`): new hosts
    // mean a new gated client and a new player, started where the person was.
    @Test fun `after an approval the item carries the new hosts and resumes where it was`() {
        val next = item.afterHostApproved(listOf("example.com", "seg.other.example"), positionMs = 754_321L, live = false)
        assertEquals(listOf("example.com", "seg.other.example"), next.pluginHosts.declared)
        assertEquals(setOf("example.com"), next.pluginHosts.insecure)
        assertEquals(754_321L, next.startPositionMs)
        assertEquals(item.copy(pluginHosts = next.pluginHosts, startPositionMs = 754_321L), next)
        assertNotEquals(
            streamHttpFor(item.kind, item.pluginHosts, pluginId = "demo"),
            streamHttpFor(next.kind, next.pluginHosts, pluginId = "demo"),
        )
    }

    @Test fun `a live channel restarts at the live edge`() {
        assertEquals(0L, item.afterHostApproved(listOf("example.com", "x.example.com"), positionMs = 90_000L, live = true).startPositionMs)
    }

    // Only a PLUGIN stream's client may ask, and only for its own plugin.
    @Test fun `the gated client knows which plugin a miss is asked for`() {
        assertEquals(StreamHttp.PluginGated(item.pluginHosts, xuper = false, pluginId = "demo"), streamHttpFor(SourceKind.PLUGIN, item.pluginHosts, pluginId = "demo"))
        assertEquals(StreamHttp.Default, streamHttpFor(SourceKind.MAGIS, item.pluginHosts, pluginId = "demo"))
    }
}
