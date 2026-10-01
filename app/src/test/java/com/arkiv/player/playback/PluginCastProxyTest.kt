package com.arkiv.player.playback

import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginStreamHttp
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
}
