package com.arkiv.player.data.plugin

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress

/**
 * The playback-time half of the [XuperStreams] carve-out. [PluginOutput.stream] already lets the
 * Xuper plugin's bridge-resolved `http://` CDN URL through; the player's own gate
 * ([PluginStreamGate] -> [PluginHostGate]) runs again on every real request and must agree, for
 * exactly that URL and nothing else -- and must behave as before whenever `xuper` is null.
 */
class XuperPlaybackGateTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cdn = "http://yuwc.cdn.magis.example/vod/abc/stream.ts?t=1"
    private val none = EffectiveHosts(emptyList())
    private fun vault(vararg urls: String) = XuperStreams().apply { urls.forEach { remember(it, mapOf("User-Agent" to "x"), emptyList()) } }

    // --- PluginHostGate, direct ---

    @Test fun `a URL the bridge resolved passes the gate though it is http and undeclared`() {
        PluginHostGate.check(cdn.toHttpUrl(), none, xuper = vault(cdn))
    }

    @Test fun `a registered URL matches in the canonical form OkHttp requests it, and nothing looser`() {
        val raw = "http://YUWC.cdn.magis.example/vod/a b.ts?x=1"
        val requested = raw.toHttpUrl() // host lowercased, space percent-encoded
        assertTrue(requested.toString() != raw)
        PluginHostGate.check(requested, none, xuper = vault(raw))
        // Same host, other path: a host match alone never passes.
        assertThrows(HostNotAllowedException::class.java) {
            PluginHostGate.check("http://yuwc.cdn.magis.example/vod/other.ts".toHttpUrl(), none, xuper = vault(raw))
        }
    }

    @Test fun `the same URL without the table, or with another table, is refused exactly as before`() {
        val before = runCatching { PluginHostGate.check(cdn.toHttpUrl(), none) }.exceptionOrNull()
        assertTrue(before is HostNotAllowedException)
        val nullXuper = runCatching { PluginHostGate.check(cdn.toHttpUrl(), none, xuper = null) }.exceptionOrNull()
        assertEquals(before!!.message, nullXuper!!.message)
        val fresh = runCatching { PluginHostGate.check(cdn.toHttpUrl(), none, xuper = XuperStreams()) }.exceptionOrNull()
        assertEquals(before.message, fresh!!.message)
    }

    @Test fun `an unregistered http URL is refused even with the table present`() {
        val other = "http://yuwc.cdn.magis.example/vod/abc/stream.ts?t=2" // one byte off
        assertThrows(HostNotAllowedException::class.java) { PluginHostGate.check(other.toHttpUrl(), none, xuper = vault(cdn)) }
        // Declared host, http, not in the table: still "solo se permite https".
        val declared = EffectiveHosts(listOf("yuwc.cdn.magis.example"))
        val e = assertThrows(PluginFetchException::class.java) { PluginHostGate.check(other.toHttpUrl(), declared, xuper = vault(cdn)) }
        assertEquals("solo se permite https", e.message)
    }

    @Test fun `a registered URL that is local is still refused`() {
        listOf("http://192.168.1.1/vod/x.ts", "http://127.0.0.1:8080/x.ts", "http://localhost/x.ts", "http://printer.local/x.ts").forEach { u ->
            assertThrows(u, HostNotAllowedException::class.java) { PluginHostGate.check(u.toHttpUrl(), none, xuper = vault(u)) }
        }
    }

    @Test fun `a redirect hop is waived only when its target is itself registered`() {
        val hop = "http://edge2.cdn.magis.example/vod/abc/stream.ts"
        PluginHostGate.checkRedirect(cdn.toHttpUrl(), hop.toHttpUrl(), none, xuper = vault(cdn, hop))
        assertThrows(HostNotAllowedException::class.java) {
            PluginHostGate.checkRedirect(cdn.toHttpUrl(), hop.toHttpUrl(), none, xuper = vault(cdn))
        }
        assertThrows(HostNotAllowedException::class.java) {
            PluginHostGate.checkRedirect(cdn.toHttpUrl(), hop.toHttpUrl(), none)
        }
    }

    // --- Through the real player client (PluginStreamHttp.client -> PluginStreamGate) ---

    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private val loopback = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    }

    // MockWebServer only serves localhost over http: the test-only override, as in PluginStreamHttpTest.
    // `localhost` is NOT declared here, so only the carve-out can let the request through.
    private fun client(xuper: XuperStreams?) =
        PluginStreamHttp.client(OkHttpClient(), none, allowInsecureLocalhost = true, delegateDns = loopback, xuper = xuper)

    private fun get(c: OkHttpClient, url: String) = c.newCall(Request.Builder().url(url).build()).execute()

    @Test fun `the player client plays a registered URL, and refuses it without the table`() {
        val url = "http://localhost:${server.port}/vod/stream.ts"
        server.enqueue(MockResponse().setBody("TS"))
        get(client(vault(url)), url).use { assertEquals("TS", it.body!!.string()) }
        assertThrows(HostNotAllowedException::class.java) { get(client(null), url) }
        assertThrows(HostNotAllowedException::class.java) { get(client(XuperStreams()), url) }
        assertEquals(1, server.requestCount)
    }

    @Test fun `the player client refuses a redirect from a registered URL to an unregistered one`() {
        val url = "http://localhost:${server.port}/vod/stream.ts"
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere.ts"))
        assertThrows(IOException::class.java) { get(client(vault(url)), url) }
        assertEquals(1, server.requestCount)
    }

    // --- Who gets the table: the installed record's address, never the manifest id ---

    private fun registry(address: String): PluginRegistry {
        val store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        val manifest = JSONObject().put("id", "xuper").put("name", "Xuper").put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).toString()
        val script = "export async function search(){}".toByteArray()
        val staging = store.newStaging("xuper")
        store.writeFiles(staging, manifest, "plugin.js", script, null,
            InstalledRecord(address, "1.0.0", sha256Hex(script), listOf("example.com"), 1L))
        store.commit(staging, "xuper")
        return PluginRegistry(store).apply { reload() }
    }

    @Test fun `only the recognized Xuper install is ready with the carve-out`() {
        assertTrue((registry(XuperPrivilege.SOURCE_REPO).accessFor("xuper") as PluginAccess.Ready).xuper)
    }

    @Test fun `a lookalike claiming the same manifest id gets no carve-out`() {
        assertFalse((registry("someone-else/kino-plugin-xuper").accessFor("xuper") as PluginAccess.Ready).xuper)
    }
}
