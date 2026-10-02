package com.arkiv.player.data.local

import com.arkiv.player.playback.Container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Which downloads become MP4 and when ([Mp4PrepPolicy]). */
class Mp4PrepPolicyTest {

    private val gb = 1024L * 1024 * 1024
    private val plenty = 50 * gb

    @Test fun `an MP4 is never converted, whatever the space`() {
        assertEquals(Mp4PrepPolicy.Decision.NOT_NEEDED, Mp4PrepPolicy.decide(Container.MP4, 3 * gb, plenty))
        assertEquals(Mp4PrepPolicy.Decision.NOT_NEEDED, Mp4PrepPolicy.decide(Container.MP4, 3 * gb, 0))
    }

    @Test fun `every container media3 can read is converted when it fits`() {
        for (c in listOf(Container.MPEGTS, Container.MATROSKA, Container.WEBM, Container.AVI, Container.MPEGPS)) {
            assertEquals(c.name, Mp4PrepPolicy.Decision.CONVERT, Mp4PrepPolicy.decide(c, 3 * gb, plenty))
        }
    }

    @Test fun `what media3 cannot read, or nothing recognized, stays as it is`() {
        assertEquals(Mp4PrepPolicy.Decision.UNSUPPORTED, Mp4PrepPolicy.decide(null, 3 * gb, plenty))
        assertEquals(Mp4PrepPolicy.Decision.UNSUPPORTED, Mp4PrepPolicy.decide(Container.ASF, 3 * gb, plenty))
        assertEquals(Mp4PrepPolicy.Decision.UNSUPPORTED, Mp4PrepPolicy.decide(Container.OGG, 3 * gb, plenty))
    }

    @Test fun `the MP4 needs the file's size again plus the reserve`() {
        val size = 3 * gb
        val exactly = size + FreeSpacePolicy.MARGIN_BYTES
        assertEquals(Mp4PrepPolicy.Decision.CONVERT, Mp4PrepPolicy.decide(Container.MPEGTS, size, exactly))
        assertEquals(Mp4PrepPolicy.Decision.NO_SPACE, Mp4PrepPolicy.decide(Container.MPEGTS, size, exactly - 1))
        assertEquals(Mp4PrepPolicy.Decision.NO_SPACE, Mp4PrepPolicy.decide(Container.MPEGTS, size, size))
    }

    @Test fun `a download is worth another pass until it is ready or kept for good`() {
        assertTrue("never prepared", Mp4PrepPolicy.wants(null))
        assertTrue("the disk may have room now", Mp4PrepPolicy.wants(PrepMarker(PrepState.NO_SPACE)))
        assertTrue(Mp4PrepPolicy.wants(PrepMarker(PrepState.FAILED, attempts = 1)))
        assertTrue(Mp4PrepPolicy.wants(PrepMarker(PrepState.FAILED, attempts = Mp4PrepPolicy.MAX_ATTEMPTS - 1)))
        assertFalse("given up", Mp4PrepPolicy.wants(PrepMarker(PrepState.FAILED, attempts = Mp4PrepPolicy.MAX_ATTEMPTS)))
        assertFalse(Mp4PrepPolicy.wants(PrepMarker(PrepState.READY)))
        assertFalse(Mp4PrepPolicy.wants(PrepMarker(PrepState.UNSUPPORTED, "audio audio/ac3")))
    }

    @Test fun `the MP4 is named after the original`() {
        assertEquals("plugin_x_ep1.mp4", Mp4PrepPolicy.outputFor(File("/d/plugin_x_ep1.ts")).name)
        assertEquals("plugin_x_ep1.mp4", Mp4PrepPolicy.outputFor(File("/d/plugin_x_ep1.mkv")).name)
        // An old download named .mp4 that is not one: never written over itself.
        assertEquals("magis_7.prepared.mp4", Mp4PrepPolicy.outputFor(File("/d/magis_7.mp4")).name)
        assertEquals("/d", Mp4PrepPolicy.outputFor(File("/d/a.ts")).parent)
    }

    @Test fun `the marker round-trips and lives next to the download under its prefix`() {
        val m = PrepMarker(PrepState.FAILED, "rename failed", 2, listOf("/d/a.ts"))
        assertEquals(m, PrepMarker.fromJson(m.toJson()))
        assertEquals(PrepMarker(PrepState.READY), PrepMarker.fromJson(PrepMarker(PrepState.READY).toJson()))
        assertEquals(null, PrepMarker.fromJson("{nope"))
        val f = PrepMarker.fileFor(File("/d"), "plugin:x::ep 1")
        assertTrue(f.name.startsWith("${LocalFilePaths.sanitize("plugin:x::ep 1")}."))
    }
}
