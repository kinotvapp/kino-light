package com.arkiv.player.ui.player

import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which data source `StreamExoPlayer` builds: the host-gated one for plugins, and only for them. */
class StreamHttpChoiceTest {
    @Test fun `a plugin stream is host-gated to the approved hosts`() =
        assertEquals(
            StreamHttp.PluginGated(listOf("cdn.example.com")),
            streamHttpFor(SourceKind.PLUGIN, listOf("cdn.example.com")),
        )

    @Test fun `a plugin stream with no approved hosts is still gated, so it reaches nothing`() =
        assertEquals(StreamHttp.PluginGated(emptyList()), streamHttpFor(SourceKind.PLUGIN, emptyList()))

    @Test fun `every other source keeps the default data source, even if hosts leak in`() =
        SourceKind.entries.filter { it != SourceKind.PLUGIN }.forEach { kind ->
            assertEquals(kind.name, StreamHttp.Default, streamHttpFor(kind, listOf("cdn.example.com")))
        }
}
