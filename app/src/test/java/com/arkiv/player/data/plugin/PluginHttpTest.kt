package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress

class PluginHttpTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    // MockWebServer can't serve arbitrary hostnames over https, so these use the test-only
    // "localhost over http" override; production never sets it.
    private fun http(hosts: EffectiveHosts = EffectiveHosts(listOf("localhost"))) =
        PluginHttp(OkHttpClient(), "test", hosts, "1.0", cookies = PluginCookies(tmp.root.resolve("cookies-${System.nanoTime()}.json"), hosts), allowInsecureLocalhost = true)

    private fun url(path: String) = "http://localhost:${server.port}$path"

    @Test fun `gate decisions`() {
        val hosts = EffectiveHosts(listOf("archive.org", "*.archive.org"))
        PluginHostGate.check("https://archive.org/x".toHttpUrl(), hosts)
        PluginHostGate.check("https://ia8.us.archive.org/x".toHttpUrl(), hosts)
        val e = assertThrows(HostNotAllowedException::class.java) {
            PluginHostGate.check("https://evil.example/".toHttpUrl(), hosts)
        }
        assertEquals("host no permitido: evil.example", e.message)
        assertThrows(IOException::class.java) { PluginHostGate.check("http://archive.org/".toHttpUrl(), hosts) }
        // Without the test flag, even a declared localhost must be https.
        assertThrows(IOException::class.java) { PluginHostGate.check("http://localhost/".toHttpUrl(), EffectiveHosts(listOf("localhost"))) }
    }

    @Test fun `the gate refuses IP literals and local names before matching the declared hosts`() {
        // Declared patterns can never be IP literals or local names (HostRules), but the gate
        // refuses them on its own too: it's the one check every request and redirect hop goes through.
        listOf("https://192.168.1.1/cgi-bin/x", "https://[::1]/", "https://2130706433/", "https://nas.local/")
            .forEach { u ->
                assertThrows(u, HostNotAllowedException::class.java) {
                    PluginHostGate.check(u.toHttpUrl(), EffectiveHosts(listOf("192.168.1.1", "nas.local", "*.1.1")))
                }
            }
        assertThrows(HostNotAllowedException::class.java) {
            PluginHostGate.check("https://localhost/".toHttpUrl(), EffectiveHosts(listOf("localhost")))
        }
    }

    @Test fun `a server the person typed is allowed exactly, cleartext included`() {
        val hosts = EffectiveHosts(listOf("api.example.com"), listOf(UserHost("http", "192.168.1.10", 8096), UserHost("http", "nas.lan", 80)))
        PluginHostGate.check("http://192.168.1.10:8096/Users/AuthenticateByName".toHttpUrl(), hosts)
        PluginHostGate.check("http://nas.lan/x".toHttpUrl(), hosts)
        listOf("http://192.168.1.10:8097/", "https://192.168.1.10:8096/", "http://192.168.1.11:8096/", "http://nas.lan:8080/", "http://api.example.com/")
            .forEach { u -> assertThrows(u, PluginFetchException::class.java) { PluginHostGate.check(u.toHttpUrl(), hosts) } }
    }

    @Test fun `a redirect from a typed server may only stay there or go to a declared host`() {
        val a = UserHost("http", "192.168.1.10", 8096)
        val b = UserHost("http", "192.168.1.20", 80)
        val hosts = EffectiveHosts(listOf("cdn.example.com"), listOf(a, b))
        val from = "http://192.168.1.10:8096/x".toHttpUrl()
        PluginHostGate.checkRedirect(from, "http://192.168.1.10:8096/y".toHttpUrl(), hosts)
        PluginHostGate.checkRedirect(from, "https://cdn.example.com/y".toHttpUrl(), hosts)
        assertThrows(HostNotAllowedException::class.java) { PluginHostGate.checkRedirect(from, "http://192.168.1.20/y".toHttpUrl(), hosts) }
        assertThrows(HostNotAllowedException::class.java) { PluginHostGate.checkRedirect(from, "http://127.0.0.1:8096/y".toHttpUrl(), hosts) }
    }

    @Test fun `a typed server name may resolve into the LAN, never to loopback`() {
        val lan = object : okhttp3.Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("192.168.1.10")) }
        val loop = object : okhttp3.Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) }
        assertEquals(1, PluginDns(delegate = lan, userHostNames = setOf("nas.lan")).lookup("nas.lan").size)
        assertThrows(PrivateAddressException::class.java) { PluginDns(delegate = lan).lookup("nas.lan") }
        assertThrows(PrivateAddressException::class.java) { PluginDns(delegate = loop, userHostNames = setOf("nas.lan")).lookup("nas.lan") }
    }

    @Test fun `a typed server address that is a decimal-integer IP literal in disguise is still refused`() {
        // "2130706433" has no dot or colon, so PluginHosts.isIpLiteral (a string check) misses it --
        // Java's InetAddress parses bare decimal hosts as a 32-bit address (2130706433 == 127.0.0.1),
        // so it must still be blocked at DNS-resolution time (PluginDns.isDevice), not by the string
        // check alone. Measured directly against a server: the request must never leave the device.
        val hosts = EffectiveHosts(emptyList(), listOf(UserHost("http", "2130706433", server.port)))
        server.enqueue(MockResponse().setBody("must not be served"))
        val client = PluginStreamHttp.client(OkHttpClient(), hosts)
        assertThrows(IOException::class.java) {
            client.newCall(okhttp3.Request.Builder().url("http://2130706433:${server.port}/x").build()).execute()
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `fetch returns status, lowercased headers and body with the plugin user agent`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"a\":1}").setHeader("X-Test", "yes"))
        val r = http().fetch(PluginHttp.Request(url("/ok")))
        assertTrue(r.ok)
        assertEquals(200, r.status)
        assertEquals("{\"a\":1}", r.body)
        assertEquals("yes", r.headers["x-test"])
        assertEquals("Kino/1.0 (plugin test)", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun `a plugin user agent and POST body are sent as given`() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        http().fetch(
            PluginHttp.Request(url("/p"), method = "POST", headers = mapOf("User-Agent" to "X/1", "Content-Type" to "application/json"), body = "{\"q\":1}"),
        )
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("X/1", rec.getHeader("User-Agent"))
        assertEquals("{\"q\":1}", rec.body.readUtf8())
    }

    @Test fun `a redirect to an undeclared host is refused before any request goes out`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${server.port}/elsewhere"))
        server.enqueue(MockResponse().setBody("must not be served"))
        val e = assertThrows(HostNotAllowedException::class.java) {
            runBlocking { http().fetch(PluginHttp.Request(url("/start"))) }
        }
        assertEquals("host no permitido: 127.0.0.1", e.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a redirect to a declared host is followed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/final"))
        server.enqueue(MockResponse().setBody("done"))
        val r = http().fetch(PluginHttp.Request(url("/start")))
        assertEquals("done", r.body)
        assertEquals(url("/final"), r.url)
    }

    @Test fun `bodies over 5 MB are refused`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(5 * 1024 * 1024 + 1))))
        assertThrows(IOException::class.java) { runBlocking { http().fetch(PluginHttp.Request(url("/big"))) } }
    }

    @Test fun `at most 60 requests per call, reset by beginCall`() = runBlocking {
        repeat(62) { server.enqueue(MockResponse().setBody("x")) }
        val h = http()
        h.beginCall()
        repeat(60) { h.fetch(PluginHttp.Request(url("/n"))) }
        assertThrows(IOException::class.java) { runBlocking { h.fetch(PluginHttp.Request(url("/n"))) } }
        h.beginCall()
        assertEquals("x", h.fetch(PluginHttp.Request(url("/n"))).body)
    }

    @Test fun `cookies persist between requests of the same plugin`() = runBlocking {
        server.enqueue(MockResponse().setBody("a").addHeader("Set-Cookie", "s=1; Path=/"))
        server.enqueue(MockResponse().setBody("b"))
        val h = http()
        h.fetch(PluginHttp.Request(url("/one")))
        h.fetch(PluginHttp.Request(url("/two")))
        server.takeRequest()
        assertEquals("s=1", server.takeRequest().getHeader("Cookie"))
    }
}
