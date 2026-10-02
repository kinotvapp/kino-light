package com.arkiv.player.playback

import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginStreamHttp
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * `/t/<token>/live.ts` on [PluginCastProxy]: a plugin's HLS as one continuous TS body for a DLNA
 * renderer with no HLS in its list, through the plugin's gated client and headers, under its token.
 */
class PluginContinuousTsTest {
    private val origin = MockWebServer()
    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    }
    private val proxy = PluginCastProxy(
        clientFor = { hosts -> PluginStreamHttp.client(OkHttpClient(), hosts, allowInsecureLocalhost = true, delegateDns = dns) },
        log = {},
    )
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val seen = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @Before fun setUp() {
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/master.m3u8") -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720\nvod.m3u8\n",
                    )
                    path.startsWith("/vod.m3u8") -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                            (0..5).joinToString("") { "#EXTINF:10,\ns$it.ts\n" } + "#EXT-X-ENDLIST\n",
                    )
                    path.startsWith("/fmp4.m3u8") -> MockResponse().setBody("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\na.m4s\n")
                    path.startsWith("/s") -> MockResponse().setBody("<${path.substringAfter("/s").substringBefore(".ts")}>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        origin.start()
        proxy.start()
    }

    @After fun tearDown() {
        proxy.stop()
        ContinuousTsStreams.stopAll()
        origin.shutdown()
    }

    private fun register(path: String) = proxy.register(
        "plugin:p:m1::0", "http://localhost:${origin.port}$path", mapOf("Referer" to "https://site.example/"),
        EffectiveHosts(listOf("localhost")), PluginCastProxy.Shape.HLS, PluginCastProxy.MIME_HLS,
    )!!

    private fun get(url: String, method: String = "GET") =
        http.newCall(Request.Builder().url(url).method(method, null).build()).execute()

    @Test fun `a finite playlist is one body from the person's position to its end, with the plugin's headers`() {
        val url = PluginCastProxy.continuousUrlOf(register("/vod.m3u8"), "video/mpeg", fromMs = 25_000L)!!
        get(url).use { r ->
            assertEquals(200, r.code)
            assertEquals("video/mpeg", r.header("Content-Type"))
            assertEquals("Streaming", r.header("transferMode.dlna.org"))
            assertEquals("<2><3><4><5>", r.body!!.string())
        }
        assertTrue(seen.all { it.getHeader("Referer") == "https://site.example/" })
    }

    @Test fun `a master playlist follows its variant`() {
        val url = PluginCastProxy.continuousUrlOf(register("/master.m3u8"), "video/mp2t", fromMs = 0L)!!
        get(url).use { r -> assertEquals("<0><1><2><3><4><5>", r.body!!.string()) }
    }

    @Test fun `fMP4 segments cannot be one TS body, so a 502 comes before any header`() {
        val url = PluginCastProxy.continuousUrlOf(register("/fmp4.m3u8"), "video/mpeg", fromMs = 0L)!!
        get(url).use { r -> assertEquals(502, r.code) }
    }

    @Test fun `a wrong token is a 403 and a HEAD costs the origin nothing`() {
        val url = PluginCastProxy.continuousUrlOf(register("/vod.m3u8"), "video/mpeg", fromMs = 0L)!!
        get(url.replace(Regex("/t/[0-9a-f]+/"), "/t/${"0".repeat(32)}/")).use { r -> assertEquals(403, r.code) }
        get(url, method = "HEAD").use { r ->
            assertEquals(200, r.code)
            assertEquals("video/mpeg", r.header("Content-Type"))
        }
        assertEquals(0, seen.size)
    }

    @Test fun `only this proxy's playlist url has a continuous twin`() {
        assertNull(PluginCastProxy.continuousUrlOf("http://192.168.1.5:4000/t/abc/media.mp4", "video/mpeg", 0L))
        assertEquals(
            "http://192.168.1.5:4000/t/abc/live.ts?m=1&s=0",
            PluginCastProxy.continuousUrlOf("http://192.168.1.5:4000/t/abc/index.m3u8", "video/mpeg", -5L),
        )
    }
}
