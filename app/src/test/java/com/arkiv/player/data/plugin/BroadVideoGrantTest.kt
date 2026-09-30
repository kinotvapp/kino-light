package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayPlayable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

/**
 * How the person grants the broad video permission: a third choice, "Permitir video de cualquier
 * servidor", in the resolve-time and playback-time host dialogs (never the fetch-time one, never a
 * DRM license's, never a live channel's). Once granted, no more video-host dialogs for that plugin.
 */
class BroadVideoGrantTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var registry: PluginRegistry
    private lateinit var center: HostApprovalCenter
    private val logs = mutableListOf<String>()

    @Before fun setUp() {
        server.start()
        install(listOf("example.com"))
        center = HostApprovalCenter(clock = { 0L }, log = {})
    }

    @After fun stop() = server.shutdown()

    private fun install(hosts: List<String>, record: InstalledRecord.() -> InstalledRecord = { this }) {
        val store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        val manifest = JSONObject().put("id", ID).put("name", NAME).put("version", "1.0.0").put("apiVersion", 2)
            .put("entry", "plugin.js").put("hosts", JSONArray(hosts))
            .put("capabilities", JSONArray(listOf("search", "resolve", "drm"))).toString()
        val script = "export async function search(){}".toByteArray()
        val staging = store.newStaging(ID)
        store.writeFiles(staging, manifest, "plugin.js", script, null, InstalledRecord("o/$ID", "1.0.0", sha256Hex(script), hosts, 1L).record())
        store.commit(staging, ID)
        registry = PluginRegistry(store, log = { logs += it }).also { it.reload() }
    }

    private val record get() = registry.find(ID)!!.record

    private fun approval() = StreamHostApproval(center, registry, log = { logs += it })

    private fun source(resolve: () -> String): PluginContentSource {
        val p = registry.find(ID)!!
        return PluginContentSource(
            p, { _, _, _, _ -> resolve() }, p.hosts, log = { logs += it },
            currentHosts = { registry.find(ID)!!.hosts },
            streamHostApproval = approval(),
            anyVideoHostGranted = { registry.find(ID)?.record?.anyVideoHost == true },
        )
    }

    private fun TestScope.play(src: PluginContentSource, ref: String = movie): Deferred<GatewayPlayable> =
        async(SupervisorJob()) { withContext(InteractivePluginCall) { src.resolve(ref) } }

    private suspend fun nextPrompt(): HostApprovalRequest {
        var req = center.pending.value
        while (req == null) { yield(); req = center.pending.value }
        return req
    }

    private val movie = PluginRef(ID, "m1", PluginRef.MOVIE, "R1").encode()

    // --- resolve time ---

    @Test fun `the video dialog offers it, and choosing it plays this title and every later one without asking`() = runTest {
        var answer = """{"url":"https://jeremyparticipantanything.com/e/1.m3u8","subtitles":[{"lang":"es","url":"https://subs.voe-like.org/a.vtt"}]}"""
        val src = source { answer }
        val playing = play(src)
        val req = nextPrompt()
        assertEquals(HostApprovalReason.VIDEO, req.reason)
        assertTrue(req.offersAnyVideoHost)
        req.answer(HostApprovalAnswer.ALLOW_ANY_VIDEO_HOST)
        val played = playing.await()
        assertEquals("https://jeremyparticipantanything.com/e/1.m3u8", played.url)
        // Its subtitle on yet another host was not asked about: the permission covers it.
        assertEquals(listOf("https://subs.voe-like.org/a.vtt"), played.subtitles.map { it.url })
        assertEquals(1, center.shownCount)
        assertTrue(record.anyVideoHost)
        // Nothing was added to the plugin's hosts: the permission is not a host.
        assertEquals(listOf("example.com"), record.hosts)
        assertTrue(logs.toString(), logs.any { "broad video permission granted" in it })

        // The next title is on another random domain: no dialog at all.
        answer = """{"url":"https://christopheruntilpossible.com/e/2.m3u8"}"""
        assertEquals("https://christopheruntilpossible.com/e/2.m3u8", play(src).await().url)
        assertEquals(1, center.shownCount)
        assertTrue(logs.toString(), logs.any { "christopheruntilpossible.com allowed by the broad video permission" in it })
        assertFalse("never a path or query in the log", logs.any { "/e/2.m3u8" in it })
    }

    @Test fun `allowing only this server is today's approval, and the permission stays off`() = runTest {
        val playing = play(source { """{"url":"https://cdn.other.example/v.mp4"}""" })
        nextPrompt().answer(HostApprovalAnswer.ALLOW_HOST)
        playing.await()
        assertFalse(record.anyVideoHost)
        assertEquals(listOf("example.com", "cdn.other.example"), record.hosts)
    }

    @Test fun `with 20 hosts approved the dialog offers both the permission and the single server`() = runTest {
        install(listOf("example.com") + (1..19).map { "h$it.example.com" })
        val playing = play(source { """{"url":"https://jeremyparticipantanything.com/e/1.m3u8"}""" })
        val req = nextPrompt()
        assertTrue(req.offersAnyVideoHost)
        assertFalse(req.question, "máximo" in req.question)
        req.answer(HostApprovalAnswer.ALLOW_ANY_VIDEO_HOST)
        assertEquals("https://jeremyparticipantanything.com/e/1.m3u8", playing.await().url)
        assertTrue(record.anyVideoHost)
    }

    @Test fun `a license host is asked about alone, never with the permission`() = runTest {
        install(listOf("example.com")) { copy(anyVideoHost = true) }
        val playing = play(source {
            """{"url":"https://cdn.other.example/1.mpd","drm":{"type":"widevine","licenseUrl":"https://lic.other.example/wv"}}"""
        })
        val req = nextPrompt()
        assertEquals(HostApprovalReason.LICENSE, req.reason)
        assertEquals("lic.other.example", req.host)
        assertFalse(req.offersAnyVideoHost)
        // A choice the dialog does not offer is ignored: the question stays up.
        req.answer(HostApprovalAnswer.ALLOW_ANY_VIDEO_HOST)
        assertEquals(req, center.pending.value)
        req.respond(true)
        playing.await()
        assertEquals(listOf("example.com", "lic.other.example"), record.hosts)
    }

    @Test fun `a live channel's dialog never offers it`() = runTest {
        val live = PluginRef(ID, "c1", PluginRef.LIVE, "ch").encode()
        val playing = play(source { """{"url":"https://cdn.other.example/live.m3u8"}""" }, live)
        val req = nextPrompt()
        assertFalse(req.offersAnyVideoHost)
        req.respond(false)
        runCatching { playing.await() }
        assertFalse(record.anyVideoHost)
    }

    @Test fun `the fetch-time dialog never offers it`() = runTest {
        val asking = async(SupervisorJob()) { center.request(ID, NAME, "api.other.example") }
        val req = nextPrompt()
        assertEquals(HostApprovalReason.FETCH, req.reason)
        assertFalse(req.offersAnyVideoHost)
        req.respond(false)
        assertFalse(asking.await())
    }

    @Test fun `rejecting in the three-way dialog is today's rejection`() = runTest {
        val playing = play(source { """{"url":"https://cdn.other.example/v.mp4"}""" })
        nextPrompt().answer(HostApprovalAnswer.REJECT)
        assertEquals("$NAME: El video apunta a cdn.other.example, que el plugin no declaró", runCatching { playing.await() }.exceptionOrNull()?.message)
        assertEquals(listOf("cdn.other.example"), record.rejectedHosts)
        assertFalse(record.anyVideoHost)
    }

    // --- playback time ---

    private val loopbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    }

    @Test fun `mid-playback, choosing it rebuilds a player that follows the CDN's next shards without asking`() = runTest {
        val prompts = PlaybackHostPrompts(approval())
        val outcome = async(SupervisorJob()) { prompts.onRefused(ID, NAME, "p16-sign.tiktokcdn-like.test", offerAnyVideoHost = true) }
        val req = nextPrompt()
        assertTrue(req.offersAnyVideoHost)
        req.answer(HostApprovalAnswer.ALLOW_ANY_VIDEO_HOST)
        assertEquals(PlaybackHostOutcome.Retry, outcome.await())
        assertTrue(record.anyVideoHost)
        // What PlayerViewModel republishes: the plugin's video hosts, read afresh.
        val hosts = (registry.accessFor(ID) as PluginAccess.Ready).videoHosts
        assertTrue(hosts.anyPublicVideoHost)
        val client = PluginStreamHttp.client(OkHttpClient(), hosts, allowInsecureLocalhost = true, delegateDns = loopbackDns, askAboutFor = ID)
        server.enqueue(MockResponse().setBody("a"))
        server.enqueue(MockResponse().setBody("b"))
        client.newCall(Request.Builder().url("http://p16-sign.tiktokcdn-like.test:${server.port}/1.ts").build()).execute().close()
        client.newCall(Request.Builder().url("http://p19-sign.tiktokcdn-like.test:${server.port}/2.ts").build()).execute().close()
        assertEquals(2, server.requestCount)
        assertEquals(1, center.shownCount)
    }

    @Test fun `mid-playback on a live channel the permission is not offered`() = runTest {
        val prompts = PlaybackHostPrompts(approval())
        val outcome = async(SupervisorJob()) { prompts.onRefused(ID, NAME, "seg.other.example", offerAnyVideoHost = false) }
        val req = nextPrompt()
        assertFalse(req.offersAnyVideoHost)
        req.respond(true)
        assertEquals(PlaybackHostOutcome.Retry, outcome.await())
        assertNull(center.pending.value)
    }

    private companion object {
        const val ID = "demo"
        const val NAME = "Demo"
    }
}
