package com.arkiv.player.playback

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/** What the proxy offers a Cast receiver: the TS playlist only when it can be built, and CORS. */
class ArchiveCacheProxyCastTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy
    private val total = 1_000_000L

    @Before
    fun setUp() {
        origin = MockWebServer()
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range == "bytes=0-0") {
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-0/$total").setBody("x")
                }
                // Anything else (the duration probe's ends) fails, like the slow CDN did.
                return MockResponse().setResponseCode(503)
            }
        }
        origin.start()
        proxy = ArchiveCacheProxy(temp.newFolder("cache"), ProxyOriginGuard(allowLoopback = true))
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        origin.shutdown()
    }

    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000; readTimeout = 20000
        }

    @Test
    fun `without a duration the playlist is not offered, with the phone's duration it is built`() {
        val url = proxy.proxyUrl(origin.url("/vod/a_media.ts").toString(), mapOf("Content-Auth" to "k"), direct = true)
        assertFalse(proxy.canServePlaylist(url))
        proxy.rememberDuration(url, -9_223_372_036_854_775_807L) // C.TIME_UNSET: ignored
        assertFalse(proxy.canServePlaylist(url))
        proxy.rememberDuration(url, 7_667_166L)
        assertTrue(proxy.canServePlaylist(url))
        val c = open(url.removeSuffix("/s") + "/hls.m3u8")
        assertEquals(200, c.responseCode)
        val body = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(body, body.startsWith("#EXTM3U"))
        assertTrue(body, body.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun `an unknown or revoked stream is never playlist-able`() {
        assertFalse(proxy.canServePlaylist("http://127.0.0.1:1/t/0123456789abcdef0123456789abcdef/s"))
        val url = proxy.proxyUrl(origin.url("/vod/b_media.ts").toString(), emptyMap(), direct = true)
        proxy.rememberDuration(url, 1_000L)
        proxy.revoke(url)
        assertFalse(proxy.canServePlaylist(url))
    }

    @Test
    fun `a receiver's preflight is answered for a valid token, and only for one`() {
        val url = proxy.proxyUrl(origin.url("/vod/c_media.ts").toString(), emptyMap(), direct = true)
        val ok = open(url, "OPTIONS")
        assertEquals(204, ok.responseCode)
        assertEquals("*", ok.getHeaderField("Access-Control-Allow-Origin"))
        assertTrue(ok.getHeaderField("Access-Control-Allow-Methods").contains("GET"))
        val bad = open(url.replace(Regex("/t/[0-9a-f]+/"), "/t/0123456789abcdef0123456789abcdef/"), "OPTIONS")
        assertEquals(403, bad.responseCode)
    }

    @Test
    fun `a ranged media response carries CORS so the receiver can read it`() {
        val url = proxy.proxyUrl(origin.url("/vod/d_media.mp4").toString(), emptyMap(), direct = true)
        val c = open(url).apply { setRequestProperty("Range", "bytes=0-0") }
        assertEquals(206, c.responseCode)
        assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
        assertTrue(c.getHeaderField("Access-Control-Expose-Headers").contains("Content-Range"))
    }

    @Test
    fun `the phone's latest duration wins over its first estimate`() {
        val url = proxy.proxyUrl(origin.url("/vod/d_media.ts").toString(), emptyMap(), direct = true)
        proxy.rememberDuration(url, 60_000L) // an early estimate
        proxy.rememberDuration(url, 7_667_166L) // what the player settled on
        val body = open(url.removeSuffix("/s") + "/hls.m3u8").inputStream.readBytes().toString(Charsets.UTF_8)
        val seconds = Regex("#EXTINF:([0-9.]+),").findAll(body).sumOf { it.groupValues[1].toDouble() }
        assertEquals(7_667.166, seconds, 1.0)
    }
}
