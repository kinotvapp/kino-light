package com.arkiv.player.playback

import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginStreamHttp
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * [PluginCastProxy]: a TV pulls a plugin stream through the phone, and every upstream request meets
 * the plugin's own gate (declared hosts, private-address refusal, redirects) with its headers; the
 * TV holds only a token, and nothing secret reaches a log.
 */
class PluginCastProxyTest {
    private val origin = MockWebServer()
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every name resolves to [answer] (loopback = the MockWebServer). */
    private var answer: (String) -> List<InetAddress> = { listOf(InetAddress.getLoopbackAddress()) }
    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = answer(hostname)
    }

    private val proxy = PluginCastProxy(
        clientFor = { hosts -> PluginStreamHttp.client(OkHttpClient(), hosts, allowInsecureLocalhost = true, delegateDns = dns) },
        log = { logs += it },
    )
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    private val secretHeaders = mapOf("Referer" to "https://site.example/secret-ref", "Cookie" to "sid=TOPSECRET")

    @Before fun setUp() {
        origin.start()
        proxy.start()
    }

    @After fun tearDown() {
        proxy.stop()
        origin.shutdown()
    }

    private fun originUrl(path: String) = "http://localhost:${origin.port}$path"
    private val localhostHosts = EffectiveHosts(listOf("localhost"))

    private fun register(
        url: String = originUrl("/movie.mp4?sig=abc"),
        headers: Map<String, String> = secretHeaders,
        hosts: EffectiveHosts = localhostHosts,
        key: String = "plugin:p:m1::0",
    ) = proxy.register(key, url, headers, hosts, PluginCastProxy.Shape.FILE, "video/mp4")!!

    private fun get(url: String, range: String? = null, method: String = "GET") =
        http.newCall(Request.Builder().url(url).method(method, null).apply { range?.let { header("Range", it) } }.build()).execute()

    @Test fun `a file is relayed with the plugin's headers and the renderer's range`() {
        origin.enqueue(
            MockResponse().setResponseCode(206).setBody("0123")
                .setHeader("Content-Range", "bytes 10-13/100").setHeader("Content-Type", "application/octet-stream"),
        )
        val url = register()
        assertTrue(url.startsWith("http://127.0.0.1:${proxy.port}/t/"))
        assertTrue(url.endsWith("/media.mp4"))
        get(url, range = "bytes=10-13").use { r ->
            assertEquals(206, r.code)
            assertEquals("0123", r.body!!.string())
            assertEquals("bytes 10-13/100", r.header("Content-Range"))
            // A generic upstream type is replaced by the container the decision named.
            assertEquals("video/mp4", r.header("Content-Type"))
            assertEquals("*", r.header("Access-Control-Allow-Origin"))
        }
        val seen = origin.takeRequest()
        assertEquals("/movie.mp4?sig=abc", seen.path)
        assertEquals("https://site.example/secret-ref", seen.getHeader("Referer"))
        assertEquals("sid=TOPSECRET", seen.getHeader("Cookie"))
        assertEquals("bytes=10-13", seen.getHeader("Range"))
        assertEquals("identity", seen.getHeader("Accept-Encoding"))
    }

    @Test fun `HEAD is answered without a body`() {
        origin.enqueue(MockResponse().setHeader("Content-Length", "100").setHeader("Content-Type", "video/mp4"))
        get(register(), method = "HEAD").use { r ->
            assertEquals(200, r.code)
            assertEquals("100", r.header("Content-Length"))
        }
        assertEquals("HEAD", origin.takeRequest().method)
    }

    @Test fun `no token, a wrong token, or an unknown route never reach the origin`() {
        val url = register()
        val base = "http://127.0.0.1:${proxy.port}"
        get("$base/media.mp4").use { assertEquals(403, it.code) }
        get("$base/t/${"0".repeat(32)}/media.mp4").use { assertEquals(403, it.code) }
        get("$base/t/short/media.mp4").use { assertEquals(403, it.code) }
        get(url.substringBeforeLast('/') + "/other").use { assertEquals(404, it.code) }
        assertEquals(0, origin.requestCount)
    }

    @Test fun `a host the plugin never declared is refused before any connection`() {
        val url = register(hosts = EffectiveHosts(listOf("cdn.example")))
        get(url).use { assertEquals(403, it.code) }
        assertEquals(0, origin.requestCount)
    }

    @Test fun `a declared name that resolves into the home network is refused`() {
        answer = { listOf(InetAddress.getByName("192.168.1.20")) }
        val hosts = EffectiveHosts(listOf("cdn.example"), insecure = setOf("cdn.example"))
        val url = register(url = "http://cdn.example:${origin.port}/movie.mp4", hosts = hosts)
        get(url).use { assertEquals(403, it.code) }
        assertEquals(0, origin.requestCount)
    }

    @Test fun `a redirect to an undeclared host is refused, never followed`() {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/x.mp4"))
        get(register()).use { assertEquals(403, it.code) }
        assertEquals(1, origin.requestCount)
    }

    @Test fun `an upstream error is passed on, not turned into a stream`() {
        origin.enqueue(MockResponse().setResponseCode(410))
        get(register()).use { assertEquals(410, it.code) }
    }

    @Test fun `one title keeps one token, and a new link swaps the origin under it`() {
        val first = register()
        assertEquals(first, register())
        val moved = register(url = originUrl("/movie-renewed.mp4"))
        assertEquals(first, moved)
        origin.enqueue(MockResponse().setBody("x"))
        get(moved).use { assertEquals(200, it.code) }
        assertEquals("/movie-renewed.mp4", origin.takeRequest().path)
        // Another title gets its own token.
        assertNotEquals(first, register(key = "plugin:p:m2::0"))
    }

    @Test fun `forget and stop end the token`() {
        val url = register()
        proxy.forget("plugin:p:m1::0")
        get(url).use { assertEquals(403, it.code) }
        val again = register()
        proxy.stop()
        assertEquals(0, proxy.size)
        assertNull(proxy.register("k", originUrl("/a.mp4"), emptyMap(), localhostHosts, PluginCastProxy.Shape.FILE, "video/mp4"))
        assertFalse(runCatching { get(again).close() }.isSuccess && proxy.port > 0)
    }

    @Test fun `logs never carry a token, a header, a path or a query`() {
        origin.enqueue(MockResponse().setResponseCode(500))
        val url = register()
        get(url).use { }
        get(register(hosts = EffectiveHosts(listOf("cdn.example")), key = "k2")).use { }
        get("http://127.0.0.1:${proxy.port}/t/${"f".repeat(32)}/media.mp4").use { }
        val token = url.substringAfter("/t/").substringBefore('/')
        assertNotNull(logs.firstOrNull())
        for (line in logs) {
            assertFalse(line, line.contains(token))
            assertFalse(line, line.contains("TOPSECRET") || line.contains("secret-ref"))
            assertFalse(line, line.contains("movie.mp4") || line.contains("sig=abc"))
        }
    }

    // --- HLS (playlists rewritten) ---

    /** Serves a master, a media playlist (relative + absolute + other-host URIs), a key, an init map and segments. */
    private fun serveHls(otherBase: String = "https://cdn.example") {
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path!!.substringBefore('?')) {
                "/hls/master.m3u8" -> MockResponse().setBody(
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow/index.m3u8?sig=abc\n",
                ).setHeader("Content-Type", "application/vnd.apple.mpegurl")
                "/hls/low/index.m3u8" -> MockResponse().setBody(
                    "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/k.key\"\n" +
                        "#EXTINF:6,\nseg1.ts\n#EXTINF:6,\nhttp://localhost:${origin.port}/abs/seg2.ts\n" +
                        "#EXTINF:6,\n$otherBase/seg3.ts\n#EXT-X-ENDLIST\n",
                )
                "/hls/low/init.mp4" -> MockResponse().setBody("INIT")
                "/keys/k.key" -> MockResponse().setBody("KEY0123456789ABC")
                "/hls/low/seg1.ts" -> MockResponse().setBody("SEG1")
                "/abs/seg2.ts" -> MockResponse().setBody("SEG2")
                "/seg3.ts" -> MockResponse().setBody("SEG3")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun registerHls(key: String = "plugin:p:m1::0") =
        proxy.register(key, originUrl("/hls/master.m3u8?sig=abc"), secretHeaders, localhostHosts, PluginCastProxy.Shape.HLS, PluginCastProxy.MIME_HLS)!!

    private fun base(url: String) = url.substringBefore("/t/")

    private val tokenUri = Regex("""/t/[0-9a-f]+/r/[^"\s]+""")

    @Test fun `an HLS stream is served as playlists whose every URI comes back under the same token`() {
        serveHls()
        val url = registerHls()
        assertTrue(url.endsWith("/index.m3u8"))
        val token = url.substringAfter("/t/").substringBefore('/')
        val master = get(url).use { r ->
            assertEquals(200, r.code)
            assertEquals(PluginCastProxy.MIME_HLS, r.header("Content-Type"))
            r.body!!.string()
        }
        assertFalse(master, master.contains("localhost") || master.contains("sig=abc"))
        val variant = master.lines().single { it.startsWith("/t/") }
        assertEquals("/t/$token/r/0.m3u8", variant)
        val media = get(base(url) + variant).use { it.body!!.string() }
        assertFalse(media, media.contains("localhost") || media.contains("cdn.example"))
        val uris = tokenUri.findAll(media).map { it.value }.toList()
        assertEquals(5, uris.size)
        assertTrue(uris.all { it.startsWith("/t/$token/r/") })
        // Map, key and the two same-host segments are fetched through the proxy with the plugin's headers.
        assertEquals("INIT", get(base(url) + uris[0]).use { it.body!!.string() })
        assertEquals("KEY0123456789ABC", get(base(url) + uris[1]).use { it.body!!.string() })
        get(base(url) + uris[2]).use {
            assertEquals("SEG1", it.body!!.string())
            assertEquals("video/mp2t", it.header("Content-Type"))
        }
        assertEquals("SEG2", get(base(url) + uris[3]).use { it.body!!.string() })
        // The segment on a host the plugin never declared is refused by its gate, before any connection.
        val before = origin.requestCount
        get(base(url) + uris[4]).use { assertEquals(403, it.code) }
        assertEquals(before, origin.requestCount)
        repeat(origin.requestCount) {
            val r = origin.takeRequest()
            assertEquals(r.path, "https://site.example/secret-ref", r.getHeader("Referer"))
            assertEquals(r.path, "sid=TOPSECRET", r.getHeader("Cookie"))
        }
    }

    @Test fun `a segment on another host is fetched when the plugin's rules allow that host`() {
        serveHls(otherBase = "http://cdn.example:${origin.port}")
        val hosts = EffectiveHosts(listOf("localhost", "cdn.example"), insecure = setOf("cdn.example"))
        val url = proxy.register("k", originUrl("/hls/low/index.m3u8"), emptyMap(), hosts, PluginCastProxy.Shape.HLS, PluginCastProxy.MIME_HLS)!!
        val media = get(url).use { it.body!!.string() }
        val third = tokenUri.findAll(media).map { it.value }.toList()[4]
        // http://cdn.example:<port>/seg3.ts: another host, declared (and approved for http): fetched.
        get(base(url) + third).use { assertEquals("SEG3", it.body!!.string()) }
    }

    @Test fun `a token reaches only what its own playlists named`() {
        serveHls()
        val a = registerHls("a")
        val b = registerHls("b")
        get(a).use { it.body!!.string() }
        // Nothing named yet under b: index 0 is unknown there even though a has one.
        get(b.substringBeforeLast('/') + "/r/0.m3u8").use { assertEquals(404, it.code) }
        get(a.substringBeforeLast('/') + "/r/0.m3u8").use { assertEquals(200, it.code) }
        get(a.substringBeforeLast('/') + "/r/99.ts").use { assertEquals(404, it.code) }
        get(a.substringBeforeLast('/') + "/r/x").use { assertEquals(404, it.code) }
        // An HLS token has no media route.
        get(a.substringBeforeLast('/') + "/media.mp4").use { assertEquals(404, it.code) }
    }

    @Test fun `an upstream that isn't a playlist is not passed off as one`() {
        origin.enqueue(MockResponse().setBody("<html>blocked</html>"))
        get(registerHls()).use { assertEquals(502, it.code) }
    }

    @Test fun `HLS logs never carry a token, a header, a path or a query`() {
        serveHls()
        val url = registerHls()
        val variant = get(url).use { it.body!!.string() }.lines().single { it.startsWith("/t/") }
        val media = get(base(url) + variant).use { it.body!!.string() }
        tokenUri.findAll(media).forEach { get(base(url) + it.value).use { } }
        val token = url.substringAfter("/t/").substringBefore('/')
        assertTrue(logs.isNotEmpty())
        for (line in logs) {
            assertFalse(line, line.contains(token) || line.contains("TOPSECRET") || line.contains("secret-ref"))
            assertFalse(line, line.contains("seg3.ts") || line.contains("sig=abc") || line.contains("/hls/"))
        }
    }
}
