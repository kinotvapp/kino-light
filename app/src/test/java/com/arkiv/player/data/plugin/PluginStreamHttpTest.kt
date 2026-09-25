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
}
