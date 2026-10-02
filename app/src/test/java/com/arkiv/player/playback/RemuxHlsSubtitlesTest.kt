package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remux's subtitles as HLS renditions: listed in the master, each with a playlist that mirrors
 * the video's segments so the receiver's view of the presentation (live or not, its target duration)
 * never changes because subtitles were added.
 */
class RemuxHlsSubtitlesTest {

    private val tracks = listOf(
        Fmp4Index.Track(1, "vide", 90_000, "avc1", "avc1.64001f", 1280, 720),
        Fmp4Index.Track(2, "soun", 48_000, "mp4a", "mp4a.40.2"),
    )

    @Test
    fun `without subtitles the master is exactly as before`() {
        val master = RemuxHls.masterPlaylist(tracks, emptyList())
        assertFalse(master.contains("SUBTITLES"))
        assertFalse(master.contains("EXT-X-MEDIA"))
    }

    @Test
    fun `each subtitle is an off-by-default rendition the variant points at`() {
        val master = RemuxHls.masterPlaylist(
            tracks,
            emptyList(),
            listOf(
                RemuxHls.SubtitleRendition("Español", "es", "http://lan/s/t/0/0/"),
                RemuxHls.SubtitleRendition("Inglés \"SDH\"", "und", "http://lan/s/t/0/1/"),
            ),
        )
        assertTrue(master.contains("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"Español\",LANGUAGE=\"es\",AUTOSELECT=NO,DEFAULT=NO,URI=\"subs0.m3u8\"\n"))
        // A quote can't break the attribute; an unknown language is not declared.
        assertTrue(master.contains("NAME=\"Inglés 'SDH'\",AUTOSELECT=NO,DEFAULT=NO,URI=\"subs1.m3u8\""))
        val variant = master.lines().first { it.startsWith("#EXT-X-STREAM-INF") }
        assertTrue(variant.endsWith(",SUBTITLES=\"subs\""))
        assertTrue(master.indexOf("#EXT-X-MEDIA") < master.indexOf("#EXT-X-STREAM-INF"))
    }

    @Test
    fun `a subtitle playlist mirrors the video's segments, type and target duration`() {
        val segments = listOf(RemuxHls.Segment(0, 2, 6.0), RemuxHls.Segment(3, 4, 6.5))
        val growing = RemuxHls.subtitlePlaylist(segments, complete = false, segmentBase = "http://lan/s/t/0/0/")
        assertTrue(growing.contains("#EXT-X-TARGETDURATION:${RemuxHls.TARGET_DURATION}\n"))
        assertTrue(growing.contains("#EXT-X-PLAYLIST-TYPE:EVENT\n"))
        assertTrue(growing.contains("#EXTINF:6.000,\nhttp://lan/s/t/0/0/0-6000.vtt\n#EXTINF:6.500,\nhttp://lan/s/t/0/0/6000-12500.vtt\n"))
        assertFalse(growing.contains("ENDLIST"))
        val done = RemuxHls.subtitlePlaylist(segments, complete = true, segmentBase = "b/")
        assertTrue(done.contains("#EXT-X-PLAYLIST-TYPE:VOD\n"))
        assertTrue(done.endsWith("#EXT-X-ENDLIST\n"))
        val video = RemuxHls.mediaPlaylist(segments, complete = true)
        assertEquals(video.lines().count { it.startsWith("#EXTINF") }, done.lines().count { it.startsWith("#EXTINF") })
    }

    @Test
    fun `subtitle playlist names`() {
        assertEquals(0, RemuxHls.subtitleNumber("subs0.m3u8"))
        assertEquals(12, RemuxHls.subtitleNumber("subs12.m3u8"))
        assertNull(RemuxHls.subtitleNumber("media.m3u8"))
        assertNull(RemuxHls.subtitleNumber("subs.m3u8"))
    }
}
