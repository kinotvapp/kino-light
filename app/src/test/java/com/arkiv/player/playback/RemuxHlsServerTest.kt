package com.arkiv.player.playback

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The remux served over HTTP as the receiver sees it, while it grows and once it is done. */
class RemuxHlsServerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = RemuxHlsServer(lanIp = { "127.0.0.1" })

    @After fun tearDown() = server.stop()

    private class Got(val code: Int, val body: ByteArray, val length: Long, val headers: Map<String, List<String>>)

    private fun get(url: String, method: String = "GET"): Got {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        val code = c.responseCode
        val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.readBytes() }.getOrNull() ?: ByteArray(0)
        return Got(code, body, c.getHeaderField("Content-Length")?.toLong() ?: -1, c.headerFields.filterKeys { it != null })
    }

    /** The fixture written as the remux writes it: a `.part` that grows, renamed when done. */
    private inner class GrowingRemux {
        val source: File = Fmp4Fixture.copyTo(tmp.newFolder())
        val bytes = source.readBytes()
        val part = File(tmp.root, "x.mp4.part")
        val done = File(tmp.root, "x.mp4")
        fun grow(to: Int) = part.writeBytes(bytes.copyOfRange(0, to))
        fun finish() { part.writeBytes(bytes); assertTrue(part.renameTo(done)) }
        val locate: () -> Pair<File, Boolean>? = {
            when {
                done.exists() -> done to true
                part.exists() -> part to false
                else -> null
            }
        }
    }

    @Test
    fun `the playlist grows with the remux and gets its end only once the remux is done`() {
        val remux = GrowingRemux()
        val index = Fmp4Fixture.indexOf(remux.source)
        // Up to the middle of the third fragment: two whole fragments (3 s + 3 s) = one segment.
        remux.grow(((index.fragments[2].start + index.fragments[2].end) / 2).toInt())
        val master = server.serve("key", remux.locate)!!
        val media = master.replace("master.m3u8", "media.m3u8")

        val m = String(get(master).body)
        assertTrue(m, m.contains("CODECS=\"avc1.F4000A,mp4a.40.2\""))
        val growing = String(get(media).body)
        assertTrue(growing.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        assertEquals(1, Regex("\\.m4s").findAll(growing).count())
        assertFalse(growing.contains("#EXT-X-ENDLIST"))
        assertEquals(6.0, server.availableSec("key"), 0.001)
        assertFalse(server.isComplete("key"))

        remux.finish()
        val done = String(get(media).body)
        assertTrue(done.startsWith(growing.substringBefore("#EXT-X-PLAYLIST-TYPE")))
        assertTrue(done.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertEquals(2, Regex("\\.m4s").findAll(done).count())
        assertTrue(done.trimEnd().endsWith("#EXT-X-ENDLIST"))
        assertTrue(server.isComplete("key"))
        assertEquals(8.0, server.availableSec("key"), 0.001)
    }

    @Test
    fun `init and segments are what the rewrite produces, with an honest Content-Length`() {
        val remux = GrowingRemux()
        remux.finish()
        val base = server.serve("key", remux.locate)!!.removeSuffix("master.m3u8")
        get(base + "media.m3u8")
        val index = Fmp4Fixture.indexOf(remux.done)
        val segments = RemuxHls.segments(index.fragments, complete = true)
        val init = get(base + "init.mp4")
        assertEquals(200, init.code)
        assertEquals(init.body.size.toLong(), init.length)
        segments.forEachIndexed { i, s ->
            val got = get(base + "s$i.m4s")
            assertEquals(200, got.code)
            assertEquals(got.body.size.toLong(), got.length)
            val (expectedInit, expectedMedia) = Fmp4Fixture.segmentBytes(remux.done, index, s)
            assertArrayEquals(expectedInit, init.body)
            assertArrayEquals(expectedMedia, got.body)
        }
        // A receiver fetches cross-origin.
        assertEquals("*", get(base + "media.m3u8").headers["Access-Control-Allow-Origin"]?.single())
        // HEAD: the length with no body.
        assertEquals(init.length, get(base + "init.mp4", "HEAD").length)
    }

    @Test
    fun `whoever lacks the token gets nothing`() {
        val remux = GrowingRemux()
        remux.finish()
        val master = server.serve("key", remux.locate)!!
        assertEquals(200, get(master).code)
        assertEquals(404, get(master.replace(Regex("/r/[0-9a-f]+/"), "/r/0123456789abcdef0123456789abcdef/")).code)
        assertEquals(404, get(master.substringBefore("/r/") + "/file").code)
        assertEquals(404, get(master.replace("master.m3u8", "s99.m4s")).code)
    }

    @Test
    fun `the same remux keeps its url, another one gets a new token on the same port`() {
        val remux = GrowingRemux()
        remux.finish()
        val first = server.serve("key", remux.locate)
        assertEquals(first, server.serve("key", remux.locate))
        val other = server.serve("other", remux.locate)
        assertNotNull(other)
        assertFalse(first == other)
        assertEquals(URL(first).port, URL(other).port)
        assertEquals(404, get(first!!).code)
        assertTrue(server.isServing("other"))
        assertFalse(server.isServing("key"))
    }

    @Test
    fun `a url whose token or port is gone is known as revoked, anything else is not this server's call`() {
        val remux = GrowingRemux()
        remux.finish()
        val first = server.serve("key", remux.locate)!!
        assertFalse(server.revoked(first))
        val other = server.serve("other", remux.locate)!!
        assertTrue(server.revoked(first)) // another title took the token
        assertFalse(server.revoked(other))
        server.stop()
        assertTrue(server.revoked(other)) // the session ended: socket and token gone
        // Not a remux url at all (a plugin or Magis proxy token): not this server's to judge.
        assertFalse(server.revoked("http://192.168.2.11:43471/t/0123456789abcdef0123456789abcdef/media.mp4"))
        assertFalse(server.revoked("not a url"))
    }

    @Test
    fun `the remux an earlier cast left is served at once and the new one takes over where it ends`() {
        val remux = GrowingRemux()
        val index = Fmp4Fixture.indexOf(remux.source)
        val full = RemuxHls.segments(index.fragments, complete = true)
        // The earlier cast stopped after the first segment's fragments.
        val cut = index.fragments[full[0].last].end.toInt()
        val leftover = File(tmp.root, "x.mp4.prev").apply { writeBytes(remux.bytes.copyOfRange(0, cut)) }
        val reusing = RemuxHlsServer(lanIp = { "127.0.0.1" }, leftoverOf = { if (it == "key") leftover else null })
        try {
            remux.grow(index.initEnd.toInt()) // the new run has only written its header
            val master = reusing.serve("key", remux.locate)!!
            val media = master.replace("master.m3u8", "media.m3u8")
            assertEquals(full[0].durationSec, reusing.availableSec("key"), 1e-6)
            val waiting = String(get(media).body)
            assertEquals(1, Regex("\\.m4s").findAll(waiting).count())
            assertFalse(waiting.contains("#EXT-X-ENDLIST"))
            val (init, s0) = Fmp4Fixture.segmentBytes(remux.source, index, full[0])
            assertArrayEquals(init, get(master.replace("master.m3u8", "init.mp4")).body)
            assertArrayEquals(s0, get(master.replace("master.m3u8", "s0.m4s")).body)

            remux.finish() // the new run caught up and finished
            val done = String(get(media).body)
            assertEquals(full.size, Regex("\\.m4s").findAll(done).count())
            assertTrue(done.contains("#EXT-X-ENDLIST"))
            assertTrue(reusing.isComplete("key"))
            // Segments past the hand-over are the new run's, byte for byte what a single run serves.
            for (i in full.indices) {
                val (_, expected) = Fmp4Fixture.segmentBytes(remux.source, index, full[i])
                assertArrayEquals(expected, get(master.replace("master.m3u8", "s$i.m4s")).body)
            }
            // Same token rule as the rest: without it, nothing.
            assertEquals(404, get(master.replace(Regex("/r/[0-9a-f]+/"), "/r/0123456789abcdef0123456789abcdef/").replace("master.m3u8", "s0.m4s")).code)
        } finally {
            reusing.stop()
        }
    }

    @Test
    fun `an earlier remux is held back until the new run's header shows it is the same stream`() {
        val remux = GrowingRemux()
        val index = Fmp4Fixture.indexOf(remux.source)
        val full = RemuxHls.segments(index.fragments, complete = true)
        val leftover = File(tmp.root, "x.mp4.prev").apply { writeBytes(remux.bytes.copyOfRange(0, index.fragments[full[0].last].end.toInt())) }
        val reusing = RemuxHlsServer(lanIp = { "127.0.0.1" }, leftoverOf = { if (it == "key") leftover else null })
        try {
            reusing.serve("key", remux.locate)
            // The new run has written nothing yet: nothing is served, not even the head.
            assertEquals(0.0, reusing.availableSec("key"), 0.0)
            remux.grow(index.initEnd.toInt())
            assertEquals(full[0].durationSec, reusing.availableSec("key"), 1e-6)
        } finally {
            reusing.stop()
        }
    }

    @Test
    fun `an earlier remux whose codec setup differs from the new run's is dropped`() {
        val remux = GrowingRemux()
        val index = Fmp4Fixture.indexOf(remux.source)
        val full = RemuxHls.segments(index.fragments, complete = true)
        val prev = remux.bytes.copyOfRange(0, index.fragments[full[0].last].end.toInt())
        // One byte of the earlier remux's avcC changed: another decoder setup, another stream.
        val avcc = String(prev, Charsets.ISO_8859_1).indexOf("avcC")
        assertTrue(avcc > 0)
        prev[avcc + 6] = (prev[avcc + 6] + 1).toByte()
        val leftover = File(tmp.root, "x.mp4.prev").apply { writeBytes(prev) }
        val reusing = RemuxHlsServer(lanIp = { "127.0.0.1" }, leftoverOf = { if (it == "key") leftover else null })
        try {
            remux.grow(index.initEnd.toInt())
            reusing.serve("key", remux.locate)
            // Only the new run is served, and it has no whole segment yet.
            assertEquals(0.0, reusing.availableSec("key"), 0.0)
            remux.grow(remux.bytes.size)
            assertTrue(reusing.availableSec("key") > 0.0)
        } finally {
            reusing.stop()
        }
    }

    @Test
    fun `a remux nobody has planned a cast of is never paced`() {
        val remux = GrowingRemux()
        remux.grow(remux.bytes.size)
        server.serve("key", remux.locate)
        assertFalse(server.remuxShouldWait("key"))
        assertFalse(server.remuxShouldWait("another"))
        // Planned at 0:00 with an 8 s remux: nowhere near the lead, keeps going.
        server.planStart("key", 0L)
        assertFalse(server.remuxShouldWait("key"))
    }

    @Test
    fun `the playlist the TV reads starts where the cast was planned`() {
        val remux = GrowingRemux()
        remux.grow(remux.bytes.size)
        val media = server.serve("key", remux.locate)!!.replace("master.m3u8", "media.m3u8")
        server.planStart("key", 3_000L)
        assertTrue(String(get(media).body).contains("#EXT-X-START:TIME-OFFSET=3.000,PRECISE=YES"))
    }

    @Test
    fun `an earlier remux is only reused for its own key, and only when it holds something playable`() {
        val remux = GrowingRemux()
        val index = Fmp4Fixture.indexOf(remux.source)
        val leftover = File(tmp.root, "x.mp4.prev").apply { writeBytes(remux.bytes.copyOfRange(0, index.fragments[1].end.toInt())) }
        val junk = File(tmp.root, "junk.prev").apply { writeBytes(remux.bytes.copyOfRange(0, index.initEnd.toInt() + 10)) }
        val reusing = RemuxHlsServer(
            lanIp = { "127.0.0.1" },
            leftoverOf = { when (it) { "key" -> leftover; "junk" -> junk; else -> null } },
        )
        try {
            reusing.serve("other", remux.locate)
            assertEquals(0.0, reusing.availableSec("other"), 0.0)
            reusing.serve("junk", remux.locate)
            assertEquals(0.0, reusing.availableSec("junk"), 0.0)
            remux.grow(index.initEnd.toInt())
            reusing.serve("key", remux.locate)
            assertTrue(reusing.availableSec("key") > 0.0)
        } finally {
            reusing.stop()
        }
    }

    @Test
    fun `a remux that started over is re-indexed instead of served from stale offsets`() {
        val remux = GrowingRemux()
        remux.grow(remux.bytes.size)
        val media = server.serve("key", remux.locate)!!.replace("master.m3u8", "media.m3u8")
        assertEquals(1, Regex("\\.m4s").findAll(String(get(media).body)).count())
        remux.grow(1000) // the restarted export has only just begun
        assertEquals(404, get(media).code)
        assertEquals(0.0, server.availableSec("key"), 0.0)
        remux.grow(remux.bytes.size)
        assertEquals(1, Regex("\\.m4s").findAll(String(get(media).body)).count())
    }

    @Test
    fun `an earlier remux that covers the planned start is served before the new run writes anything`() {
        val remux = GrowingRemux()
        val index = Fmp4Fixture.indexOf(remux.source)
        val full = RemuxHls.segments(index.fragments, complete = true)
        val leftover = File(tmp.root, "x.mp4.prev").apply { writeBytes(remux.bytes.copyOfRange(0, index.fragments[full[0].last].end.toInt())) }
        // A lead the fixture's few seconds can cover.
        val reusing = RemuxHlsServer(lanIp = { "127.0.0.1" }, leftoverOf = { if (it == "key") leftover else null }, headLeadSec = 1.0)
        try {
            val master = reusing.serve("key", remux.locate)!!
            // Nothing planned yet: held back, as before.
            assertEquals(0.0, reusing.availableSec("key"), 0.0)
            // Planned inside the head, lead included: served at once, no header from the new run needed.
            reusing.planStart("key", 0L)
            assertEquals(full[0].durationSec, reusing.availableSec("key"), 1e-6)
            val (init, s0) = Fmp4Fixture.segmentBytes(remux.source, index, full[0])
            assertArrayEquals(init, get(master.replace("master.m3u8", "init.mp4")).body)
            assertArrayEquals(s0, get(master.replace("master.m3u8", "s0.m4s")).body)
            // Planned past what the head holds: waits for the new run again.
            reusing.planStart("key", (full[0].durationSec * 1000).toLong())
            assertEquals(0.0, reusing.availableSec("key"), 0.0)
        } finally {
            reusing.stop()
        }
    }
}
