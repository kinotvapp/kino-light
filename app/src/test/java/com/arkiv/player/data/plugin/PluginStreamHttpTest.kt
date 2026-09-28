package com.arkiv.player.data.plugin

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The player's client for plugin streams: every manifest, segment, key, subtitle and redirect hop
 * goes through the same host gate as `kino.fetch`, and a refused one never reaches the network.
 */
class PluginStreamHttpTest {
    private val server = MockWebServer()
    private val lookups = mutableListOf<String>()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    /** Records every name resolved; answers with [answer] (loopback by default, for MockWebServer). */
    private fun recordingDns(answer: (String) -> List<InetAddress> = { listOf(InetAddress.getLoopbackAddress()) }) =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups += hostname
                return answer(hostname)
            }
        }

    // MockWebServer can't serve arbitrary hostnames over https: these use the test-only
    // "localhost over http" override, exactly like PluginHttpTest. Production never sets it.
    private fun localClient(hosts: List<String> = listOf("localhost")) =
        PluginStreamHttp.client(OkHttpClient(), EffectiveHosts(hosts), allowInsecureLocalhost = true, delegateDns = recordingDns())

    private fun client(hosts: List<String>, dns: Dns = recordingDns()) =
        PluginStreamHttp.client(OkHttpClient(), EffectiveHosts(hosts), delegateDns = dns)

    private fun get(client: OkHttpClient, url: String) =
        client.newCall(Request.Builder().url(url).header("Referer", "https://cdn.example.com/").build()).execute()

    private fun url(path: String) = "http://localhost:${server.port}$path"

    @Test fun `a declared host is fetched with the stream headers`() {
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        get(localClient(), url("/master.m3u8")).use { assertEquals("#EXTM3U", it.body!!.string()) }
        assertEquals("https://cdn.example.com/", server.takeRequest().getHeader("Referer"))
    }

    @Test fun `an undeclared host is refused before any lookup or connection`() {
        val e = assertThrows(HostNotAllowedException::class.java) { get(client(listOf("cdn.example.com")), "https://evil.example/seg1.ts") }
        assertEquals("host no permitido: evil.example", e.message)
        assertEquals(emptyList<String>(), lookups)
    }

    @Test fun `plain http is refused even on a declared host`() {
        assertThrows(IOException::class.java) { get(client(listOf("cdn.example.com")), "http://cdn.example.com/seg1.ts") }
        assertEquals(emptyList<String>(), lookups)
    }

    @Test fun `a stream request over http is served only from a host approved as insecureHttp`() {
        val hosts = EffectiveHosts(listOf("api.example.com", "cdn.example.com"), insecure = setOf("cdn.example.com"))
        // Both names resolve to this MockWebServer (the loopback allowance is the test-only flag; the
        // gate's own decision runs before any lookup, and never depends on it).
        val client = PluginStreamHttp.client(OkHttpClient(), hosts, allowInsecureLocalhost = true, delegateDns = recordingDns())
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        get(client, "http://cdn.example.com:${server.port}/master.m3u8").use { r ->
            assertEquals("#EXTM3U", r.body!!.string())
            assertEquals("https://cdn.example.com/", server.takeRequest().getHeader("Referer"))
        }
        assertEquals(listOf("cdn.example.com"), lookups)
        assertThrows(IOException::class.java) { get(client, "http://api.example.com:${server.port}/seg1.ts") }
        assertThrows(IOException::class.java) { get(client, "http://sub.cdn.example.com:${server.port}/seg1.ts") }
        assertEquals(1, server.requestCount)
        assertEquals(listOf("cdn.example.com"), lookups)
        // A redirect from the https API onto the insecure CDN over http is followed; onto the API over http it is not.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.example.com:${server.port}/final.ts"))
        server.enqueue(MockResponse().setBody("bytes"))
        get(client, "http://cdn.example.com:${server.port}/seg1.ts").use { r -> assertEquals("bytes", r.body!!.string()) }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://api.example.com:${server.port}/final.ts"))
        assertThrows(IOException::class.java) { get(client, "http://cdn.example.com:${server.port}/seg2.ts") }
        assertEquals(4, server.requestCount)
    }

    @Test fun `IP literals and localhost are refused, whatever was declared`() {
        listOf("https://192.168.1.1/cgi-bin/x", "https://127.0.0.1:8080/s?u=x", "https://[::1]/k", "https://localhost/x")
            .forEach { u -> assertThrows(u, HostNotAllowedException::class.java) { get(client(listOf("cdn.example.com")), u) } }
        assertEquals(emptyList<String>(), lookups)
    }

    @Test fun `a declared name that resolves into the home network fails`() {
        val lan = recordingDns { listOf(InetAddress.getByAddress(it, byteArrayOf(192.toByte(), 168.toByte(), 1, 1))) }
        assertThrows(UnknownHostException::class.java) { get(client(listOf("cdn.example.com"), lan), "https://cdn.example.com/seg1.ts") }
        assertEquals(listOf("cdn.example.com"), lookups)
    }

    @Test fun `a redirect hop to an IP literal never reaches that server`() {
        val lan = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${lan.port}/cgi-bin/x"))
            lan.enqueue(MockResponse().setBody("must not be served"))
            val e = assertThrows(HostNotAllowedException::class.java) { get(localClient(), url("/seg1.ts")) }
            assertEquals("host no permitido: 127.0.0.1", e.message)
            assertEquals(1, server.requestCount)
            assertEquals(0, lan.requestCount)
        } finally {
            lan.shutdown()
        }
    }

    @Test fun `a redirect hop to an undeclared name is refused before it is even resolved`() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://evil.example/seg1.ts"))
        assertThrows(HostNotAllowedException::class.java) { get(localClient(), url("/seg1.ts")) }
        assertEquals(1, server.requestCount)
        assertTrue(lookups.none { it == "evil.example" })
    }

    @Test fun `a redirect within the declared hosts is followed`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final.ts"))
        server.enqueue(MockResponse().setBody("bytes"))
        get(localClient(), url("/seg1.ts")).use { r ->
            assertEquals("bytes", r.body!!.string())
            assertEquals(url("/final.ts"), r.request.url.toString())
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun `too many redirects fail`() {
        repeat(12) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again")) }
        assertThrows(IOException::class.java) { get(localClient(), url("/loop")) }
        assertEquals(11, server.requestCount)
    }

    // --- liveStreamHosts "any": the player client of an approved live channel ---

    private fun anyClient(dns: Dns, loopback: Boolean = false) = PluginStreamHttp.client(
        OkHttpClient(), EffectiveHosts(listOf("declared.example.com"), anyPublicLiveHost = true),
        allowInsecureLocalhost = loopback, delegateDns = dns,
    )

    @Test fun `with any public live host an undeclared name is fetched over plain http`() {
        server.enqueue(MockResponse().setBody("#EXTM3U"))
        // Resolves to this MockWebServer (loopback only through the test-only flag).
        get(anyClient(recordingDns(), loopback = true), "http://cdn.iptv-somewhere.test:${server.port}/1.m3u8").use {
            assertEquals("#EXTM3U", it.body!!.string())
        }
        assertEquals(listOf("cdn.iptv-somewhere.test"), lookups)
    }

    @Test fun `with any public live host a public name resolving into the home network is refused at connect time`() {
        listOf(byteArrayOf(192.toByte(), 168.toByte(), 1, 1), byteArrayOf(10, 0, 0, 2), byteArrayOf(100, 64, 0, 1),
            byteArrayOf(127, 0, 0, 1), byteArrayOf(169.toByte(), 254.toByte(), 1, 1)).forEach { ip ->
            val rebinding = recordingDns { listOf(InetAddress.getByAddress(it, ip)) }
            assertThrows(UnknownHostException::class.java) { get(anyClient(rebinding), "http://cdn.iptv-somewhere.test/1.m3u8") }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `with any public live host a redirect onto a LAN literal never reaches it`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://192.168.1.1/cgi-bin/x"))
        val e = assertThrows(HostNotAllowedException::class.java) {
            get(anyClient(recordingDns(), loopback = true), "http://cdn.iptv-somewhere.test:${server.port}/1.m3u8")
        }
        assertEquals("host no permitido: 192.168.1.1", e.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun `with any public live host a hex-number name reaches DNS and a loopback answer is refused`() {
        val dns = recordingDns { listOf(InetAddress.getByAddress(it, byteArrayOf(127, 0, 0, 1))) }
        assertThrows(UnknownHostException::class.java) { get(anyClient(dns), "http://0x7f000001/1.m3u8") }
        assertEquals(listOf("0x7f000001"), lookups)
        assertEquals(0, server.requestCount)
    }

    @Test fun `under any a side-loaded subtitle is gated strictly on every hop, a segment is not`() {
        val any = EffectiveHosts(listOf("localhost"), anyPublicLiveHost = true)
        val subtitle = url("/a.vtt")
        val client = PluginStreamHttp.client(OkHttpClient(), any, allowInsecureLocalhost = true, delegateDns = recordingDns(), strictOrigins = setOf(subtitle))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://subs.elsewhere.test:${server.port}/b.vtt"))
        assertThrows(HostNotAllowedException::class.java) { get(client, subtitle) }
        assertEquals(1, server.requestCount)
        assertTrue(lookups.none { it == "subs.elsewhere.test" })
        // The stream's own segment redirecting the same way is followed: "any" covers it.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.elsewhere.test:${server.port}/s.ts"))
        server.enqueue(MockResponse().setBody("bytes"))
        // (A public name here: under "any" the test-only localhost allowance never applies.)
        get(client, "http://cdn.iptv-somewhere.test:${server.port}/seg1.ts").use { assertEquals("bytes", it.body!!.string()) }
    }
}
