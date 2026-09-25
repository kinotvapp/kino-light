package com.arkiv.player.playback

import com.arkiv.player.playback.LiveStreamHealth.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamHealthTest {

    // --- playlist parsing ---------------------------------------------------

    @Test
    fun `a live playlist is read into sequence, target, segments and durations`() {
        val p = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:2782
            #EXTINF:5.000,
            https://cdn.example.com/live/cyx_2782.ts?tk=abc
            #EXTINF:5.000,
            cyx_2783.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:4.800,
            cyx_2784.ts
            """.trimIndent(),
        )
        assertEquals(2782L, p.mediaSequence)
        assertEquals(5.0, p.targetDurationSec!!, 0.0)
        assertEquals(listOf("cyx_2782.ts", "cyx_2783.ts", "cyx_2784.ts"), p.segments.map { it.name })
        assertEquals(listOf(5.0, 5.0, 4.8), p.segments.map { it.durationSec })
        assertEquals(1, p.discontinuities)
        assertFalse(p.endList)
    }

    @Test
    fun `the discontinuity SEQUENCE tag is not a discontinuity and ENDLIST is noticed`() {
        val p = LivePlaylistParser.parse("#EXT-X-DISCONTINUITY-SEQUENCE:3\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST")
        assertEquals(0, p.discontinuities)
        assertTrue(p.endList)
    }

    @Test
    fun `only text that starts with the EXTM3U tag is a playlist`() {
        assertTrue(LivePlaylistParser.looksLikePlaylist("#EXTM3U\n#EXT-X-VERSION:3"))
        assertTrue("a BOM and blank lines in front are tolerated", LivePlaylistParser.looksLikePlaylist("\uFEFF\n #EXTM3U\n"))
        assertFalse(LivePlaylistParser.looksLikePlaylist("<html><head><title>404 Not Found</title></head></html>"))
        assertFalse(LivePlaylistParser.looksLikePlaylist("{\"code\":500}"))
        assertFalse(LivePlaylistParser.looksLikePlaylist(""))
    }

    @Test
    fun `garbage parses to an empty playlist instead of throwing`() {
        val p = LivePlaylistParser.parse("<html>502 Bad Gateway</html>")
        assertNull(p.mediaSequence)
        assertEquals(1, p.segments.size) // a non-tag line is taken as a segment URI: harmless, and never an exception
    }

    // --- playlist health ----------------------------------------------------

    private fun playlist(seq: Long, count: Int = 6, target: Double = 5.0, discontinuities: Int = 0, endList: Boolean = false) =
        LivePlaylist(seq, target, List(count) { LiveSegment("s${seq + it}.ts", target) }, discontinuities, endList)

    private fun warnings(notes: List<LiveStreamHealth.Note>) = notes.filter { it.level == Level.WARN }.map { it.text }

    @Test
    fun `a window advancing normally raises nothing`() {
        val h = LiveStreamHealth()
        assertEquals(emptyList<String>(), warnings(h.onPlaylist(playlist(100), 0)))
        assertEquals(emptyList<String>(), warnings(h.onPlaylist(playlist(101), 5_000)))
        assertEquals(emptyList<String>(), warnings(h.onPlaylist(playlist(102), 10_000)))
    }

    @Test
    fun `the live edge skipping segments is a gap`() {
        val h = LiveStreamHealth()
        h.onPlaylist(playlist(100), 0) // announces 100..105
        val w = warnings(h.onPlaylist(playlist(108), 5_000)) // next window starts at 108: 106 and 107 never seen
        assertTrue(w.toString(), w.any { it.contains("GAP") && it.contains("2 segment") })
    }

    @Test
    fun `overlapping or contiguous windows are not a gap`() {
        val h = LiveStreamHealth()
        h.onPlaylist(playlist(100), 0)
        assertEquals(emptyList<String>(), warnings(h.onPlaylist(playlist(106), 5_000))) // starts exactly where the last ended
    }

    @Test
    fun `a playlist that stops advancing is stale only after longer than the target`() {
        val h = LiveStreamHealth()
        h.onPlaylist(playlist(100), 0)
        assertEquals(emptyList<String>(), warnings(h.onPlaylist(playlist(100), 3_000)))
        val stale = warnings(h.onPlaylist(playlist(100), 9_000))
        assertTrue(stale.toString(), stale.any { it.contains("STALE") })
    }

    @Test
    fun `a playlist asked again after a long pause is a resume, not a gap or a late refresh`() {
        val h = LiveStreamHealth()
        h.onPlaylist(playlist(100), 0)
        val notes = h.onPlaylist(playlist(130), 150_000) // the player was stopped for 150 s: the window moved 30 segments
        assertEquals(emptyList<String>(), warnings(notes))
        assertTrue(notes.any { it.level == Level.INFO && it.text.contains("resume") })
        assertTrue(h.summary().contains("gaps=0"))
        assertTrue(h.summary().contains("resumes=1"))
    }

    @Test
    fun `a gap while the player is asking normally is still a gap`() {
        val h = LiveStreamHealth()
        h.onPlaylist(playlist(100), 0)
        h.onPlaylist(playlist(101), 5_000)
        assertTrue(warnings(h.onPlaylist(playlist(140), 10_000)).any { it.contains("GAP") })
    }

    @Test
    fun `a sequence that goes backwards means the source restarted`() {
        val h = LiveStreamHealth()
        h.onPlaylist(playlist(500), 0)
        assertTrue(warnings(h.onPlaylist(playlist(20), 5_000)).any { it.contains("BACKWARDS") })
    }

    @Test
    fun `a refresh that arrives later than the player needs is flagged`() {
        val h = LiveStreamHealth()
        h.onPlaylist(playlist(100), 0)
        assertTrue(warnings(h.onPlaylist(playlist(101), 9_000)).any { it.contains("LATE") })
    }

    @Test
    fun `a thin cushion, a discontinuity and an ENDLIST are each flagged`() {
        val h = LiveStreamHealth()
        assertTrue(warnings(h.onPlaylist(playlist(100, count = 2), 0)).any { it.contains("cushion LOW") })
        assertTrue(warnings(h.onPlaylist(playlist(101, discontinuities = 1), 5_000)).any { it.contains("DISCONTINUITY") })
        assertTrue(warnings(h.onPlaylist(playlist(102, discontinuities = 1, endList = true), 10_000)).any { it.contains("ENDLIST") })
    }

    // --- segment health -----------------------------------------------------

    private fun healthWithSegments(): LiveStreamHealth = LiveStreamHealth().also { it.onPlaylist(playlist(1), 0) }

    private val segmentBytes = 800L * 1024 // ~1.3 Mbps for a 5 s segment

    @Test
    fun `a healthy segment is only an info line`() {
        val h = healthWithSegments()
        val notes = h.onSegment("s1.ts", segmentBytes, ttfbMs = 200, copyMs = 400, wasCutOff = false)
        assertEquals(emptyList<String>(), warnings(notes))
        assertTrue(notes.first().text.contains("segment ok"))
    }

    @Test
    fun `a segment cut off in the middle is reported as such`() {
        val h = healthWithSegments()
        val w = warnings(h.onSegment("s1.ts", -1, ttfbMs = 300, copyMs = 90, wasCutOff = true))
        assertTrue(w.toString(), w.any { it.contains("CUT OFF") })
    }

    @Test
    fun `a segment the player closed itself is not a CDN cut`() {
        val h = healthWithSegments()
        val notes = h.onSegment("s1.ts", 300L * 1024, ttfbMs = 170, copyMs = 260, wasCutOff = false, playerGone = true)
        assertEquals(emptyList<String>(), warnings(notes))
        assertTrue(notes.first().text.contains("abandoned by the player"))
        assertTrue(h.summary().contains("cutOff=0"))
        assertTrue(h.summary().contains("abandoned=1"))
    }

    @Test
    fun `a complete segment far smaller than the rest is flagged as truncated`() {
        val h = healthWithSegments()
        listOf("s1.ts", "s2.ts", "s3.ts").forEach { h.onSegment(it, segmentBytes, 200, 400, false) } // builds the baseline
        val w = warnings(h.onSegment("s4.ts", 90L * 1024, 200, 400, false))
        assertTrue(w.toString(), w.any { it.contains("SHORT") })
    }

    @Test
    fun `no judgement of size before there is a baseline`() {
        val h = healthWithSegments()
        assertEquals(emptyList<String>(), warnings(h.onSegment("s1.ts", 90L * 1024, 200, 400, false)))
    }

    @Test
    fun `a segment that takes nearly as long to download as it lasts drains the buffer`() {
        val h = healthWithSegments()
        val w = warnings(h.onSegment("s1.ts", segmentBytes, ttfbMs = 500, copyMs = 4_000, wasCutOff = false)) // 4.5 s for 5 s
        assertTrue(w.toString(), w.any { it.contains("SLOW") })
    }

    @Test
    fun `a slow first byte is flagged even when the segment has no known duration`() {
        val h = LiveStreamHealth()
        val w = warnings(h.onSegment("unknown.ts", segmentBytes, ttfbMs = 2_500, copyMs = 300, wasCutOff = false))
        assertTrue(w.toString(), w.any { it.contains("slow first byte") })
    }

    @Test
    fun `the summary counts what was seen`() {
        val h = healthWithSegments()
        h.onSegment("s1.ts", segmentBytes, 200, 400, false)
        h.onSegment("s2.ts", -1, 200, 400, true)
        val summary = h.summary()
        assertTrue(summary, summary.contains("playlists=1"))
        assertTrue(summary, summary.contains("segments=2"))
        assertTrue(summary, summary.contains("cutOff=1"))
    }
}
