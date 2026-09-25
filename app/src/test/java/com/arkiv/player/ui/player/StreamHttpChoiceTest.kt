package com.arkiv.player.ui.player

import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.UserHost
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which data source `StreamExoPlayer` builds: the host-gated one for plugins, and only for them. */
class StreamHttpChoiceTest {
    private val hosts = EffectiveHosts(listOf("cdn.example.com"), listOf(UserHost("http", "192.168.1.10", 8096)))

    @Test fun `a plugin stream is host-gated to the approved hosts and the typed servers`() =
        assertEquals(StreamHttp.PluginGated(hosts), streamHttpFor(SourceKind.PLUGIN, hosts))

    @Test fun `a plugin stream with no approved hosts is still gated, so it reaches nothing`() =
        assertEquals(StreamHttp.PluginGated(EffectiveHosts(emptyList())), streamHttpFor(SourceKind.PLUGIN, EffectiveHosts(emptyList())))

    @Test fun `every other source keeps the default data source, even if hosts leak in`() =
        SourceKind.entries.filter { it != SourceKind.PLUGIN }.forEach { kind ->
            assertEquals(kind.name, StreamHttp.Default, streamHttpFor(kind, hosts))
        }
}
