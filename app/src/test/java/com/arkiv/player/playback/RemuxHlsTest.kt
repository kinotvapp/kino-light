package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemuxHlsTest {

    private fun frags(vararg seconds: Double): List<Fmp4Index.Fragment> {
        var at = 0L
        return seconds.map { s ->
            Fmp4Index.Fragment(at, 100, at + 1000, emptyList(), s).also { at += 1000 }
        }
    }

    @Test
    fun `fragments are grouped into segments of about the target`() {
        val s = RemuxHls.segments(frags(3.0, 3.0, 3.0, 3.0, 2.0), complete = true, targetSec = 6.0)
        assertEquals(listOf(RemuxHls.Segment(0, 1, 6.0), RemuxHls.Segment(2, 3, 6.0), RemuxHls.Segment(4, 4, 2.0)), s)
    }

    @Test
    fun `while the remux grows the unfinished tail is held back, and boundaries never move`() {
        val all = frags(2.5, 2.5, 2.5, 4.0, 2.0, 2.0, 2.0, 1.0)
        var previous = emptyList<RemuxHls.Segment>()
        for (n in 0..all.size) {
            val now = RemuxHls.segments(all.subList(0, n), complete = false)
            assertEquals(previous, now.subList(0, previous.size))
            now.forEach { assertTrue(it.durationSec >= RemuxHls.TARGET_SEGMENT_SEC) }
            previous = now
        }
        val done = RemuxHls.segments(all, complete = true)
        assertEquals(previous, done.subList(0, previous.size))
        assertEquals(all.sumOf { it.durationSec }, done.sumOf { it.durationSec }, 1e-9)
    }

    @Test
    fun `a growing playlist is an EVENT with no end, a finished one a VOD with ENDLIST`() {
        val segments = RemuxHls.segments(frags(3.0, 3.0, 3.0, 3.0), complete = false)
        val growing = RemuxHls.mediaPlaylist(segments, complete = false)
        assertTrue(growing.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        assertFalse(growing.contains("#EXT-X-ENDLIST"))
        assertTrue(growing.contains("#EXT-X-MAP:URI=\"init.mp4\""))
        assertTrue(growing.contains("#EXTINF:6.000,\ns0.m4s\n#EXTINF:6.000,\ns1.m4s\n"))
        val done = RemuxHls.mediaPlaylist(segments, complete = true)
        assertTrue(done.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertTrue(done.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun `the target duration does not change as the playlist grows`() {
        val a = RemuxHls.mediaPlaylist(listOf(RemuxHls.Segment(0, 0, 6.0)), complete = false)
        val b = RemuxHls.mediaPlaylist(List(50) { RemuxHls.Segment(it, it, 6.0 + (it % 5)) }, complete = false)
        val target = Regex("#EXT-X-TARGETDURATION:(\\d+)")
        assertEquals(target.find(a)!!.groupValues[1], target.find(b)!!.groupValues[1])
    }

    @Test
    fun `the master playlist states the codecs and the picture size`() {
        val tracks = listOf(
            Fmp4Index.Track(1, "vide", 90_000, "hvc1", "hvc1.1.6.L93.B0", 1280, 536),
            Fmp4Index.Track(2, "soun", 48_000, "mp4a", "mp4a.40.2"),
        )
        val master = RemuxHls.masterPlaylist(tracks, emptyList())
        assertTrue(master, master.contains("CODECS=\"hvc1.1.6.L93.B0,mp4a.40.2\""))
        assertTrue(master.contains("RESOLUTION=1280x536"))
        assertTrue(master.trimEnd().endsWith("media.m3u8"))
    }

    @Test
    fun `routes carry the token and the resource`() {
        assertEquals(RemuxHls.Route("abc", "media.m3u8"), RemuxHls.route("/r/abc/media.m3u8"))
        assertNull(RemuxHls.route("/file"))
        assertNull(RemuxHls.route("/r//media.m3u8"))
        assertNull(RemuxHls.route("/r/abc/x/y"))
        assertEquals(12, RemuxHls.segmentNumber("s12.m4s"))
        assertNull(RemuxHls.segmentNumber("s.m4s"))
        assertNull(RemuxHls.segmentNumber("init.mp4"))
    }

    @Test
    fun `the cast starts where the phone was once the remux covers it with room to spare`() {
        assertNull(RemuxHls.startIfCovered(107_266, availableSec = 120.0, leadSec = 30.0))
        assertEquals(107_266L, RemuxHls.startIfCovered(107_266, availableSec = 140.0, leadSec = 30.0))
        assertEquals(0L, RemuxHls.startIfCovered(0, availableSec = 30.0, leadSec = 30.0))
    }

    @Test
    fun `a seek while the remux grows is held inside what can be played`() {
        assertEquals(50_000L, RemuxHls.clampSeek(3_600_000, availableSec = 60.0, complete = false))
        assertEquals(20_000L, RemuxHls.clampSeek(20_000, availableSec = 60.0, complete = false))
        assertEquals(0L, RemuxHls.clampSeek(20_000, availableSec = 5.0, complete = false))
        assertEquals(3_600_000L, RemuxHls.clampSeek(3_600_000, availableSec = 60.0, complete = true))
    }
}
