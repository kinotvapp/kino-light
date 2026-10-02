package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveSession
import com.arkiv.player.data.gateway.LiveSignature
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * `/live.ts` on [LiveHlsProxy]: the channel as ONE continuous MPEG-TS body for a renderer whose DLNA
 * list has a TS type and no HLS (ERRORES-AMF). Reused from `experiment/dlna-live-ts`.
 */
class LiveContinuousTsTest {
    private class FakeSignatures : SegmentSignature {
        override suspend fun sign(token: String) = LiveSignature(1000L, "sig")
        override fun rejected() {}
        override fun accepted() {}
    }

    private val servers = mutableListOf<MockWebServer>()
    private var proxy: LiveHlsProxy? = null

    @After fun tearDown() {
        proxy?.stop()
        ContinuousTsStreams.stopAll()
        servers.forEach { runCatching { it.shutdown() } }
    }

    /** An origin whose playlist for the n-th request is `windows[min(n, last)]` (media sequence to segment numbers). */
    private fun origin(windows: List<Pair<Int, List<Int>>>, missing: Set<Int> = emptySet()): MockWebServer {
        val server = MockWebServer()
        val playlists = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.endsWith(".m3u8")) {
                    val (seq, segs) = windows[minOf(playlists.getAndIncrement(), windows.lastIndex)]
                    val body = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$seq\n" +
                        segs.joinToString("") { "#EXTINF:1,\nhttp://${server.hostName}:${server.port}/seg/$it.ts\n" }
                    return MockResponse().setBody(body)
                }
                val n = path.substringAfter("/seg/").substringBefore(".ts").toIntOrNull()
                return if (n == null || n in missing) MockResponse().setResponseCode(404) else MockResponse().setBody("<$n>")
            }
        }
        server.start()
        servers += server
        return server
    }

    private fun proxyFor(origin: MockWebServer): LiveHlsProxy {
        val p = LiveHlsProxy(FakeSignatures(), dns = okhttp3.Dns.SYSTEM, continuousIdleMs = 1_200L)
        proxy = p
        p.start()
        p.urlFor(LiveSession("${origin.hostName}:${origin.port}", "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0))
        return p
    }

    private fun get(url: String, method: String = "GET"): Triple<Int, HttpURLConnection, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        val code = c.responseCode
        val body = runCatching { c.inputStream.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
        return Triple(code, c, body)
    }

    @Test fun `the window's segments come back to back as one body of the type the TV listed, with DLNA headers`() {
        val p = proxyFor(origin(listOf(10 to listOf(10, 11, 12))))
        val (code, c, body) = get(p.lanTsUrl("127.0.0.1", "video/mpeg")!!)
        assertEquals(200, code)
        assertEquals("video/mpeg", c.contentType)
        assertEquals("Streaming", c.getHeaderField("transferMode.dlna.org"))
        assertTrue(c.getHeaderField("contentFeatures.dlna.org").startsWith("DLNA.ORG_OP=00"))
        assertNull("no length: it never ends", c.getHeaderField("Content-Length"))
        assertEquals("<10><11><12>", body)
    }

    @Test fun `a long window starts near its live edge, and what the window adds later follows once`() {
        val p = proxyFor(origin(listOf(10 to (10..17).toList(), 11 to (11..18).toList(), 12 to (12..19).toList())))
        assertEquals("<15><16><17><18><19>", get(p.lanTsUrl("127.0.0.1", "video/mp2t")!!).third)
    }

    @Test fun `a segment that never arrives is skipped and the body goes on`() {
        val p = proxyFor(origin(listOf(10 to listOf(10, 11, 12)), missing = setOf(11)))
        assertEquals("<10><12>", get(p.lanTsUrl("127.0.0.1", "video/mpeg")!!).third)
    }

    @Test fun `it needs the session token like every other route`() {
        val p = proxyFor(origin(listOf(10 to listOf(10))))
        val url = p.lanTsUrl("127.0.0.1", "video/mpeg")!!.replace(Regex("t=[0-9a-f]+"), "t=wrong")
        assertEquals(403, get(url).first)
    }

    @Test fun `no CDN serving the playlist is a 502, not an empty 200`() {
        val dead = MockWebServer().also {
            it.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404) }
            it.start()
        }
        servers += dead
        val p = proxyFor(dead)
        assertEquals(502, get(p.lanTsUrl("127.0.0.1", "video/mpeg")!!).first)
    }

    @Test fun `a HEAD probe gets the headers at once, with no body and no work for the origin`() {
        val origin = origin(listOf(10 to listOf(10, 11, 12)))
        val p = proxyFor(origin)
        val (code, c, body) = get(p.lanTsUrl("127.0.0.1", "video/mpeg")!!, method = "HEAD")
        assertEquals(200, code)
        assertEquals("video/mpeg", c.contentType)
        assertEquals("", body)
        assertEquals("the origin is never asked for a probe", 0, origin.requestCount)
    }

    @Test fun `the proxy stopping ends the body`() {
        val p = proxyFor(origin(listOf(10 to (10..12).toList(), 11 to (11..13).toList(), 12 to (12..14).toList(), 13 to (13..15).toList())))
        val url = p.lanTsUrl("127.0.0.1", "video/mpeg")!!
        val t = Thread { Thread.sleep(700); p.stop() }.apply { start() }
        val started = System.currentTimeMillis()
        get(url)
        t.join()
        assertTrue("ended well before the idle limit", System.currentTimeMillis() - started < 3_000L)
    }

    @Test fun `there is no url until a channel is open`() {
        assertNull(LiveHlsProxy(FakeSignatures(), dns = okhttp3.Dns.SYSTEM).lanTsUrl("127.0.0.1", "video/mpeg"))
    }
}
