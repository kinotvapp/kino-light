package com.arkiv.player.ui.player

import androidx.media3.common.MimeTypes
import com.arkiv.player.data.gateway.GatewaySubtitle
import org.junit.Assert.assertEquals
import org.junit.Test

/** A plugin's declared subtitle `format` decides the MIME type; the URL is only a fallback. */
class SubtitleFormatTest {
    @Test fun `a plugin subtitle keeps its declared format`() =
        assertEquals(
            listOf(ResolvedSub("es", "https://h.example/sub?id=5", "srt")),
            pluginSubtitles(listOf(GatewaySubtitle("es", "https://h.example/sub?id=5", "srt"))),
        )

    @Test fun `the declared format wins over the URL`() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMimeType(ResolvedSub("es", "https://h.example/sub?id=5", "srt")))
        assertEquals(MimeTypes.TEXT_VTT, subtitleMimeType(ResolvedSub("es", "https://h.example/file.srt.php", "vtt")))
    }

    @Test fun `without a format the URL decides, VTT by default, as for Magis`() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMimeType(ResolvedSub("es", "https://h.example/a.srt")))
        assertEquals(MimeTypes.TEXT_VTT, subtitleMimeType(ResolvedSub("es", "https://h.example/a")))
    }
}
