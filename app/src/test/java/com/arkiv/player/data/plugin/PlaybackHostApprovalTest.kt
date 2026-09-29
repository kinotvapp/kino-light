package com.arkiv.player.data.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

/**
 * Hosts the PLAYER reaches while a plugin stream plays (an HLS playlist's segments or keys on
 * another CDN, a redirect hop) that the plugin never declared: the gate tells a promptable miss
 * apart ([UndeclaredPlaybackHostException]) so the player can ask with the same dialog, rules and
 * per-plugin memory as for `kino.fetch` and a returned Stream ([PlaybackHostPrompts]).
 */
class PlaybackHostApprovalTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    private lateinit var registry: PluginRegistry
    private lateinit var center: HostApprovalCenter

    @Before fun setUp() {
        server.start()
        val store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        registry = PluginRegistry(store)
        center = HostApprovalCenter(clock = { 0L })
        install(store, listOf("example.com"))
    }

    @After fun stop() = server.shutdown()

    private fun install(store: PluginStore, hosts: List<String>, record: InstalledRecord.() -> InstalledRecord = { this }) {
        val manifest = JSONObject().put("id", ID).put("name", NAME).put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(hosts))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).toString()
        val script = "export async function search(){}".toByteArray()
        val staging = store.newStaging(ID)
        store.writeFiles(staging, manifest, "plugin.js", script, null, InstalledRecord("o/$ID", "1.0.0", sha256Hex(script), hosts, 1L).record())
        store.commit(staging, ID)
        registry.reload()
    }

    private fun reinstall(hosts: List<String>, record: InstalledRecord.() -> InstalledRecord = { this }) {
        val store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        install(store, hosts, record)
        registry = PluginRegistry(store).also { it.reload() }
    }

    private val record get() = registry.find(ID)!!.record

    private val loopbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    }

    /** The player's client, as `StreamExoPlayer` builds it for a plugin VOD stream: misses are askable for [ID]. */
    private fun playerClient(hosts: EffectiveHosts, askable: String? = ID) =
        PluginStreamHttp.client(OkHttpClient(), hosts, allowInsecureLocalhost = true, delegateDns = loopbackDns, askAboutFor = askable)

    private fun get(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute().close()

    // --- the gate tells an askable miss apart ---

    @Test fun `an undeclared public https host is refused as an askable miss naming plugin and host`() {
        val e = assertThrows(UndeclaredPlaybackHostException::class.java) { get(playerClient(EffectiveHosts(listOf("example.com"))), "https://seg.other.example/1.ts") }
        assertEquals(ID, e.pluginId)
        assertEquals("seg.other.example", e.host)
        // Still a host_not_allowed to anything that only knew that one.
        assertEquals("host no permitido: seg.other.example", e.message)
        assertTrue(e is HostNotAllowedException)
    }

    @Test fun `a redirect hop to an undeclared host is an askable miss too`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://cdn2.other.example/v.m3u8"))
        val e = assertThrows(UndeclaredPlaybackHostException::class.java) {
            get(playerClient(EffectiveHosts(listOf("localhost"))), "http://localhost:${server.port}/master.m3u8")
        }
        assertEquals("cdn2.other.example", e.host)
    }

    // Local addresses, IP literals, plain http and the live "any" carve-out are hard refusals, as for
    // kino.fetch (PluginHostGate.isPromptableMiss): the player keeps failing exactly as today.
    @Test fun `every other refusal stays what it was`() {
        val hosts = EffectiveHosts(listOf("example.com"))
        for (url in listOf("https://192.168.1.4/1.ts", "https://203.0.113.9/1.ts", "https://nas.local/1.ts", "http://seg.other.example/1.ts", "https://single/1.ts")) {
            val e = runCatching { get(playerClient(hosts), url) }.exceptionOrNull()
            assertTrue(url, e is PluginFetchException)
            assertFalse(url, e is UndeclaredPlaybackHostException)
        }
        // http on a DECLARED host is its own refusal ("solo se permite https"), never askable.
        val http = runCatching { get(playerClient(hosts), "http://example.com/1.ts") }.exceptionOrNull()
        assertFalse(http is UndeclaredPlaybackHostException)
        // A live channel under liveStreamHosts "any": no prompts on that path (a subtitle kept strict included).
        val any = hosts.copy(anyPublicLiveHost = true)
        val side = "https://subs.other.example/es.vtt"
        val strictSide = runCatching {
            PluginStreamHttp.client(OkHttpClient(), any, delegateDns = loopbackDns, strictOrigins = listOf(side), askAboutFor = ID)
                .newCall(Request.Builder().url(side).build()).execute().close()
        }.exceptionOrNull()
        assertTrue(strictSide is HostNotAllowedException)
        assertFalse(strictSide is UndeclaredPlaybackHostException)
    }

    // A download (PluginDownloadStrategy's client, built without askAboutFor) and the license client
    // never ask: nobody to ask, and a license server gets the plugin's licenseHeaders.
    @Test fun `a client that was not told which plugin to ask for never classifies`() {
        val e = runCatching { get(playerClient(EffectiveHosts(listOf("example.com")), askable = null), "https://seg.other.example/1.ts") }.exceptionOrNull()
        assertTrue(e is HostNotAllowedException)
        assertFalse(e is UndeclaredPlaybackHostException)
    }

    // --- asking the person (PlaybackHostPrompts) ---

    private fun prompts() = PlaybackHostPrompts(StreamHostApproval(center, registry, log = {}))

    private fun TestScope.refused(p: PlaybackHostPrompts, host: String): Deferred<PlaybackHostOutcome> =
        async(SupervisorJob()) { p.onRefused(ID, NAME, host) }

    private suspend fun nextPrompt(): HostApprovalRequest {
        var req = center.pending.value
        while (req == null) { yield(); req = center.pending.value }
        return req
    }

    @Test fun `approving persists the host and says to rebuild the player`() = runTest {
        val outcome = refused(prompts(), "seg.other.example")
        val req = nextPrompt()
        assertEquals(NAME to "seg.other.example", req.pluginName to req.host)
        assertEquals(HostApprovalReason.VIDEO, req.reason)
        req.respond(true)
        assertEquals(PlaybackHostOutcome.Retry, outcome.await())
        assertEquals(listOf("example.com", "seg.other.example"), record.hosts)
        // What the player's gate is rebuilt with (PlayerViewModel re-reads the plugin's access) now
        // lets that host through: the request gets past the gate and only fails at DNS, stubbed here.
        val after = (registry.accessFor(ID) as PluginAccess.Ready).hosts
        val noNetwork = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException("past the gate")
        }
        val e = runCatching { get(PluginStreamHttp.client(OkHttpClient(), after, delegateDns = noNetwork, askAboutFor = ID), "https://seg.other.example/1.ts") }.exceptionOrNull()
        assertEquals("past the gate", e?.message)
    }

    @Test fun `rejecting persists the no and ends in a clear error naming the host`() = runTest {
        val outcome = refused(prompts(), "seg.other.example")
        nextPrompt().respond(false)
        assertEquals(PlaybackHostOutcome.Fail("$NAME: el video usa otro servidor (seg.other.example) que no permitiste"), outcome.await())
        assertEquals(listOf("seg.other.example"), record.rejectedHosts)
        assertEquals(listOf("example.com"), record.hosts)
    }

    @Test fun `a host rejected before fails with the same error and no dialog`() = runTest {
        reinstall(listOf("example.com")) { copy(rejectedHosts = listOf("seg.other.example")) }
        val outcome = refused(prompts(), "seg.other.example").await()
        assertEquals(PlaybackHostOutcome.Fail("$NAME: el video usa otro servidor (seg.other.example) que no permitiste"), outcome)
        assertNull(center.pending.value)
        assertFalse(center.wasAsking(ID, since = 0L))
    }

    @Test fun `with the 20-host cap full it fails clearly without asking`() = runTest {
        reinstall((1..ManifestParser.MAX_HOSTS).map { "h$it.example.com" })
        val outcome = refused(prompts(), "seg.other.example").await()
        assertEquals(PlaybackHostOutcome.Fail("$NAME: el video usa otro servidor (seg.other.example), pero $NAME ya tiene el máximo de 20 servidores aprobados"), outcome)
        assertNull(center.pending.value)
    }

    // One prompt per host per playback attempt: if the same host is refused again after its "yes"
    // (the rebuilt player still can't use it), the person gets the error, not the same question.
    @Test fun `the same host twice in one playback is asked once, then an error`() = runTest {
        val p = prompts()
        val first = refused(p, "seg.other.example")
        nextPrompt().respond(true)
        assertEquals(PlaybackHostOutcome.Retry, first.await())
        val again = refused(p, "seg.other.example").await()
        assertEquals(PlaybackHostOutcome.Fail("$NAME: el video no se pudo cargar desde seg.other.example"), again)
        assertNull(center.pending.value)
        // A new playback attempt (a fresh resolve) may ask again.
        p.newAttempt()
        reinstall(listOf("example.com"))
        val fresh = refused(p, "seg.other.example")
        nextPrompt().respond(false)
        assertTrue(fresh.await() is PlaybackHostOutcome.Fail)
    }

    @Test fun `several undeclared hosts in a row are asked one after the other`() = runTest {
        val p = prompts()
        val a = refused(p, "a.other.example")
        val ra = nextPrompt()
        assertEquals("a.other.example", ra.host)
        ra.respond(true)
        assertEquals(PlaybackHostOutcome.Retry, a.await())
        val b = refused(p, "b.other.example")
        var rb = center.pending.value
        while (rb == null || rb === ra) { yield(); rb = center.pending.value }
        assertEquals("b.other.example", rb.host)
        rb.respond(true)
        assertEquals(PlaybackHostOutcome.Retry, b.await())
        assertEquals(listOf("example.com", "a.other.example", "b.other.example"), record.hosts)
    }

    @Test fun `the question waits past the fetch window`() = runTest {
        val outcome = refused(prompts(), "seg.other.example")
        val req = nextPrompt()
        kotlinx.coroutines.delay(HostApprovalCenter.TIMEOUT_MS * 5)
        assertTrue(outcome.isActive)
        req.respond(true)
        assertEquals(PlaybackHostOutcome.Retry, outcome.await())
    }

    @Test fun `leaving the player while it asks leaves nothing half done`() = runTest {
        val outcome = refused(prompts(), "seg.other.example")
        val req = nextPrompt()
        outcome.cancel()
        assertTrue(runCatching { outcome.await() }.exceptionOrNull() is CancellationException)
        assertNull(center.pending.value)
        req.respond(true)
        assertEquals(listOf("example.com"), record.hosts)
        assertEquals(emptyList<String>(), record.rejectedHosts)
    }

    private companion object {
        const val ID = "demo"
        const val NAME = "Demo"
    }
}
