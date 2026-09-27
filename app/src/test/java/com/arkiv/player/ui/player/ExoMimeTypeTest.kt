package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExoMimeTypeTest {
    @Test fun `every HLS alias becomes the one media3 recognises`() {
        for (alias in listOf(
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/x-mpegURL",
            "audio/mpegurl", "audio/x-mpegurl", "application/mpegurl", "Application/Vnd.Apple.MpegURL",
        )) {
            assertEquals(alias, "application/x-mpegURL", exoMimeType(alias))
        }
    }

    @Test fun `DASH and Smooth Streaming keep media3's exact spelling whatever the case`() {
        assertEquals("application/dash+xml", exoMimeType("Application/DASH+XML"))
        assertEquals("application/vnd.ms-sstr+xml", exoMimeType("application/vnd.ms-sstr+xml"))
    }

    @Test fun `a progressive container is passed through unchanged`() {
        assertEquals("video/mp4", exoMimeType("video/mp4"))
        assertEquals("video/mp2t", exoMimeType("video/mp2t"))
    }

    @Test fun `no mime or a blank one lets the player sniff`() {
        assertNull(exoMimeType(null))
        assertNull(exoMimeType("  "))
    }
}
