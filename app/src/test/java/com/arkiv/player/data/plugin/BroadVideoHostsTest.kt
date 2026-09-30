package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The broad video permission: ONE consent per plugin that lets the PLAYER fetch that plugin's video
 * (the resolved VOD stream, everything its manifest names, redirects, side subtitles and audio) from
 * any public server -- the `liveStreamHosts: "any"` rule applied to VOD, granted only by the person
 * ([InstalledRecord.anyVideoHost]). Never the home network, never an IPv6 literal, never `kino.fetch`,
 * never a DRM license; a download follows the player's rule.
 */
class BroadVideoHostsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var store: PluginStore
    private lateinit var registry: PluginRegistry

    @Before fun setUp() {
        server.start()
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        registry = PluginRegistry(store)
    }

    @After fun stop() = server.shutdown()

    private fun install(id: String = ID, record: InstalledRecord.() -> InstalledRecord = { this }) {
        val manifest = JSONObject().put("id", id).put("name", "Demo").put("version", "1.0.0").put("apiVersion", 2)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve", "drm"))).toString()
        val script = "export async function search(){}".toByteArray()
        val staging = store.newStaging(id)
        store.writeFiles(staging, manifest, "plugin.js", script, null,
            InstalledRecord("o/$id", "1.0.0", sha256Hex(script), listOf("example.com"), 1L).record())
        store.commit(staging, id)
        registry.reload()
    }

    private val video = EffectiveHosts(listOf("example.com"), anyPublicVideoHost = true)

    // --- the record ---

    @Test fun `the flag survives a JSON round trip and an older record reads as not granted`() {
        val r = InstalledRecord("o/r", "1.0.0", "abc", listOf("example.com"), 1L, anyVideoHost = true)
        assertTrue(InstalledRecord.fromJson(r.toJson())!!.anyVideoHost)
        val old = JSONObject(r.toJson()).apply { remove("anyVideoHost") }.toString()
        assertFalse(InstalledRecord.fromJson(old)!!.anyVideoHost)
        assertFalse(InstalledRecord("o/r", "1.0.0", "abc", emptyList(), 1L).anyVideoHost)
    }

    @Test fun `only the person grants and revokes it, through the registry`() {
        install()
        assertFalse(registry.find(ID)!!.record.anyVideoHost)
        registry.setAnyVideoHost(ID, true)
        assertTrue(registry.find(ID)!!.record.anyVideoHost)
        registry.setAnyVideoHost(ID, false)
        assertFalse(registry.find(ID)!!.record.anyVideoHost)
        // Uninstall drops it with the rest of the record.
        registry.setAnyVideoHost(ID, true)
        registry.uninstall(ID)
        install()
        assertFalse(registry.find(ID)!!.record.anyVideoHost)
    }

    // --- who gets relaxed hosts ---

    @Test fun `the plugin's own hosts never carry it, its video hosts do, and only for its VOD refs`() {
        install { copy(anyVideoHost = true) }
        val p = registry.find(ID)!!
        assertFalse(p.hosts.anyPublicStreamHost)
        assertTrue(p.videoHosts.anyPublicVideoHost)
        assertEquals(p.hosts.copy(anyPublicVideoHost = true), p.videoHosts)
        // A live channel keeps its own rule (liveStreamHosts), never the VOD permission.
        assertFalse(p.liveHosts.anyPublicStreamHost)
        val ready = registry.accessFor(ID) as PluginAccess.Ready
        assertEquals(p.videoHosts, ready.streamHostsFor(ID, PluginRef(ID, "m1", PluginRef.MOVIE, "m-1").encode()))
        assertEquals(p.videoHosts, ready.streamHostsFor(ID, PluginRef(ID, "e1", PluginRef.EPISODE, "e-1").encode()))
        assertEquals(p.liveHosts, ready.streamHostsFor(ID, PluginRef(ID, "c1", PluginRef.LIVE, "ch").encode()))
        // Another plugin's ref, or garbage: the strict hosts.
        assertEquals(ready.hosts, ready.streamHostsFor(ID, PluginRef("other", "m1", PluginRef.MOVIE, "m").encode()))
        assertEquals(ready.hosts, ready.streamHostsFor(ID, "nope"))
        // Not granted: nothing relaxes.
        install("pb")
        val strict = registry.accessFor("pb") as PluginAccess.Ready
        assertEquals(strict.hosts, strict.streamHostsFor("pb", PluginRef("pb", "m1", PluginRef.MOVIE, "m").encode()))
    }

    // --- what the returned Stream may name ---

    @Test fun `a VOD stream on any public host passes, its subtitles and audio too, never its license`() {
        val s = PluginOutput.stream(
            """{"url":"https://jeremyparticipantanything.com/hls/master.m3u8",
               "subtitles":[{"lang":"es","url":"https://subs.elsewhere.org/a.vtt"}],
               "audioTracks":[{"lang":"es","url":"https://audio.elsewhere.org/a.m4a"}]}""",
            video,
        )
        assertEquals("https://jeremyparticipantanything.com/hls/master.m3u8", s.url)
        assertEquals(listOf("https://subs.elsewhere.org/a.vtt"), s.subtitles.map { it.url })
        assertEquals(listOf("https://audio.elsewhere.org/a.m4a"), s.audioTracks.map { it.url })
        // Same scheme and literal rule as live "any": http and a public IPv4 literal.
        assertEquals("http://203.0.113.7:8080/v.mp4", PluginOutput.stream("""{"url":"http://203.0.113.7:8080/v.mp4"}""", video).url)
        // The license stays on the declared hosts.
        assertThrows(PluginContractException::class.java) {
            PluginOutput.stream("""{"url":"https://cdn.elsewhere.org/1.mpd","drm":{"type":"widevine","licenseUrl":"https://lic.elsewhere.org/"}}""", video, allowDrm = true)
        }
    }

    @Test fun `never the home network nor an IPv6 literal`() {
        listOf("http://192.168.1.4/v.m3u8", "https://10.0.0.2/v.m3u8", "http://127.0.0.1/v.m3u8", "http://100.64.0.1/v.m3u8",
            "http://[fd00::1]/v.m3u8", "http://[2001:4860:4860::8888]/v.m3u8", "http://nas.local/v.m3u8", "http://tv.lan/v.m3u8", "http://localhost/v.m3u8",
        ).forEach { u -> assertThrows(u, PluginContractException::class.java) { PluginOutput.stream("""{"url":"$u"}""", video) } }
        val v6 = assertThrows(PluginContractException::class.java) { PluginOutput.stream("""{"url":"http://[2001:4860:4860::8888]/v.m3u8"}""", video) }
        assertEquals("El video solo puede estar en una dirección IPv4 pública o en un nombre de dominio", v6.message)
        val subtitle = PluginOutput.stream("""{"url":"https://cdn.elsewhere.org/v.m3u8","subtitles":[{"lang":"es","url":"http://192.168.1.4/a.vtt"}]}""", video)
        assertEquals(emptyList<PluginSubtitle>(), subtitle.subtitles)
    }

    @Test fun `with it nothing of the video is asked about any more, a license still is`() {
        val json = """{"url":"https://cdn.elsewhere.org/1.mpd","subtitles":[{"lang":"es","url":"https://subs.elsewhere.org/a.vtt"}],
            "drm":{"type":"widevine","licenseUrl":"https://lic.elsewhere.org/"}}"""
        assertEquals(listOf("lic.elsewhere.org"), PluginOutput.undeclaredHosts(json, video, allowDrm = true).map { it.host })
        assertFalse(PluginHostGate.isPromptableMiss("https://cdn.elsewhere.org/x".toHttpUrl(), video))
    }

    // --- the player's client ---

    private val loopbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    }

    private fun get(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test fun `the player's client fetches segments and redirects on any public name, never into the LAN`() {
        val client = PluginStreamHttp.client(OkHttpClient(), video, allowInsecureLocalhost = true, delegateDns = loopbackDns, askAboutFor = ID)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://p19-shard.tiktokcdn-like.test:${server.port}/seg2.ts"))
        server.enqueue(MockResponse().setBody("bytes"))
        get(client, "http://p16-shard.tiktokcdn-like.test:${server.port}/seg1.ts").use { assertEquals("bytes", it.body!!.string()) }
        assertEquals(2, server.requestCount)
        // A redirect onto a LAN literal never leaves the device.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://192.168.1.1/admin"))
        val e = assertThrows(HostNotAllowedException::class.java) { get(client, "http://cdn.elsewhere.test:${server.port}/v.m3u8").close() }
        assertEquals("host no permitido: 192.168.1.1", e.message)
        assertFalse(e is UndeclaredPlaybackHostException)
        // A public name that resolves into the home network is refused at connect time.
        val rebinding = object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(hostname, byteArrayOf(192.toByte(), 168.toByte(), 1, 1)))
        }
        val lan = PluginStreamHttp.client(OkHttpClient(), video, delegateDns = rebinding, askAboutFor = ID)
        assertThrows(UnknownHostException::class.java) { get(lan, "https://cdn.elsewhere.test/v.m3u8").close() }
    }

    @Test fun `every undeclared host the player reaches under it is logged once, host only`() {
        val used = mutableListOf<String>()
        val client = PluginStreamHttp.client(OkHttpClient(), video, allowInsecureLocalhost = true, delegateDns = loopbackDns, onAnyVideoHost = { used += it })
        repeat(2) { server.enqueue(MockResponse().setBody("x")) }
        get(client, "http://cdn.elsewhere.test:${server.port}/a.ts?token=secret").close()
        get(client, "http://cdn.elsewhere.test:${server.port}/b.ts?token=secret").close()
        assertEquals(listOf("cdn.elsewhere.test"), used)
    }

    // --- kino.fetch never gets it ---

    @Test fun `kino fetch stays on the plugin's hosts even when handed relaxed ones`() = runBlocking {
        val http = PluginHttp(OkHttpClient(), ID, video, "1.0", delegateDns = loopbackDns, log = {})
        val e = assertThrows(PluginFetchException::class.java) {
            runBlocking { http.fetch(PluginHttp.Request("https://cdn.elsewhere.org/x")) }
        }
        assertEquals("host_not_allowed", e.code)
        assertFalse(http.hosts.anyPublicStreamHost)
    }

    // --- resolve: only the player's own call, never a download ---

    private fun source(out: String): PluginContentSource {
        val p = registry.find(ID)!!
        return PluginContentSource(
            p, { _, _, _, _ -> out }, p.hosts, log = {},
            currentHosts = { registry.find(ID)!!.hosts },
            anyVideoHostGranted = { registry.find(ID)?.record?.anyVideoHost == true },
        )
    }

    private val movie = PluginRef(ID, "m1", PluginRef.MOVIE, "R1").encode()

    @Test fun `the player's and the download's resolve accept the stream once granted, any other background call does not`() = runBlocking {
        install { copy(anyVideoHost = true) }
        val out = """{"url":"https://jeremyparticipantanything.com/e/abc.m3u8"}"""
        val played = withContext(InteractivePluginCall) { source(out).resolve(movie) }
        assertEquals("https://jeremyparticipantanything.com/e/abc.m3u8", played.url)
        // The download queue's resolve follows the rule of playing that stream.
        val saved = withContext(BackgroundPluginCall + PluginDownloadCall) { source(out).resolve(movie) }
        assertEquals("https://jeremyparticipantanything.com/e/abc.m3u8", saved.url)
        val e = assertThrows(GatewayException::class.java) {
            runBlocking { withContext(BackgroundPluginCall + InteractivePluginCall) { source(out).resolve(movie) } }
        }
        assertEquals("Demo: El video apunta a jeremyparticipantanything.com, que el plugin no declaró", e.message)
        // Not granted: exactly as before, for the player and the download alike.
        registry.setAnyVideoHost(ID, false)
        assertThrows(GatewayException::class.java) { runBlocking { withContext(InteractivePluginCall) { source(out).resolve(movie) } } }
        assertThrows(GatewayException::class.java) { runBlocking { withContext(BackgroundPluginCall + PluginDownloadCall) { source(out).resolve(movie) } } }
        Unit
    }

    @Test fun `a download's resolve of a live channel never uses it`() = runBlocking {
        install { copy(anyVideoHost = true) }
        val live = PluginRef(ID, "c1", PluginRef.LIVE, "ch").encode()
        assertThrows(GatewayException::class.java) {
            runBlocking { withContext(BackgroundPluginCall + PluginDownloadCall) { source("""{"url":"https://cdn.elsewhere.org/live.m3u8"}""").resolve(live) } }
        }
        Unit
    }

    @Test fun `the download's client uses the plugin's video hosts, the same as the player's`() {
        install { copy(anyVideoHost = true) }
        assertTrue(registry.find(ID)!!.videoHosts.anyPublicVideoHost)
        registry.setAnyVideoHost(ID, false)
        assertFalse(registry.find(ID)!!.videoHosts.anyPublicVideoHost)
    }

    @Test fun `a live channel's resolve never uses it`() = runBlocking {
        install { copy(anyVideoHost = true) }
        val live = PluginRef(ID, "c1", PluginRef.LIVE, "ch").encode()
        assertThrows(GatewayException::class.java) {
            runBlocking { withContext(InteractivePluginCall) { source("""{"url":"https://cdn.elsewhere.org/live.m3u8"}""").resolve(live) } }
        }
        Unit
    }

    private companion object {
        const val ID = "demo"
    }
}
