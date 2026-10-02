package com.arkiv.player.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/**
 * [ContinuousTs] and [ContinuousTsAssembler]: an HLS stream of TS segments written as ONE TS body
 * for a DLNA renderer that lists a TS type and no HLS (ERRORES-AMF). Fake playlists and segments:
 * the order, the dedupe, the live edge, the stops.
 */
class ContinuousTsTest {

    @After fun tearDown() = ContinuousTsStreams.stopAll()

    private fun media(seq: Long, segs: List<Int>, ended: Boolean = false, durMs: Long = 6_000L) =
        ContinuousTs.Playlist.Media(seq, segs.map { ContinuousTs.Segment("http://cdn/$it.ts", durMs) }, ended, 6_000L)

    /** A source whose n-th playlist is `windows[min(n, last)]`; segment `n` is the bytes `<n>`, [missing] are not served. */
    private class Source(val windows: List<ContinuousTs.Playlist.Media?>, val missing: Set<Int> = emptySet()) {
        var asked = 0
        val opened = mutableListOf<Int>()
        fun window(): ContinuousTs.Playlist.Media? = windows[minOf(asked++, windows.lastIndex)]
        fun open(url: String): java.io.InputStream? {
            val n = url.substringAfterLast('/').substringBefore(".ts").toInt()
            opened += n
            return if (n in missing) null else ByteArrayInputStream("<$n>".toByteArray())
        }
    }

    private class Clock(var now: Long = 0L)

    private fun assembler(src: Source, clock: Clock = Clock(), fromMs: Long = 0L, idleMs: Long = 20_000L) = ContinuousTsAssembler(
        window = src::window,
        openSegment = src::open,
        fromMs = fromMs,
        idleMs = idleMs,
        clock = { clock.now },
        sleep = { clock.now += it },
    )

    private fun run(a: ContinuousTsAssembler, active: () -> Boolean = { true }): Pair<ContinuousTsAssembler.Outcome, String> {
        val out = ByteArrayOutputStream()
        var head = 0
        val outcome = a.run(out, { head++ }, active)
        if (outcome.end != ContinuousTsAssembler.End.NO_PLAYLIST) assertEquals("the head is written once", 1, head)
        return outcome to out.toString()
    }

    // ------------------------------------------------------------------ the assembler

    @Test fun `a live window starts a few segments before its edge, not at its oldest segment`() {
        val (outcome, body) = run(assembler(Source(listOf(media(10, (10..17).toList())))))
        assertTrue(body.startsWith("<15><16><17>"))
        assertEquals(ContinuousTsAssembler.End.IDLE, outcome.end)
        assertEquals(3, outcome.written)
    }

    @Test fun `segments the window adds later follow in order, and none is written twice`() {
        val src = Source(listOf(media(10, listOf(10, 11, 12)), media(11, listOf(11, 12, 13)), media(12, listOf(12, 13, 14)), media(14, listOf(14, 15, 16))))
        val (_, body) = run(assembler(src))
        assertEquals("<10><11><12><13><14><15><16>", body)
    }

    @Test fun `a window that did not move is asked again without writing anything`() {
        val src = Source(listOf(media(10, listOf(10, 11, 12)), media(10, listOf(10, 11, 12)), media(11, listOf(11, 12, 13))))
        assertEquals("<10><11><12><13>", run(assembler(src)).second)
    }

    @Test fun `a segment that never arrives is skipped and the body goes on`() {
        val (outcome, body) = run(assembler(Source(listOf(media(10, listOf(10, 11, 12))), missing = setOf(11))))
        assertEquals("<10><12>", body)
        assertEquals(1, outcome.skipped)
    }

    @Test fun `a window that moved past what was not written yet jumps to its oldest segment`() {
        val src = Source(listOf(media(10, listOf(10, 11, 12)), media(20, listOf(20, 21, 22))))
        assertEquals("<10><11><12><20><21><22>", run(assembler(src)).second)
    }

    @Test fun `a playlist that restarts its numbering goes back to its edge instead of waiting forever`() {
        val src = Source(listOf(media(500, listOf(500, 501, 502)), media(0, listOf(0, 1, 2, 3, 4))))
        assertEquals("<500><501><502><2><3><4>", run(assembler(src)).second)
    }

    @Test fun `a window not served this time keeps the last one, and nothing new for long ends the body`() {
        val clock = Clock()
        val src = Source(listOf(media(10, listOf(10)), null))
        val (outcome, body) = run(assembler(src, clock, idleMs = 5_000L))
        assertEquals("<10>", body)
        assertEquals(ContinuousTsAssembler.End.IDLE, outcome.end)
        assertTrue(clock.now in 5_000L..8_000L)
    }

    @Test fun `no playlist at all writes nothing, not even the head`() {
        val (outcome, body) = run(assembler(Source(listOf(null))))
        assertEquals(ContinuousTsAssembler.End.NO_PLAYLIST, outcome.end)
        assertEquals("", body)
    }

    @Test fun `a finite playlist starts at the segment holding the position and ends after its last`() {
        val src = Source(listOf(media(0, (0..9).toList(), ended = true, durMs = 10_000L)))
        val (outcome, body) = run(assembler(src, fromMs = 35_000L))
        assertEquals("<3><4><5><6><7><8><9>", body)
        assertEquals(ContinuousTsAssembler.End.ENDED, outcome.end)
        assertEquals("asked once: it is finite", 1, src.asked)
    }

    @Test fun `it stops when told to, between segments`() {
        val src = Source(listOf(media(10, (10..20).toList(), ended = true)))
        val out = ByteArrayOutputStream()
        val outcome = assembler(src).run(out, {}) { src.opened.size < 2 }
        assertEquals(ContinuousTsAssembler.End.STOPPED, outcome.end)
        assertEquals("<10>", out.toString())
    }

    @Test fun `the TV hanging up ends the body at the next write`() {
        val src = Source(listOf(media(10, listOf(10, 11, 12), ended = true)))
        var writes = 0
        val out = object : OutputStream() {
            override fun write(b: Int) = throw IOException("gone")
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (++writes > 1) throw IOException("broken pipe")
            }
        }
        val outcome = assembler(src).run(out, {}) { true }
        assertEquals(ContinuousTsAssembler.End.CLIENT_GONE, outcome.end)
        assertEquals(listOf(10, 11), src.opened)
    }

    // ------------------------------------------------------------------ the playlist

    @Test fun `a media playlist of TS segments is read with its sequence, durations and end`() {
        val p = ContinuousTs.parse(
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:42\n#EXTINF:4.0,\nseg42.ts?x=1\n#EXTINF:3.5,\n/abs/seg43.ts\n#EXT-X-ENDLIST\n",
            "http://cdn.example/live/chan.m3u8",
        ) as ContinuousTs.Playlist.Media
        assertEquals(42L, p.firstSequence)
        assertEquals(listOf("http://cdn.example/live/seg42.ts?x=1", "http://cdn.example/abs/seg43.ts"), p.segments.map { it.url })
        assertEquals(listOf(4_000L, 3_500L), p.segments.map { it.durationMs })
        assertTrue(p.ended)
        assertEquals(4_000L, p.targetMs)
    }

    @Test fun `what one TS body cannot carry is refused before anything is written`() {
        val base = "http://cdn.example/a.m3u8"
        assertTrue(ContinuousTs.parse("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\na.m4s\n", base) is ContinuousTs.Playlist.Unusable)
        assertTrue(ContinuousTs.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts\n", base) is ContinuousTs.Playlist.Unusable)
        assertTrue(ContinuousTs.parse("#EXTM3U\n#EXTINF:4,\na.m4s\n", base) is ContinuousTs.Playlist.Unusable)
        assertTrue(ContinuousTs.parse("<html>nope</html>", base) is ContinuousTs.Playlist.Unusable)
        assertTrue(ContinuousTs.parse("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\na.ts\n", base) is ContinuousTs.Playlist.Media)
    }

    @Test fun `a master playlist follows its best variant up to 1080p`() {
        val master = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhd.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160\nuhd.m3u8\n"
        assertEquals(ContinuousTs.Playlist.Master("http://cdn.example/hd.m3u8"), ContinuousTs.parse(master, "http://cdn.example/master.m3u8"))
    }

    @Test fun `a master whose audio is a separate rendition is refused`() {
        val master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"es\",URI=\"audio.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO=\"a\"\nv.m3u8\n"
        assertTrue(ContinuousTs.parse(master, "http://cdn.example/m.m3u8") is ContinuousTs.Playlist.Unusable)
    }

    // ------------------------------------------------------------------ the type and the headers

    @Test fun `the type is the TS one the renderer lists, plain TS first`() {
        val philipsNmr = listOf("audio/mpeg", "image/jpeg", "video/mpeg", "video/vnd.dlna.mpeg-tts", "audio/x-ms-wma")
        assertEquals("video/mpeg", ContinuousTs.mimeFor(philipsNmr))
        assertEquals("video/mp2t", ContinuousTs.mimeFor(listOf("video/vnd.dlna.mpeg-tts", "video/mp2t")))
        assertEquals("video/vnd.dlna.mpeg-tts", ContinuousTs.mimeFor(listOf("video/vnd.dlna.mpeg-tts")))
        assertNull(ContinuousTs.mimeFor(listOf("video/mp4", "audio/mpeg")))
        assertEquals("video/mpeg", ContinuousTs.mimeAt(ContinuousTs.indexOf("video/mpeg")))
        assertEquals("anything else is video/mpeg", "video/mpeg", ContinuousTs.mimeAt(99))
    }

    @Test fun `the head is a live DLNA stream with no length and no ranges`() {
        val head = ContinuousTs.responseHead("video/mpeg")
        assertTrue(head.contains("Content-Type: video/mpeg\r\n"))
        assertTrue(head.contains("transferMode.dlna.org: Streaming\r\n"))
        assertTrue(head.contains("DLNA.ORG_OP=00"))
        assertTrue(head.contains("DLNA.ORG_FLAGS=0D7"))
        assertFalse(head.contains("Content-Length"))
        assertTrue(head.endsWith("\r\n\r\n"))
    }

    // ------------------------------------------------------------------ the registry

    @Test fun `a stopped cast closes every body, and a reconnecting TV cannot pile them up`() {
        val closed = mutableListOf<Int>()
        val ids = (1..3).map { n -> ContinuousTsStreams.open(Closeable { closed += n }) }
        assertEquals("past two, the oldest is closed", listOf(1), closed)
        assertFalse(ContinuousTsStreams.isOpen(ids[0]))
        assertTrue(ContinuousTsStreams.isOpen(ids[2]))
        ContinuousTsStreams.stopAll()
        assertEquals(listOf(1, 2, 3), closed.sorted())
        assertEquals(0, ContinuousTsStreams.size)
    }

    @Test fun `a body served through the registry stops when the cast stops`() {
        val src = Source(listOf(media(10, (10..1000).toList(), ended = true)))
        val out = object : OutputStream() {
            var n = 0
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (++n == 5) ContinuousTsStreams.stopAll()
            }
        }
        val outcome = ContinuousTsStreams.serve(Closeable {}, out, "video/mpeg", assembler(src)) { true }
        assertEquals(ContinuousTsAssembler.End.STOPPED, outcome.end)
        assertTrue(src.opened.size < 10)
        assertEquals(0, ContinuousTsStreams.size)
    }
}
