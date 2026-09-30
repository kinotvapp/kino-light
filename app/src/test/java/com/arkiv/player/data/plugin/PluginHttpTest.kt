package com.arkiv.player.data.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.util.Base64

class PluginHttpTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    // MockWebServer can't serve arbitrary hostnames over https, so these use the test-only
    // "localhost over http" override; production never sets it.
    private fun http(hosts: EffectiveHosts = EffectiveHosts(listOf("localhost")), cookies: PluginCookies? = jar(hosts)) =
        PluginHttp(OkHttpClient(), "test", hosts, "1.0", cookies = cookies, allowInsecureLocalhost = true)

    private fun jar(hosts: EffectiveHosts) = PluginCookies(tmp.root.resolve("cookies-${System.nanoTime()}.json"), hosts)

    private fun url(path: String) = "http://localhost:${server.port}$path"

    private fun fetchError(h: PluginHttp, req: PluginHttp.Request): PluginFetchException =
        assertThrows(PluginFetchException::class.java) { runBlocking { h.fetch(req) } }

    private fun approvingHttp(
        hosts: EffectiveHosts,
        approve: Boolean,
        onApproved: (String) -> Unit = {},
        onRejected: (String) -> Unit = {},
        rejected: Set<String> = emptySet(),
        delegateDns: Dns = Dns.SYSTEM,
    ) = PluginHttp(
        OkHttpClient(), "test", hosts, "1.0", cookies = jar(hosts), allowInsecureLocalhost = true, delegateDns = delegateDns,
        reactiveApproval = PluginHttp.ReactiveApproval(
            pluginName = "Plugin de prueba",
            requester = HostApprovalRequester { _, _, _ -> approve },
            onApproved = onApproved, onRejected = onRejected, rejectedHosts = rejected,
        ),
    )

    @Test fun `gate decisions`() {
        val hosts = EffectiveHosts(listOf("archive.org", "*.archive.org"))
        PluginHostGate.check("https://archive.org/x".toHttpUrl(), hosts)
        PluginHostGate.check("https://ia8.us.archive.org/x".toHttpUrl(), hosts)
        val e = assertThrows(HostNotAllowedException::class.java) {
            PluginHostGate.check("https://evil.example/".toHttpUrl(), hosts)
        }
        assertEquals("host no permitido: evil.example", e.message)
        assertEquals("host_not_allowed", assertThrows(PluginFetchException::class.java) { PluginHostGate.check("http://archive.org/".toHttpUrl(), hosts) }.code)
        // Without the test flag, even a declared localhost must be https.
        assertThrows(PluginFetchException::class.java) { PluginHostGate.check("http://localhost/".toHttpUrl(), EffectiveHosts(listOf("localhost"))) }
    }

    @Test fun `the gate lets plain http through only on a host approved as insecureHttp`() {
        val hosts = EffectiveHosts(listOf("api.example.com", "cdn.example.com"), insecure = setOf("cdn.example.com"))
        PluginHostGate.check("http://cdn.example.com/v.mp4".toHttpUrl(), hosts)
        PluginHostGate.check("https://cdn.example.com/v.mp4".toHttpUrl(), hosts)
        PluginHostGate.check("https://api.example.com/".toHttpUrl(), hosts)
        listOf("http://api.example.com/", "http://sub.cdn.example.com/", "http://evil.example/")
            .forEach { u -> assertThrows(u, PluginFetchException::class.java) { PluginHostGate.check(u.toHttpUrl(), hosts) } }
        assertEquals("solo se permite https", assertThrows(PluginFetchException::class.java) { PluginHostGate.check("http://api.example.com/".toHttpUrl(), hosts) }.message)
        // A redirect may land on the insecure host over http, and on no other declared host over http.
        val from = "https://api.example.com/start".toHttpUrl()
        PluginHostGate.checkRedirect(from, "http://cdn.example.com/v.mp4".toHttpUrl(), hosts)
        assertThrows(PluginFetchException::class.java) { PluginHostGate.checkRedirect(from, "http://api.example.com/v.mp4".toHttpUrl(), hosts) }
        // An insecure host is still never an IP literal, a local name, or one that resolves into the LAN.
        assertThrows(HostNotAllowedException::class.java) { PluginHostGate.check("http://192.168.1.10/".toHttpUrl(), EffectiveHosts(listOf("192.168.1.10"), insecure = setOf("192.168.1.10"))) }
        assertThrows(HostNotAllowedException::class.java) { PluginHostGate.check("http://nas.local/".toHttpUrl(), EffectiveHosts(listOf("nas.local"), insecure = setOf("nas.local"))) }
    }

    @Test fun `kino fetch reaches an insecure host over http, and no other declared host`() {
        val hosts = EffectiveHosts(listOf("api.example.com", "cdn.example.com"), insecure = setOf("cdn.example.com"))
        // Both names resolve to this MockWebServer; the gate decides before any lookup happens.
        val loopback = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }
        val h = PluginHttp(OkHttpClient(), "test", hosts, "1.0", cookies = null, allowInsecureLocalhost = true, delegateDns = loopback)
        server.enqueue(MockResponse().setBody("hola"))
        val r = runBlocking { h.fetch(PluginHttp.Request("http://cdn.example.com:${server.port}/x")) }
        assertEquals(200, r.status)
        assertEquals("hola", r.text)
        assertEquals(1, server.requestCount)
        assertEquals("host_not_allowed", fetchError(h, PluginHttp.Request("http://api.example.com:${server.port}/x")).code)
        assertEquals("host_not_allowed", fetchError(h, PluginHttp.Request("http://sub.cdn.example.com:${server.port}/x")).code)
        assertEquals(1, server.requestCount)
        // The same names without the flag (a v1 plugin): http is refused on both, exactly as always.
        val v1 = PluginHttp(OkHttpClient(), "test", EffectiveHosts(listOf("api.example.com", "cdn.example.com")), "1.0", cookies = null, allowInsecureLocalhost = true, delegateDns = loopback)
        assertEquals("host_not_allowed", fetchError(v1, PluginHttp.Request("http://cdn.example.com:${server.port}/x")).code)
        assertEquals(1, server.requestCount)
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

    @Test fun `fetch returns status, lowercased headers and text with the plugin user agent`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"a\":1}").setHeader("X-Test", "yes").setHeader("Content-Type", "application/json"))
        val r = http().fetch(PluginHttp.Request(url("/ok")))
        assertTrue(r.ok)
        assertEquals(200, r.status)
        assertEquals("{\"a\":1}", r.text)
        assertNull(r.bytesBase64)
        assertEquals("yes", r.headers["x-test"])
        assertEquals("Kino/1.0 (plugin test)", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun `set-cookie never reaches the plugin as a header`() = runBlocking {
        server.enqueue(MockResponse().setBody("x").addHeader("Set-Cookie", "s=1").addHeader("Set-Cookie2", "t=2"))
        val r = http().fetch(PluginHttp.Request(url("/c")))
        assertTrue(r.headers.keys.none { it.startsWith("set-cookie") })
    }

    @Test fun `a binary body comes as base64 only, a latin-1 text body as both`() = runBlocking {
        val bytes = byteArrayOf(0, -1, 10, -128, 65)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "application/octet-stream"))
        val bin = http().fetch(PluginHttp.Request(url("/bin")))
        assertNull(bin.text)
        assertEquals(Base64.getEncoder().encodeToString(bytes), bin.bytesBase64)
        server.enqueue(MockResponse().setBody(Buffer().write("año".toByteArray(Charsets.ISO_8859_1))).setHeader("Content-Type", "text/html; charset=ISO-8859-1"))
        val latin = http().fetch(PluginHttp.Request(url("/latin")))
        assertEquals("año", latin.text)
        assertEquals(Base64.getEncoder().encodeToString("año".toByteArray(Charsets.ISO_8859_1)), latin.bytesBase64)
        server.enqueue(MockResponse().setBody("sin tipo"))
        assertEquals("sin tipo", http().fetch(PluginHttp.Request(url("/none"))).text)
    }

    // Nuvio scrapers copy a browser's headers, "Accept-Encoding: gzip, deflate, br" included. Sent
    // as is, OkHttp stops decompressing and the plugin got the gzip bytes as "text": PelisPlusHD's
    // JSON.parse of TMDB's answer failed with "unexpected token" on the TV. The plugin can't
    // decompress anything itself, so Kino keeps that header to itself and hands over plain text.
    @Test fun `a plugin's own Accept-Encoding is not sent, and the answer arrives decompressed`() = runBlocking {
        val json = "{\"title\":\"IntensaMente 2\"}"
        val gz = Buffer().also { b -> okio.GzipSink(b).use { okio.Buffer().writeUtf8(json).let { src -> it.write(src, src.size) } } }
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setHeader("Content-Type", "application/json").setBody(gz))
        val r = http().fetch(PluginHttp.Request(url("/m"), headers = mapOf("Accept-Encoding" to "gzip, deflate, br")))
        assertEquals(json, r.text)
        assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
    }

    @Test fun `each body kind is sent as the plugin asked`() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setBody("ok")) }
        val h = http()
        h.fetch(PluginHttp.Request(url("/t"), "POST", mapOf("User-Agent" to "X/1", "Content-Type" to "text/plain"), PluginHttp.Body.Text("hola")))
        h.fetch(PluginHttp.Request(url("/j"), "PUT", body = PluginHttp.Body.Json("{\"q\":1}")))
        h.fetch(PluginHttp.Request(url("/f"), "POST", body = PluginHttp.Body.Form(listOf("user" to "ana maría", "pass" to "a&b=c"))))
        h.fetch(PluginHttp.Request(url("/b"), "PATCH", body = PluginHttp.Body.Bytes(byteArrayOf(1, 2, 3))))
        server.takeRequest().let {
            assertEquals("X/1", it.getHeader("User-Agent"))
            assertEquals("hola", it.body.readUtf8())
        }
        server.takeRequest().let {
            assertEquals("PUT", it.method)
            assertTrue(it.getHeader("Content-Type")!!.startsWith("application/json"))
            assertEquals("{\"q\":1}", it.body.readUtf8())
        }
        server.takeRequest().let {
            assertTrue(it.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
            assertEquals("user=ana%20mar%C3%ADa&pass=a%26b%3Dc", it.body.readUtf8())
        }
        server.takeRequest().let { assertEquals(listOf<Byte>(1, 2, 3), it.body.readByteArray().toList()) }
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
        assertEquals("done", r.text)
        assertEquals(url("/final"), r.url)
    }

    @Test fun `redirect manual returns the 3xx with its location and follows nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/x"))
        val r = http().fetch(PluginHttp.Request(url("/login"), manualRedirects = true))
        assertEquals(302, r.status)
        assertEquals("https://evil.example/x", r.headers["location"])
        assertEquals(1, server.requestCount)
    }

    @Test fun `bodies over 5 MB are refused as too_large`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(5 * 1024 * 1024 + 1))))
        assertEquals("too_large", fetchError(http(), PluginHttp.Request(url("/big"))).code)
    }

    @Test fun `a server that never answers is a timeout`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals("timeout", fetchError(http(), PluginHttp.Request(url("/slow"), timeoutMs = 300)).code)
    }

    @Test fun `a bad url or method is invalid_request and nothing is sent`() {
        val h = http()
        assertEquals("invalid_request", fetchError(h, PluginHttp.Request("not a url")).code)
        assertEquals("invalid_request", fetchError(h, PluginHttp.Request(url("/x"), method = "TRACE")).code)
        assertEquals(0, server.requestCount)
    }

    @Test fun `at most 60 requests per call, reset by beginCall`() = runBlocking {
        repeat(62) { server.enqueue(MockResponse().setBody("x")) }
        val h = http()
        h.beginCall()
        repeat(60) { h.fetch(PluginHttp.Request(url("/n"))) }
        assertEquals("invalid_request", fetchError(h, PluginHttp.Request(url("/n"))).code)
        h.beginCall()
        assertEquals("x", h.fetch(PluginHttp.Request(url("/n"))).text)
    }

    @Test fun `cookies persist between requests of the same plugin, and cookies false skips the jar`() = runBlocking {
        server.enqueue(MockResponse().setBody("a").addHeader("Set-Cookie", "s=1; Path=/"))
        server.enqueue(MockResponse().setBody("b"))
        server.enqueue(MockResponse().setBody("c").addHeader("Set-Cookie", "t=2; Path=/"))
        server.enqueue(MockResponse().setBody("d"))
        val h = http()
        h.fetch(PluginHttp.Request(url("/one")))
        h.fetch(PluginHttp.Request(url("/two")))
        h.fetch(PluginHttp.Request(url("/three"), useCookies = false))
        h.fetch(PluginHttp.Request(url("/four")))
        server.takeRequest()
        assertEquals("s=1", server.takeRequest().getHeader("Cookie"))
        assertNull(server.takeRequest().getHeader("Cookie"))
        // t=2 came on a cookies:false response: never stored.
        assertEquals("s=1", server.takeRequest().getHeader("Cookie"))
    }

    @Test fun `a typed server name may resolve into the LAN, never to loopback`() {
        val lan = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("192.168.1.10")) }
        val loop = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) }
        assertEquals(1, PluginDns(delegate = lan, userHostNames = setOf("nas.lan")).lookup("nas.lan").size)
        assertThrows(PrivateAddressException::class.java) { PluginDns(delegate = lan).lookup("nas.lan") }
        assertThrows(PrivateAddressException::class.java) { PluginDns(delegate = loop, userHostNames = setOf("nas.lan")).lookup("nas.lan") }
    }

    @Test fun `any public live host passes, LAN and private literals never do`() {
        val any = EffectiveHosts(listOf("declared.example.com"), anyPublicLiveHost = true)
        PluginHostGate.check("http://cdn.iptv-somewhere.net/live/1.m3u8".toHttpUrl(), any)
        PluginHostGate.check("https://cdn.iptv-somewhere.net/live/1.ts".toHttpUrl(), any)
        PluginHostGate.check("http://203.0.113.7:8080/live/1.m3u8".toHttpUrl(), any)
        listOf("http://192.168.1.10/x", "http://10.0.0.2/x", "http://172.20.0.1/x", "http://127.0.0.1/x", "http://169.254.1.1/x",
            "http://100.64.0.1/x", "http://0.0.0.0/x", "http://224.0.0.1/x", "http://[fd00::1]/x", "http://router.local/x", "http://nas.lan/x",
        ).forEach { url ->
            assertThrows(url, HostNotAllowedException::class.java) { PluginHostGate.check(url.toHttpUrl(), any) }
        }
        // Without the flag nothing changes: an undeclared host is refused as always.
        assertThrows(HostNotAllowedException::class.java) {
            PluginHostGate.check("https://cdn.iptv-somewhere.net/x".toHttpUrl(), EffectiveHosts(listOf("declared.example.com")))
        }
    }

    @Test fun `isPublicIpv4Literal refuses every non-public range and every non-canonical shape`() {
        listOf("8.8.8.8", "203.0.113.7", "1.1.1.1", "172.15.0.1", "172.32.0.1", "100.63.255.255", "100.128.0.1", "223.255.255.255")
            .forEach { assertTrue(it, HostRules.isPublicIpv4Literal(it)) }
        listOf("0.1.2.3", "10.1.1.1", "127.0.0.1", "169.254.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.0.1",
            "100.127.0.1", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.19.255.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "127.1", "2130706433", "010.0.0.1", "0x7f.0.0.1", "1.2.3.4.5", "1.2.3", "256.1.1.1", "", "example.com", "::1",
        ).forEach { assertTrue(it, !HostRules.isPublicIpv4Literal(it)) }
    }

    @Test fun `under any, a typed server's name on another port or scheme falls back to the strict rules`() {
        val any = EffectiveHosts(
            listOf("declared.example.com"),
            listOfNotNull(PluginHosts.userHostOf("http://nas-name:8096"), PluginHosts.userHostOf("https://myhome.duckdns.org")),
            anyPublicLiveHost = true,
        )
        PluginHostGate.check("http://nas-name:8096/live/1.m3u8".toHttpUrl(), any)
        PluginHostGate.check("https://myhome.duckdns.org/live/1.m3u8".toHttpUrl(), any)
        listOf("http://nas-name:22/x", "https://nas-name:8096/x", "http://myhome.duckdns.org:80/admin", "http://NAS-NAME.:8080/x").forEach { u ->
            assertThrows(u, HostNotAllowedException::class.java) { PluginHostGate.check(u.toHttpUrl(), any) }
        }
    }

    @Test fun `isPromptableMiss only true for an undeclared, otherwise-normal public https host`() {
        val hosts = EffectiveHosts(listOf("archive.org"))
        assertTrue(PluginHostGate.isPromptableMiss("https://new-cdn.example/x".toHttpUrl(), hosts))
        // Already declared: not a "miss" at all, whatever the reason it failed (e.g. scheme).
        assertFalse(PluginHostGate.isPromptableMiss("http://archive.org/x".toHttpUrl(), hosts))
        // Hard refusals never become promptable, however the manifest is shaped.
        assertFalse(PluginHostGate.isPromptableMiss("https://192.168.1.10/x".toHttpUrl(), hosts))
        assertFalse(PluginHostGate.isPromptableMiss("https://nas.local/x".toHttpUrl(), hosts))
        assertFalse(PluginHostGate.isPromptableMiss("http://new-cdn.example/x".toHttpUrl(), hosts)) // plain http: never auto-approved
        // A server the person typed is its own gate, not a "miss".
        val withUser = hosts.copy(user = listOfNotNull(PluginHosts.userHostOf("http://192.168.1.5:8096")))
        assertFalse(PluginHostGate.isPromptableMiss("http://192.168.1.5:8096/x".toHttpUrl(), withUser))
        // User-typed servers exclude the host even with the right scheme (https)
        val withHttpsUser = hosts.copy(user = listOfNotNull(PluginHosts.userHostOf("https://myhome.duckdns.org")))
        assertFalse(PluginHostGate.isPromptableMiss("https://myhome.duckdns.org/x".toHttpUrl(), withHttpsUser))
        // The live "any host" carve-out is its own thing, never routed through reactive approval.
        assertFalse(PluginHostGate.isPromptableMiss("https://anything.example/x".toHttpUrl(), hosts.copy(anyPublicLiveHost = true)))
    }

    @Test fun `isPromptableMiss refuses a host no manifest could declare`() {
        val hosts = EffectiveHosts(listOf("archive.org"))
        // Approving is adding the host verbatim to `hosts`: a shape HostRules.isValidPattern refuses
        // would be persisted as a pattern that never matches again, and asked about forever.
        listOf("https://intranet/x", "https://a_b.example.com/x", "https://example.com./x").forEach { u ->
            assertFalse(u, PluginHostGate.isPromptableMiss(u.toHttpUrl(), hosts))
        }
        // A `*` is never a host: whatever HttpUrl makes of it, it must never be approved as a pattern.
        listOf("https://*.example.com/x", "https://a*.example.com/x").forEach { u ->
            u.toHttpUrlOrNull()?.let { assertFalse(u, PluginHostGate.isPromptableMiss(it, hosts)) }
        }
    }

    @Test fun `isPromptableMiss against a wildcard declaration`() {
        val hosts = EffectiveHosts(listOf("*.example.com"))
        // Covered by the pattern: not a miss.
        assertFalse(PluginHostGate.isPromptableMiss("https://cdn.example.com/x".toHttpUrl(), hosts))
        assertFalse(PluginHostGate.isPromptableMiss("https://a.b.example.com/x".toHttpUrl(), hosts))
        // `*.example.com` covers subdomains only, never the apex (HostRules): that IS a genuine miss.
        assertTrue(PluginHostGate.isPromptableMiss("https://example.com/x".toHttpUrl(), hosts))
        // A lookalike that merely ends in the same letters is a different host.
        assertTrue(PluginHostGate.isPromptableMiss("https://badexample.com/x".toHttpUrl(), hosts))
    }

    @Test fun `a malformed undeclared host fails as host_not_allowed without asking`() = runTest {
        var asked = false
        val h = PluginHttp(
            OkHttpClient(), "test", EffectiveHosts(listOf("archive.org")), "1.0", allowInsecureLocalhost = true,
            reactiveApproval = PluginHttp.ReactiveApproval("P", HostApprovalRequester { _, _, _ -> asked = true; true }, {}, {}, emptySet()),
        )
        assertEquals("host_not_allowed", fetchError(h, PluginHttp.Request("https://a_b.example.com/x")).code)
        assertFalse(asked)
    }

    // `isPromptableMiss` requires https (Task 2), and this suite never sets up a real TLS listener
    // (no other test in this file does either — everything else uses `allowInsecureLocalhost` +
    // plain http on `localhost`). So this test proves the gate opened, not that bytes came back: an
    // `offline` DNS makes `new-cdn.example` fail fast and deterministically with `UnknownHostException`
    // -> `PluginFetchException("network", …)`. Getting THAT code back, instead of `host_not_allowed`,
    // is exactly the proof the retried request got past the host check into real network I/O.
    @Test fun `an approved new host is added to hosts, and a later request no longer fails as host_not_allowed`() = runTest {
        var approvedHost: String? = null
        val offline = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException(hostname) }
        val h = approvingHttp(EffectiveHosts(listOf("archive.org")), approve = true, onApproved = { approvedHost = it }, delegateDns = offline)
        val e = fetchError(h, PluginHttp.Request("https://new-cdn.example/x"))
        assertEquals("network", e.code)
        assertEquals("new-cdn.example", approvedHost)
    }

    @Test fun `a rejected new host fails as host_not_allowed and is remembered via onRejected`() = runTest {
        var rejectedHost: String? = null
        val h = approvingHttp(EffectiveHosts(listOf("archive.org")), approve = false, onRejected = { rejectedHost = it })
        val e = fetchError(h, PluginHttp.Request("https://new-cdn.example/x"))
        assertEquals("host_not_allowed", e.code)
        assertEquals("new-cdn.example", rejectedHost)
    }

    @Test fun `a host already in rejectedHosts is never asked again`() = runTest {
        var asked = false
        val h = PluginHttp(
            OkHttpClient(), "test", EffectiveHosts(listOf("archive.org")), "1.0", allowInsecureLocalhost = true,
            reactiveApproval = PluginHttp.ReactiveApproval(
                "P", HostApprovalRequester { _, _, _ -> asked = true; true }, {}, {}, rejectedHosts = setOf("new-cdn.example"),
            ),
        )
        val e = fetchError(h, PluginHttp.Request("https://new-cdn.example/x"))
        assertEquals("host_not_allowed", e.code)
        assertFalse(asked)
    }

    // No cap on what the person approves: with 20 hosts (the most a manifest may declare) a 21st
    // and on to a 25th are still asked about, added and handed to onApproved to be persisted.
    @Test fun `past 20 hosts a new host is still asked about and added`() = runTest {
        val offline = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException(hostname) }
        val asked = mutableListOf<String>()
        val approved = mutableListOf<String>()
        val twenty = (1..20).map { "h$it.example.com" }
        val h = PluginHttp(
            OkHttpClient(), "test", EffectiveHosts(twenty), "1.0", allowInsecureLocalhost = true, delegateDns = offline,
            reactiveApproval = PluginHttp.ReactiveApproval("P", HostApprovalRequester { _, _, host -> asked += host; true }, { approved += it }, {}, emptySet()),
        )
        val more = (21..25).map { "h$it.example.com" }
        // Past the gate: only DNS (stubbed offline) fails.
        for (host in more) assertEquals(host, "network", fetchError(h, PluginHttp.Request("https://$host/x")).code)
        assertEquals(more, asked)
        assertEquals(more, approved)
        assertEquals(twenty + more, h.hosts.declared)
        // recordDecision (a Stream's host approved at resolve time) is not capped either.
        h.recordDecision("h26.example.com", approved = true)
        assertTrue(HostRules.matches("h26.example.com", h.hosts.declared))
    }

    // The requester owns the waiting window (HostApprovalCenter times itself out once the dialog is
    // on screen, see HostApprovalCenterTest); its null -- "no answer in time" -- is what's under test.
    @Test fun `an unanswered prompt fails as timeout, not host_not_allowed, and is never remembered`() = runTest {
        var rejected = false
        val h = PluginHttp(
            OkHttpClient(), "test", EffectiveHosts(listOf("archive.org")), "1.0", allowInsecureLocalhost = true,
            reactiveApproval = PluginHttp.ReactiveApproval(
                "P",
                HostApprovalRequester { _, _, _ -> null },
                {}, { rejected = true }, emptySet(),
            ),
        )
        val e = fetchError(h, PluginHttp.Request("https://new-cdn.example/x"))
        assertEquals("timeout", e.code)
        assertFalse(rejected)
    }

    // Same "offline DNS" technique as the test above: what's under test is that the requester is
    // invoked once, not whether either retried request ultimately succeeds.
    @Test fun `two concurrent misses on the same host share one prompt`() = runTest {
        var asks = 0
        val offline = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException(hostname) }
        val h = PluginHttp(
            OkHttpClient(), "test", EffectiveHosts(listOf("archive.org")), "1.0", allowInsecureLocalhost = true, delegateDns = offline,
            reactiveApproval = PluginHttp.ReactiveApproval("P", HostApprovalRequester { _, _, _ -> asks++; kotlinx.coroutines.delay(50); true }, {}, {}, emptySet()),
        )
        val url = "https://new-cdn.example/x"
        val r1 = async { runCatching { h.fetch(PluginHttp.Request(url)) } }
        val r2 = async { runCatching { h.fetch(PluginHttp.Request(url)) } }
        r1.await(); r2.await()
        assertEquals(1, asks)
    }

    // Regression test for a reviewer finding: the winner's OWN cancellation must never leak into a
    // loser waiting on the shared prompt as a bare CancellationException -- `fetch()`'s catch chain has
    // no mapping from that to any `kino.fetch` error code, so the loser would die with an unclassified
    // failure instead of a real answer. Same offline-DNS technique as the tests above: once the loser
    // re-asks (its own, fresh prompt) and gets "yes", the retried request fails deterministically as
    // "network", proving it got a real answer rather than inheriting the winner's cancellation.
    @Test fun `a waiting loser gets a real answer, not the winner's cancellation, when the winner is cancelled`() = runTest {
        var asks = 0
        val winnerIsAsking = CompletableDeferred<Unit>()
        val offline = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException(hostname) }
        val h = PluginHttp(
            OkHttpClient(), "test", EffectiveHosts(listOf("archive.org")), "1.0", allowInsecureLocalhost = true, delegateDns = offline,
            reactiveApproval = PluginHttp.ReactiveApproval(
                "P",
                HostApprovalRequester { _, _, _ ->
                    asks++
                    if (asks == 1) { winnerIsAsking.complete(Unit); kotlinx.coroutines.delay(Long.MAX_VALUE) }
                    true
                },
                {}, {}, emptySet(),
            ),
        )
        val url = "https://new-cdn.example/x"
        val winner = async { runCatching { h.fetch(PluginHttp.Request(url)) } }
        winnerIsAsking.await() // the winner has claimed the prompt and is now stuck "asking" forever
        val loser = async { runCatching { h.fetch(PluginHttp.Request(url)) } }
        winner.cancel()
        winner.join() // the winner's cleanup (completing the shared deferred, clearing the map) is done
        val result = loser.await()
        assertEquals(2, asks) // the winner's original ask, plus the loser's own retry-as-new-winner ask
        assertFalse(result.exceptionOrNull() is CancellationException)
        assertEquals("network", (result.exceptionOrNull() as PluginFetchException).code)
    }

    // The side effects of one shared prompt happen once, not once per caller that shared it. The
    // prompt is held open until the second caller has had time to arrive and wait on it; should it
    // arrive late anyway, it finds the host already declared and asks nothing -- so every assertion
    // below holds either way, and a regression to "each waiter applies the answer" fails it.
    @Test fun `two concurrent misses on the same host approve it once and add it once`() = runBlocking {
        val offline = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException(hostname) }
        val live = LiveHosts(EffectiveHosts(listOf("archive.org")))
        val approvedCalls = java.util.concurrent.atomic.AtomicInteger()
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val asking = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val h = PluginHttp(
            OkHttpClient(), "test", live, "1.0", allowInsecureLocalhost = true, delegateDns = offline,
            reactiveApproval = PluginHttp.ReactiveApproval(
                "P",
                HostApprovalRequester { _, _, _ -> asks.incrementAndGet(); asking.complete(Unit); release.await(); true },
                { approvedCalls.incrementAndGet() }, {}, emptySet(),
            ),
        )
        val url = "https://new-cdn.example/x"
        val r1 = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { h.fetch(PluginHttp.Request(url)) } }
        asking.await()
        val r2 = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { h.fetch(PluginHttp.Request(url)) } }
        kotlinx.coroutines.delay(200)
        release.complete(Unit)
        // Both got past the host gate (offline DNS -> "network"), neither was refused.
        assertEquals("network", (r1.await().exceptionOrNull() as PluginFetchException).code)
        assertEquals("network", (r2.await().exceptionOrNull() as PluginFetchException).code)
        assertEquals(1, asks.get())
        assertEquals(1, approvedCalls.get())
        assertEquals(listOf("archive.org", "new-cdn.example"), live.value.declared)
    }

    // areshd real-network run: three vidhidepro.com links 301'd at once to the new vidhidefast.com;
    // one was approved, and a request that missed just before that approval landed was refused as
    // "not a gap" (the host was declared by then) instead of going through.
    @Test fun `concurrent misses racing an instant approval all get through, asked once`() = runBlocking {
        val offline = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException(hostname) }
        repeat(200) {
            val asks = java.util.concurrent.atomic.AtomicInteger()
            val h = PluginHttp(
                OkHttpClient(), "test", EffectiveHosts(listOf("archive.org")), "1.0", delegateDns = offline, log = {},
                reactiveApproval = PluginHttp.ReactiveApproval("P", HostApprovalRequester { _, _, _ -> asks.incrementAndGet(); true }, {}, {}, emptySet()),
            )
            val results = (1..3).map {
                async(kotlinx.coroutines.Dispatchers.Default) { runCatching { h.fetch(PluginHttp.Request("https://new-cdn.example/x")) } }
            }.map { it.await() }
            // Offline DNS: "network" proves each one got past the host gate.
            results.forEach { assertEquals("network", (it.exceptionOrNull() as PluginFetchException).code) }
            assertEquals(1, asks.get())
        }
    }

    // Finding 1 of the final review: the cookie jar and `kino.cookies.get` read the SAME live host
    // set as kino.fetch, so a host approved mid-call is theirs too for the rest of that call.
    @Test fun `a reactively approved host is at once allowed by the shared cookie jar and kino cookies get`() = runTest {
        val offline = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException(hostname) }
        val live = LiveHosts(EffectiveHosts(listOf("archive.org")))
        val jar = PluginCookies(tmp.root.resolve("cookies-live.json"), live)
        val h = PluginHttp(
            OkHttpClient(), "test", live, "1.0", cookies = jar, allowInsecureLocalhost = true, delegateDns = offline,
            reactiveApproval = PluginHttp.ReactiveApproval("P", HostApprovalRequester { _, _, _ -> true }, {}, {}, emptySet()),
        )
        val host = DefaultPluginHost("test", h, PluginStorage(tmp.root.resolve("s-live.json")), cookies = jar, logger = {})
        val newHost = "https://new-cdn.example/".toHttpUrl()
        val sid = okhttp3.Cookie.parse(newHost, "sid=abc; Path=/")!!
        // Before: the jar keeps nothing from a host the plugin may not reach.
        jar.saveFromResponse(newHost, listOf(sid))
        assertEquals(0, jar.size())
        assertNull(host.cookieGet(newHost.toString(), "sid"))
        assertEquals("network", fetchError(h, PluginHttp.Request("https://new-cdn.example/login")).code)
        // After the person said yes: no second host list lagging behind anywhere.
        jar.saveFromResponse(newHost, listOf(sid))
        assertEquals(1, jar.size())
        assertEquals("abc", host.cookieGet(newHost.toString(), "sid"))
        assertTrue(HostRules.matches("new-cdn.example", h.hosts.declared))
    }

    // Two approvals of DIFFERENT hosts racing at 19 hosts both land: neither is lost to the other,
    // and there is no cap to fill.
    @Test fun `two approvals racing past 20 hosts both land`() = runBlocking {
        val offline = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException(hostname) }
        val live = LiveHosts(EffectiveHosts((1..19).map { "h$it.example.com" }))
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val approved = java.util.concurrent.CopyOnWriteArrayList<String>()
        val slowAsking = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val h = PluginHttp(
            OkHttpClient(), "test", live, "1.0", allowInsecureLocalhost = true, delegateDns = offline,
            reactiveApproval = PluginHttp.ReactiveApproval(
                "P",
                HostApprovalRequester { _, _, host ->
                    if (host == "slow.example.org") { slowAsking.complete(Unit); releaseSlow.await() }
                    true
                },
                { approved += it }, {}, emptySet(),
            ),
            log = { logs += it },
        )
        val slow = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { h.fetch(PluginHttp.Request("https://slow.example.org/x")) } }
        slowAsking.await()
        // Meanwhile another host is approved and becomes the 20th.
        assertEquals("network", fetchError(h, PluginHttp.Request("https://fast.example.org/x")).code)
        releaseSlow.complete(Unit)
        assertEquals("network", (slow.await().exceptionOrNull() as PluginFetchException).code)
        assertEquals(listOf("fast.example.org", "slow.example.org"), approved.toList())
        assertEquals(21, live.value.declared.size)
        assertTrue(logs.toString(), logs.none { "limit" in it })
    }

    // --- Only a call someone is waiting on may ask (PluginCallTracker) ---

    private val offlineDns = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException(hostname) }

    private fun runningCall(function: String, interactive: Boolean = true) =
        PluginCall(function, interactive, PluginCallClock(20_000))

    private class Asked {
        val hosts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val approved = java.util.concurrent.CopyOnWriteArrayList<String>()
        val rejected = java.util.concurrent.CopyOnWriteArrayList<String>()
    }

    private fun trackedHttp(
        calls: PluginCallTracker,
        asked: Asked,
        logs: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList(),
        answer: suspend (String) -> Boolean? = { true },
    ) = PluginHttp(
        OkHttpClient(), "test", EffectiveHosts(listOf("archive.org")), "1.0", allowInsecureLocalhost = true, delegateDns = offlineDns,
        reactiveApproval = PluginHttp.ReactiveApproval(
            "P", HostApprovalRequester { _, _, host -> asked.hosts += host; answer(host) },
            { asked.approved += it }, { asked.rejected += it }, emptySet(),
        ),
        calls = calls,
        log = { logs += it },
    )

    @Test fun `a miss while no call is running asks nothing and records nothing`() = runTest {
        val asked = Asked()
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val h = trackedHttp(PluginCallTracker(), asked, logs)
        assertEquals("host_not_allowed", fetchError(h, PluginHttp.Request("https://greenmotors.cc/x")).code)
        assertEquals(emptyList<String>(), asked.hosts.toList())
        assertEquals(emptyList<String>(), asked.rejected.toList())
        assertTrue(logs.toString(), logs.any { "greenmotors.cc" in it && "not asked" in it })
    }

    // Search, Home rows and "Ver más" run for many sources at once while the person types or scrolls:
    // a dialog per source would bury the screen. Only resolve (the person tapped play) and episodes ask.
    @Test fun `a miss during search, home or browse asks nothing`() = runTest {
        for (function in listOf("search", "home", "browse", "liveChannels")) {
            val asked = Asked()
            val calls = PluginCallTracker().apply { begin(runningCall(function)) }
            val h = trackedHttp(calls, asked)
            assertEquals(function, "host_not_allowed", fetchError(h, PluginHttp.Request("https://new-cdn.example/x")).code)
            assertEquals(function, emptyList<String>(), asked.hosts.toList())
            assertEquals(function, emptyList<String>(), asked.rejected.toList())
        }
    }

    @Test fun `a miss in a background call asks nothing`() = runTest {
        val asked = Asked()
        val calls = PluginCallTracker().apply { begin(runningCall("resolve", interactive = false)) }
        assertEquals("host_not_allowed", fetchError(trackedHttp(calls, asked), PluginHttp.Request("https://new-cdn.example/x")).code)
        assertEquals(emptyList<String>(), asked.hosts.toList())
    }

    @Test fun `a miss during resolve or episodes asks, with the call's clock stopped while it waits`() = runTest {
        for (function in listOf("resolve", "episodes")) {
            val asked = Asked()
            val call = runningCall(function)
            val calls = PluginCallTracker().apply { begin(call) }
            var remainingWhileAsking: Long? = -1
            val h = trackedHttp(calls, asked) { remainingWhileAsking = call.clock.remainingMs(); true }
            // Offline DNS: "network" proves the approved request went past the host gate.
            assertEquals(function, "network", fetchError(h, PluginHttp.Request("https://new-cdn.example/x")).code)
            assertEquals(function, listOf("new-cdn.example"), asked.hosts.toList())
            assertEquals(function, listOf("new-cdn.example"), asked.approved.toList())
            assertNull("$function: the clock is paused while the person decides", remainingWhileAsking)
            assertTrue(function, call.clock.remainingMs()!! > 0)
        }
    }

    // The person leaves the screen (or the call fails) while the question is up: it comes down and
    // nothing is recorded, not even as a rejection.
    @Test fun `a question whose call ends while it is on screen comes down and records nothing`() = runBlocking {
        val asked = Asked()
        val call = runningCall("resolve")
        val calls = PluginCallTracker().apply { begin(call) }
        val showing = CompletableDeferred<Unit>()
        var questionCancelled = false
        val h = trackedHttp(calls, asked) {
            showing.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally { questionCancelled = true }
        }
        val fetch = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { h.fetch(PluginHttp.Request("https://gamerxyt.com/x")) } }
        kotlinx.coroutines.withTimeout(5_000) { showing.await() }
        calls.end(call)
        val failure = kotlinx.coroutines.withTimeout(5_000) { fetch.await() }.exceptionOrNull()
        assertEquals("host_not_allowed", (failure as PluginFetchException).code)
        assertTrue(questionCancelled)
        assertEquals(emptyList<String>(), asked.rejected.toList())
        assertEquals(emptyList<String>(), asked.approved.toList())
        // And a later miss of that same finished call asks nothing more.
        assertEquals("host_not_allowed", fetchError(h, PluginHttp.Request("https://gamerxyt.com/y")).code)
        assertEquals(listOf("gamerxyt.com"), asked.hosts.toList())
    }

    // --- What a call's fetches met, for telling the person why it failed (PluginCallTrace) ---

    @Test fun `the call's trace records refusals, with why`() = runTest {
        val search = runningCall("search")
        trackedHttp(PluginCallTracker().apply { begin(search) }, Asked()).let { runCatching { it.fetch(PluginHttp.Request("https://new-cdn.example/x")) } }
        assertEquals(listOf(PluginCallTrace.Refused("new-cdn.example", PluginCallTrace.Refusal.NOT_ASKED)), search.trace.events)

        val resolve = runningCall("resolve")
        trackedHttp(PluginCallTracker().apply { begin(resolve) }, Asked()) { false }.let { runCatching { it.fetch(PluginHttp.Request("https://gamerxyt.com/x")) } }
        assertEquals(listOf(PluginCallTrace.Refused("gamerxyt.com", PluginCallTrace.Refusal.REJECTED_NOW)), resolve.trace.events)

        val again = runningCall("resolve")
        val before = PluginHttp(
            OkHttpClient(), "test", EffectiveHosts(listOf("archive.org")), "1.0", allowInsecureLocalhost = true,
            reactiveApproval = PluginHttp.ReactiveApproval("P", HostApprovalRequester { _, _, _ -> true }, {}, {}, setOf("greenmotors.cc")),
            calls = PluginCallTracker().apply { begin(again) },
        )
        runCatching { before.fetch(PluginHttp.Request("https://greenmotors.cc/x")) }
        assertEquals(listOf(PluginCallTrace.Refused("greenmotors.cc", PluginCallTrace.Refusal.REJECTED_BEFORE)), again.trace.events)
    }

    @Test fun `the call's trace records a site that could not be found, and nothing left waiting`() = runTest {
        val call = runningCall("resolve")
        // Approved at once, then the offline DNS fails it: "no se encontró el sitio".
        trackedHttp(PluginCallTracker().apply { begin(call) }, Asked()).let { runCatching { it.fetch(PluginHttp.Request("https://4khdhub.click/x")) } }
        assertEquals(listOf(PluginCallTrace.Failed("4khdhub.click", PluginCallTrace.Failure.DNS)), call.trace.events)
        assertNull(call.trace.waitingFor)
    }

    @Test fun `the call's trace records a server error and a timeout`() = runBlocking {
        val call = runningCall("resolve")
        val h = PluginHttp(
            OkHttpClient(), "test", EffectiveHosts(listOf("localhost")), "1.0", allowInsecureLocalhost = true,
            calls = PluginCallTracker().apply { begin(call) },
        )
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("ok").setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(503, h.fetch(PluginHttp.Request(url("/a"))).status)
        assertEquals("timeout", fetchError(h, PluginHttp.Request(url("/slow"), timeoutMs = 300)).code)
        assertEquals(
            listOf(PluginCallTrace.Answered("localhost", 503), PluginCallTrace.Failed("localhost", PluginCallTrace.Failure.TIMEOUT)),
            call.trace.events,
        )
        assertNull(call.trace.waitingFor)
    }

    @Test fun `a call asks about at most 3 hosts, then fails the rest silently`() = runTest {
        val asked = Asked()
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val calls = PluginCallTracker().apply { begin(runningCall("resolve")) }
        val h = trackedHttp(calls, asked, logs) { true }
        for (i in 1..3) assertEquals("network", fetchError(h, PluginHttp.Request("https://m$i.example.com/x")).code)
        for (i in 4..8) assertEquals("host_not_allowed", fetchError(h, PluginHttp.Request("https://m$i.example.com/x")).code)
        assertEquals((1..3).map { "m$it.example.com" }, asked.hosts.toList())
        assertTrue(logs.toString(), logs.any { "m4.example.com" in it && "already 3 host questions" in it })
        // A new call gets its own questions.
        calls.begin(runningCall("resolve"))
        assertEquals("network", fetchError(h, PluginHttp.Request("https://m9.example.com/x")).code)
        assertEquals("m9.example.com", asked.hosts.last())
    }

    @Test fun `after a no, nothing else is asked in that call`() = runTest {
        val asked = Asked()
        val calls = PluginCallTracker().apply { begin(runningCall("resolve")) }
        val h = trackedHttp(calls, asked) { false }
        repeat(4) { assertEquals("host_not_allowed", fetchError(h, PluginHttp.Request("https://m$it.example.com/x")).code) }
        assertEquals(listOf("m0.example.com"), asked.hosts.toList())
        assertEquals(listOf("m0.example.com"), asked.rejected.toList())
    }

    @Test fun `at most 6 fetches of one runtime are in flight at once`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                peak.accumulateAndGet(now.incrementAndGet(), ::maxOf)
                Thread.sleep(150)
                now.decrementAndGet()
                return MockResponse().setBody("x")
            }
        }
        val h = http()
        h.beginCall()
        val all = (1..14).map { async(kotlinx.coroutines.Dispatchers.Default) { h.fetch(PluginHttp.Request(url("/p$it"))).text } }
        assertEquals(List(14) { "x" }, all.map { it.await() })
        assertTrue("peak ${peak.get()}", peak.get() in 2..PluginHttp.MAX_FETCHES_IN_FLIGHT)
    }

    @Test fun `refused hops count toward the request budget`() = runTest {
        val h = PluginHttp(OkHttpClient(), "test", EffectiveHosts(listOf("archive.org")), "1.0", maxRequestsPerCall = 5)
        h.beginCall()
        repeat(5) { assertEquals("host_not_allowed", fetchError(h, PluginHttp.Request("https://m$it.example.com/x")).code) }
        assertEquals("invalid_request", fetchError(h, PluginHttp.Request("https://m9.example.com/x")).code)
    }

    @Test fun `the same host is asked once per call`() = runTest {
        val asked = Asked()
        val calls = PluginCallTracker().apply { begin(runningCall("resolve")) }
        val h = trackedHttp(calls, asked) { false }
        repeat(3) { assertEquals("host_not_allowed", fetchError(h, PluginHttp.Request("https://greenmotors.cc/$it")).code) }
        assertEquals(listOf("greenmotors.cc"), asked.hosts.toList())
        assertEquals(listOf("greenmotors.cc"), asked.rejected.toList())
    }

    // --- Sealed secrets (spec 2026-09-29-plugin-sealed-secrets §5): a request that carries a
    // substituted value goes only to a host the MANIFEST declared, over https, on every hop. ---

    private val loopback = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }

    /**
     * localhost (the test-only plain-http exception a sealed request also honors), declared.example
     * and other.example all resolve to this MockWebServer; the last two over plain http as `insecureHttp`.
     */
    private fun sealedHttp(log: (String) -> Unit = {}, reactive: PluginHttp.ReactiveApproval? = null, user: List<UserHost> = emptyList()): PluginHttp {
        val insecure = setOf("declared.example", "other.example")
        return PluginHttp(
            OkHttpClient(), "test", EffectiveHosts(listOf("localhost") + insecure, user, insecure = insecure), "1.0", cookies = null,
            allowInsecureLocalhost = true, delegateDns = loopback, reactiveApproval = reactive, log = log,
        )
    }

    private fun sealedSecrets(plain: String = "k-123", hosts: List<String> = listOf("localhost", "declared.example")) = PluginSecrets(
        mapOf("apiKey" to TestSealing.seal(plain, "owner/repo", "apiKey")), "owner/repo", TestSealing.agreement,
        sealedHosts = hosts, recipient = TestSealing.TEST_PUBLIC,
    )

    private fun sealedHost(secrets: PluginSecrets, log: (String) -> Unit = {}) =
        DefaultPluginHost("test", sealedHttp(log = log), PluginStorage(tmp.root.resolve("s-${System.nanoTime()}.json")), allowInsecureLocalhost = true, secrets = secrets, logger = {})

    private fun fetchJson(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: org.json.JSONObject? = null) =
        org.json.JSONObject().put("url", url).put("method", method).put("headers", org.json.JSONObject(headers))
            .apply { if (body != null) put("body", body) }.toString()

    private fun local(path: String) = "http://localhost:${server.port}$path"

    @Test fun `a request with a secret reaches a declared host`() = runBlocking {
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val secrets = sealedSecrets()
        val m = secrets.marker("apiKey")!!
        server.enqueue(MockResponse().setBody("ok"))
        val form = org.json.JSONObject().put("kind", "form").put("value", org.json.JSONArray().put(org.json.JSONArray().put("key").put(m)))
        val out = org.json.JSONObject(sealedHost(secrets, log = { logs += it }).fetch(fetchJson(local("/v1/$m/items?api_key=$m&q=x"), "POST", mapOf("Authorization" to "Bearer $m"), form)))
        assertEquals(200, out.getInt("status"))
        val seen = server.takeRequest()
        assertEquals("/v1/k-123/items?api_key=k-123&q=x", seen.path)
        assertEquals("Bearer k-123", seen.getHeader("Authorization"))
        assertEquals("key=k-123", seen.body.readUtf8())
        // What goes back to the plugin carries the marker, never the value -- and so does the log,
        // which names the host only.
        assertFalse(out.toString(), "k-123" in out.toString())
        assertEquals(local("/v1/$m/items?api_key=$m&q=x"), out.getString("url"))
        assertTrue(logs.toString(), logs.any { "fetch POST localhost (datos sellados) -> 200" in it })
        assertFalse(logs.toString(), logs.any { "k-123" in it || "/v1/" in it })
    }

    @Test fun `a secret in a JSON or text body is substituted too`() = runBlocking {
        val secrets = sealedSecrets()
        val m = secrets.marker("apiKey")!!
        val host = sealedHost(secrets)
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))
        host.fetch(fetchJson(local("/p"), "POST", body = org.json.JSONObject().put("kind", "json").put("value", "{\"key\":\"$m\"}")))
        host.fetch(fetchJson(local("/p"), "POST", body = org.json.JSONObject().put("kind", "text").put("value", "key=$m")))
        assertEquals("{\"key\":\"k-123\"}", server.takeRequest().body.readUtf8())
        assertEquals("key=k-123", server.takeRequest().body.readUtf8())
    }

    // Fix round 1: a value is written in its context's own encoding, so it arrives as exactly ONE
    // value, and no decodable form of it comes back to the plugin in the final URL.
    @Test fun `every value arrives whole in the path, the query and a JSON body, and the plugin gets no form of it`() = runBlocking {
        for (value in listOf("abc+def/gh=", "p&ss=1", "a b\"c\\d", "sé%41x")) {
            val secrets = sealedSecrets(plain = value)
            val m = secrets.marker("apiKey")!!
            server.enqueue(MockResponse().setBody("ok"))
            // What the prelude sends for `body: { json: { key: k } }`: JSON.stringify's text.
            val json = org.json.JSONObject().put("kind", "json").put("value", org.json.JSONObject().put("key", m).toString())
            val raw = sealedHost(secrets).fetch(fetchJson(local("/v1/pre-$m-post/x?api_key=$m&q=1"), "POST", body = json))
            val seen = server.takeRequest()
            val u = seen.requestUrl!!
            assertEquals(value, listOf("v1", "pre-$value-post", "x"), u.pathSegments)
            assertEquals(value, setOf("api_key", "q"), u.queryParameterNames)
            assertEquals(value, value, u.queryParameter("api_key"))
            assertEquals(value, "1", u.queryParameter("q"))
            assertEquals(value, value, org.json.JSONObject(seen.body.readUtf8()).getString("key"))
            val out = org.json.JSONObject(raw)
            assertEquals(value, 200, out.getInt("status"))
            val url = out.getString("url")
            // Neither the value nor anything decoding to it: URLDecoder (form, `+` is a space) and
            // decodeURIComponent (`+` stays) both.
            for (text in listOf(raw, url, java.net.URLDecoder.decode(url, "UTF-8"), java.net.URLDecoder.decode(url.replace("+", "%2B"), "UTF-8"))) {
                assertFalse("$value in $text", value in text)
            }
            assertEquals(value, local("/v1/pre-$m-post/x?api_key=$m&q=1"), url)
        }
    }

    @Test fun `a marker in the userinfo stays a marker`() = runBlocking {
        val secrets = sealedSecrets()
        val m = secrets.marker("apiKey")!!
        server.enqueue(MockResponse().setBody("ok"))
        val out = org.json.JSONObject(sealedHost(secrets).fetch(fetchJson("http://$m:$m@localhost:${server.port}/x?k=$m")))
        assertEquals(200, out.getInt("status"))
        val seen = server.takeRequest()
        assertEquals("/x?k=k-123", seen.path)
        assertFalse(seen.headers.toString(), "k-123" in seen.headers.toString())
        assertTrue(out.getString("url"), out.getString("url").startsWith("http://$m:$m@localhost:"))
    }

    @Test fun `a marker in the host is never substituted`() = runBlocking {
        val secrets = sealedSecrets()
        val m = secrets.marker("apiKey")!!
        val out = org.json.JSONObject(sealedHost(secrets).fetch(fetchJson("http://$m.example:${server.port}/x?k=$m")))
        val message = out.getJSONObject("error").getString("message")
        assertFalse(message, "k-123" in message)
        assertEquals("este plugin no puede enviar datos sellados a ${m.lowercase()}.example", message)
        assertEquals(0, server.requestCount)
    }

    @Test fun `a secret never goes to an undeclared host`() = runBlocking {
        val secrets = sealedSecrets()
        val m = secrets.marker("apiKey")!!
        val host = sealedHost(secrets)
        // other.example IS in the plugin's host set -- only not in its manifest's `hosts`.
        val out = org.json.JSONObject(host.fetch(fetchJson("http://other.example:${server.port}/x?k=$m")))
        val error = out.getJSONObject("error")
        assertEquals("host_not_allowed", error.getString("code"))
        assertEquals("este plugin no puede enviar datos sellados a other.example", error.getString("message"))
        assertEquals(0, server.requestCount)
        // The same request without a marker is an ordinary one: it goes.
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals(200, org.json.JSONObject(host.fetch(fetchJson("http://other.example:${server.port}/x"))).getInt("status"))
    }

    @Test fun `a secret never goes over plain http, not even to a host declared insecureHttp`() = runBlocking {
        val secrets = sealedSecrets()
        val m = secrets.marker("apiKey")!!
        val host = sealedHost(secrets)
        val out = org.json.JSONObject(host.fetch(fetchJson("http://declared.example:${server.port}/x?k=$m")))
        val error = out.getJSONObject("error")
        assertEquals("host_not_allowed", error.getString("code"))
        assertEquals("este plugin no puede enviar datos sellados sin https a declared.example", error.getString("message"))
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals(200, org.json.JSONObject(host.fetch(fetchJson("http://declared.example:${server.port}/x"))).getInt("status"))
    }

    @Test fun `a redirect to plain http is refused on that hop`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://declared.example:${server.port}/landing"))
        val e = fetchError(sealedHttp(), PluginHttp.Request(local("/start"), sealedTo = listOf("localhost", "declared.example")))
        assertEquals("host_not_allowed", e.code)
        assertEquals("este plugin no puede enviar datos sellados sin https a declared.example", e.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun `the sealed-host rule is checked on the request itself, whatever the host set says`() {
        val e = fetchError(sealedHttp(), PluginHttp.Request("http://other.example:${server.port}/x", sealedTo = listOf("declared.example")))
        assertEquals("host_not_allowed", e.code)
        assertEquals("este plugin no puede enviar datos sellados a other.example", e.message)
        assertEquals(0, server.requestCount)
    }

    @Test fun `redirect off the declared hosts is refused`() {
        // 302 on a GET, and the redirects that turn a POST into a GET (303; 301/302 after a POST).
        for ((code, method) in listOf(302 to "GET", 303 to "POST", 301 to "POST", 302 to "POST")) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", "https://other.example/landing"))
            val body = if (method == "POST") PluginHttp.Body.Text("k=k-123") else null
            val e = fetchError(sealedHttp(), PluginHttp.Request(local("/start?k=k-123"), method, body = body, sealedTo = listOf("localhost")))
            assertEquals("$code $method", "host_not_allowed", e.code)
            assertEquals("$code $method", "este plugin no puede enviar datos sellados a other.example", e.message)
            assertFalse(e.message!!, "k-123" in e.message!!)
            assertEquals("$code $method", before + 1, server.requestCount)
        }
    }

    @Test fun `a sealed request may follow a redirect within the declared hosts`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", local("/landing")))
        server.enqueue(MockResponse().setBody("ok"))
        val r = sealedHttp().fetch(PluginHttp.Request(local("/start"), sealedTo = listOf("localhost")))
        assertEquals(200, r.status)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a manual redirect hands back the 3xx without following it`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example/landing"))
        val r = sealedHttp().fetch(PluginHttp.Request(local("/start"), manualRedirects = true, sealedTo = listOf("localhost")))
        assertEquals(302, r.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun `reactively approved and user hosts don't count`() {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val reactive = PluginHttp.ReactiveApproval("P", HostApprovalRequester { _, _, _ -> asks.incrementAndGet(); true }, {}, {}, emptySet())
        val nas = UserHost("http", "nas.example", server.port)
        val h = sealedHttp(reactive = reactive, user = listOf(nas))
        val sealedTo = listOf("localhost")
        // other.example stands for a host approved in the moment: in the host set, not the manifest.
        listOf("http://other.example:${server.port}/x", "http://nas.example:${server.port}/x", "https://fresh.example/x").forEach { u ->
            val e = fetchError(h, PluginHttp.Request(u, sealedTo = sealedTo))
            assertEquals(u, "host_not_allowed", e.code)
            assertTrue(u, e.message!!.startsWith("este plugin no puede enviar datos sellados a "))
        }
        assertEquals(0, asks.get())
        assertEquals(0, server.requestCount)
        // Control: the very same undeclared https host WOULD have been asked about without a secret.
        fetchError(h, PluginHttp.Request("https://fresh.example/x"))
        assertEquals(1, asks.get())
    }

    @Test fun `the refused host is cut to 100 characters`() {
        val long = "a".repeat(60) + "." + "b".repeat(60) + ".example"
        val e = fetchError(sealedHttp(), PluginHttp.Request("https://$long/x", sealedTo = listOf("declared.example")))
        assertEquals("este plugin no puede enviar datos sellados a " + long.take(100), e.message)
    }
}
