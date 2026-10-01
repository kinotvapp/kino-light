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
}
