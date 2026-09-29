package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A plugin's `resolve` RETURNS a Stream whose URL (or a subtitle/audio/license URL) is on a host it
 * never declared: while the person waits in the player, Kino asks them with the same dialog, the
 * same rules and the same per-plugin memory as a `kino.fetch` miss. Real [PluginRegistry] (on a temp
 * dir), real [HostApprovalCenter] answered through its `pending` request as the dialog would.
 */
class StreamHostApprovalTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var registry: PluginRegistry
    private lateinit var center: HostApprovalCenter
    private val movie = PluginRef(ID, "m1", PluginRef.MOVIE, "R1").encode()

    @Before fun setUp() {
        val store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        registry = PluginRegistry(store)
        center = HostApprovalCenter(clock = { 0L })
        install(store, listOf("example.com"))
    }

    private fun install(store: PluginStore, hosts: List<String>, record: InstalledRecord.() -> InstalledRecord = { this }) {
        val manifest = JSONObject().put("id", ID).put("name", NAME).put("version", "1.0.0").put("apiVersion", 2)
            .put("entry", "plugin.js").put("hosts", JSONArray(hosts))
            .put("capabilities", JSONArray(listOf("search", "resolve", "download", "drm"))).toString()
        val script = "export async function search(){}".toByteArray()
        val staging = store.newStaging(ID)
        store.writeFiles(staging, manifest, "plugin.js", script, null,
            InstalledRecord("o/$ID", "1.0.0", sha256Hex(script), hosts, 1L).record())
        store.commit(staging, ID)
        registry.reload()
    }

    private fun reinstall(hosts: List<String>, record: InstalledRecord.() -> InstalledRecord = { this }) {
        val store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        install(store, hosts, record)
        registry = PluginRegistry(store).also { it.reload() }
    }

    private val record get() = registry.find(ID)!!.record

    private fun approval(http: PluginHttp? = null) = StreamHostApproval(center, registry, openRuntimeHttp = { if (it == ID) http else null }, log = {})

    /** What `AppGraph` builds: hosts read from the registry after the call, the stream approval wired in. */
    private fun source(resolve: String, http: PluginHttp? = null): PluginContentSource {
        val p = registry.find(ID)!!
        return PluginContentSource(
            p, { _, _, _, _ -> resolve }, p.hosts, log = {},
            currentHosts = { registry.find(ID)!!.hosts },
            streamHostApproval = approval(http),
        )
    }

    /** The player's own resolve: a person is on screen ([InteractivePluginCall]). */
    private fun TestScope.play(src: PluginContentSource): Deferred<GatewayPlayable> =
        // Its own job: a refused stream must reach the test through await(), not cancel the test itself.
        async(SupervisorJob()) { withContext(InteractivePluginCall) { src.resolve(movie) } }

    private suspend fun nextPrompt(): HostApprovalRequest {
        var req = center.pending.value
        while (req == null) { yield(); req = center.pending.value }
        return req
    }

    /** Resolves [src] with nobody asked: the error the person saw before this change. */
    private suspend fun todaysError(src: PluginContentSource): String? = runCatching { src.resolve(movie) }.exceptionOrNull()?.message

    // --- the stream's own URL ---

    @Test fun `an undeclared stream host is asked about, and approving plays it and keeps the host`() = runTest {
        val src = source("""{"url":"https://cdn.other.example/v.mp4"}""")
        val playing = play(src)
        val req = nextPrompt()
        assertEquals(NAME, req.pluginName)
        assertEquals("cdn.other.example", req.host)
        assertEquals(HostApprovalReason.VIDEO, req.reason)
        req.respond(true)
        assertEquals("https://cdn.other.example/v.mp4", playing.await().url)
        assertEquals(listOf("example.com", "cdn.other.example"), record.hosts)
        assertNull(center.pending.value)
    }

    // PlayerViewModel re-reads the plugin's access after resolve and hands those hosts to the
    // player's own gate (PluginStreamHttp -> PluginStreamGate -> PluginHostGate.check): the video's
    // requests to the approved host must pass it, and did not before the approval.
    @Test fun `after approval the player's gate lets the video's requests through`() = runTest {
        val video = "https://cdn.other.example/v.mp4".toHttpUrl()
        val before = (registry.accessFor(ID) as PluginAccess.Ready).hosts
        assertTrue(runCatching { PluginHostGate.check(video, before) }.exceptionOrNull() is HostNotAllowedException)
        val playing = play(source("""{"url":"https://cdn.other.example/v.mp4"}"""))
        nextPrompt().respond(true)
        playing.await()
        val after = (registry.accessFor(ID) as PluginAccess.Ready).hosts
        PluginHostGate.check(video, after)
        PluginHostGate.check("https://cdn.other.example/seg/1.ts".toHttpUrl(), after)
        // Only that host: its neighbours stay refused.
        assertTrue(runCatching { PluginHostGate.check("https://x.other.example/v.mp4".toHttpUrl(), after) }.exceptionOrNull() is HostNotAllowedException)
    }

    @Test fun `rejecting fails with today's error and remembers the no`() = runTest {
        val answer = """{"url":"https://cdn.other.example/v.mp4"}"""
        val expected = todaysError(PluginContentSource(registry.find(ID)!!, { _, _, _, _ -> answer }, log = {}))
        val playing = play(source(answer))
        nextPrompt().respond(false)
        val e = runCatching { playing.await() }.exceptionOrNull()
        assertTrue(e is GatewayException)
        assertEquals(expected, e!!.message)
        assertEquals("$NAME: El video apunta a cdn.other.example, que el plugin no declaró", e.message)
        assertEquals(listOf("cdn.other.example"), record.rejectedHosts)
        assertEquals(listOf("example.com"), record.hosts)
    }

    @Test fun `a host rejected before is never asked about again`() = runTest {
        reinstall(listOf("example.com")) { copy(rejectedHosts = listOf("cdn.other.example")) }
        val playing = play(source("""{"url":"https://cdn.other.example/v.mp4"}"""))
        val e = runCatching { playing.await() }.exceptionOrNull()
        assertEquals("$NAME: El video apunta a cdn.other.example, que el plugin no declaró", e?.message)
        assertNull("no dialog for a host already refused", center.pending.value)
        assertFalse(center.wasAsking(ID, since = 0L))
    }

    // Local addresses, IP literals and plain http are hard refusals for kino.fetch too
    // (PluginHostGate.isPromptableMiss): the same rules, the same error, never a question.
    @Test fun `an ineligible stream URL is never asked about and fails exactly as today`() = runTest {
        val urls = listOf(
            "https://192.168.1.20/v.mp4",
            "https://203.0.113.9/v.mp4",
            "https://[2001:db8::1]/v.mp4",
            "https://nas.local/v.mp4",
            "https://localhost/v.mp4",
            "http://cdn.other.example/v.mp4",
            "https://single/v.mp4",
            "ftp://cdn.other.example/v.mp4",
            "no es una url",
        )
        for (url in urls) {
            val answer = JSONObject().put("url", url).toString()
            val expected = todaysError(PluginContentSource(registry.find(ID)!!, { _, _, _, _ -> answer }, log = {}))
            val e = runCatching { play(source(answer)).await() }.exceptionOrNull()
            assertEquals(url, expected, e?.message)
            assertNull(url, center.pending.value)
        }
        assertFalse(center.wasAsking(ID, since = 0L))
        assertEquals(listOf("example.com"), record.hosts)
        assertEquals(emptyList<String>(), record.rejectedHosts)
    }

    // A stream that would be refused anyway (here: an invalid mime) is not worth a question whose
    // "yes" still ends in an error: it fails at once, with the error it always had.
    @Test fun `a stream that is broken for another reason is not asked about`() = runTest {
        val answer = """{"url":"https://cdn.other.example/v.mp4","mime":"not a mime"}"""
        val expected = todaysError(PluginContentSource(registry.find(ID)!!, { _, _, _, _ -> answer }, log = {}))
        val e = runCatching { play(source(answer)).await() }.exceptionOrNull()
        assertEquals(expected, e?.message)
        assertNull(center.pending.value)
        assertFalse(center.wasAsking(ID, since = 0L))
    }

    @Test fun `with 20 hosts already approved the stream fails with a clear message and no question`() = runTest {
        val full = (1..ManifestParser.MAX_HOSTS).map { "h$it.example.com" }
        reinstall(full)
        val e = runCatching { play(source("""{"url":"https://cdn.other.example/v.mp4"}""")).await() }.exceptionOrNull()
        assertTrue(e is GatewayException)
        assertEquals("$NAME: El video está en cdn.other.example, pero $NAME ya tiene el máximo de 20 servidores aprobados", e!!.message)
        assertNull(center.pending.value)
        assertEquals(full, record.hosts)
    }

    // A fetch-time approval of ANOTHER host can fill the last slot while this dialog is up.
    @Test fun `if the cap fills while the person decides, approving still fails clearly and adds nothing`() = runTest {
        val almost = (1 until ManifestParser.MAX_HOSTS).map { "h$it.example.com" }
        reinstall(almost)
        val playing = play(source("""{"url":"https://cdn.other.example/v.mp4"}"""))
        val req = nextPrompt()
        assertTrue(registry.addApprovedHost(ID, "last.example.com"))
        req.respond(true)
        val e = runCatching { playing.await() }.exceptionOrNull()
        assertEquals("$NAME: El video está en cdn.other.example, pero $NAME ya tiene el máximo de 20 servidores aprobados", e?.message)
        assertFalse("cdn.other.example" in record.hosts)
        assertEquals(ManifestParser.MAX_HOSTS, record.hosts.size)
    }

    // --- side files ---

    @Test fun `a rejected subtitle host only drops that subtitle, and the video still plays`() = runTest {
        val answer = """{"url":"https://example.com/v.mp4","subtitles":[
            {"lang":"es","url":"https://subs.other.example/es.vtt"},{"lang":"en","url":"https://example.com/en.vtt"}]}"""
        val playing = play(source(answer))
        val req = nextPrompt()
        assertEquals("subs.other.example", req.host)
        assertEquals(HostApprovalReason.SUBTITLE, req.reason)
        req.respond(false)
        val play = playing.await()
        assertEquals("https://example.com/v.mp4", play.url)
        assertEquals(listOf("en"), play.subtitles.map { it.lang })
        assertEquals(listOf("subs.other.example"), record.rejectedHosts)
    }

    @Test fun `an approved audio host keeps its track`() = runTest {
        val answer = """{"url":"https://example.com/v.mp4","audioTracks":[{"lang":"es","url":"https://dub.other.example/es.aac"}]}"""
        val playing = play(source(answer))
        val req = nextPrompt()
        assertEquals(HostApprovalReason.AUDIO, req.reason)
        req.respond(true)
        assertEquals(listOf("https://dub.other.example/es.aac"), playing.await().audioTracks.map { it.url })
        assertTrue("dub.other.example" in record.hosts)
    }

    @Test fun `stream and subtitle on two undeclared hosts are asked once each, the video first`() = runTest {
        val answer = """{"url":"https://cdn.other.example/v.mp4","subtitles":[
            {"lang":"es","url":"https://subs.other.example/es.vtt"},{"lang":"en","url":"https://subs.other.example/en.vtt"},
            {"lang":"fr","url":"https://cdn.other.example/fr.vtt"}]}"""
        val playing = play(source(answer))
        val first = nextPrompt()
        assertEquals("cdn.other.example" to HostApprovalReason.VIDEO, first.host to first.reason)
        first.respond(true)
        var second = center.pending.value
        while (second == null || second === first) { yield(); second = center.pending.value }
        assertEquals("subs.other.example" to HostApprovalReason.SUBTITLE, second.host to second.reason)
        second.respond(true)
        val play = playing.await()
        assertEquals(listOf("es", "en", "fr"), play.subtitles.map { it.lang })
        assertEquals(listOf("example.com", "cdn.other.example", "subs.other.example"), record.hosts)
        assertNull(center.pending.value)
    }

    @Test fun `rejecting the video's host fails at once, without asking about its subtitles`() = runTest {
        val answer = """{"url":"https://cdn.other.example/v.mp4","subtitles":[{"lang":"es","url":"https://subs.other.example/es.vtt"}]}"""
        val playing = play(source(answer))
        nextPrompt().respond(false)
        val e = runCatching { playing.await() }.exceptionOrNull()
        assertEquals("$NAME: El video apunta a cdn.other.example, que el plugin no declaró", e?.message)
        assertNull(center.pending.value)
        assertEquals(listOf("cdn.other.example"), record.rejectedHosts)
    }

    @Test fun `an undeclared license host is asked about like the video's own`() = runTest {
        val answer = """{"url":"https://example.com/x.mpd","drm":{"type":"widevine","licenseUrl":"https://lic.other.example/wv"}}"""
        val playing = play(source(answer))
        val req = nextPrompt()
        assertEquals("lic.other.example" to HostApprovalReason.LICENSE, req.host to req.reason)
        req.respond(false)
        assertEquals("$NAME: La licencia del video apunta a lic.other.example, que el plugin no declaró", runCatching { playing.await() }.exceptionOrNull()?.message)
    }

    // --- nobody to ask ---

    // A download resolves in the background (PluginDownloadStrategy, BackgroundPluginCall): no one
    // is looking, so it fails as it always did. Neither can anything else that didn't say a person waits.
    @Test fun `a background or unmarked resolve never asks`() = runTest {
        val answer = """{"url":"https://cdn.other.example/v.mp4"}"""
        val expected = "$NAME: El video apunta a cdn.other.example, que el plugin no declaró"
        assertEquals(expected, runCatching { withContext(BackgroundPluginCall) { source(answer).resolve(movie) } }.exceptionOrNull()?.message)
        assertEquals(expected, runCatching { source(answer).resolve(movie) }.exceptionOrNull()?.message)
        // Even if a background job somehow ran inside an interactive context, background wins.
        assertEquals(expected, runCatching { withContext(InteractivePluginCall + BackgroundPluginCall) { source(answer).resolve(movie) } }.exceptionOrNull()?.message)
        assertNull(center.pending.value)
        assertFalse(center.wasAsking(ID, since = 0L))
        assertEquals(emptyList<String>(), record.rejectedHosts)
    }

    // --- the wait itself ---

    @Test fun `the stream prompt is not dismissed by the fetch-time window`() = runTest {
        val playing = play(source("""{"url":"https://cdn.other.example/v.mp4"}"""))
        val req = nextPrompt()
        kotlinx.coroutines.delay(HostApprovalCenter.TIMEOUT_MS * 5)
        assertTrue(playing.isActive)
        assertEquals(req, center.pending.value)
        req.respond(true)
        assertEquals("https://cdn.other.example/v.mp4", playing.await().url)
    }

    // Leaving the player cancels the ViewModel's resolve: the dialog goes away with it, and neither
    // an approval nor a rejection is written.
    @Test fun `cancelling while the person decides leaves nothing half done`() = runTest {
        val playing = play(source("""{"url":"https://cdn.other.example/v.mp4"}"""))
        val req = nextPrompt()
        playing.cancel()
        assertTrue(runCatching { playing.await() }.exceptionOrNull() is CancellationException)
        assertNull(center.pending.value)
        // A tap that lands on the dialog as it goes changes nothing either.
        req.respond(true)
        assertEquals(listOf("example.com"), record.hosts)
        assertEquals(emptyList<String>(), record.rejectedHosts)
        // And the next question is not stuck behind it.
        val next = async { center.request("other", "Otro", "b.example") }
        nextPrompt().respond(false)
        assertEquals(false, next.await())
    }

    // --- the open runtime learns the answer too ---

    // Otherwise the plugin's next kino.fetch to the same host, in the same open runtime, would ask
    // the person a second time about a host they just answered.
    @Test fun `the open runtime's fetch gate learns an approval and a rejection made for a stream`() = runTest {
        val rejectedFetchAsks = mutableListOf<String>()
        val http = PluginHttp(
            OkHttpClient(), ID, registry.find(ID)!!.hosts, "1.0.0",
            reactiveApproval = PluginHttp.ReactiveApproval(NAME, HostApprovalRequester { _, _, host -> rejectedFetchAsks += host; null }, {}, {}, emptySet()),
        )
        val approved = play(source("""{"url":"https://cdn.other.example/v.mp4"}""", http))
        nextPrompt().respond(true)
        approved.await()
        assertTrue("cdn.other.example" in http.hosts.declared)

        val refused = play(source("""{"url":"https://bad.other.example/v.mp4"}""", http))
        nextPrompt().respond(false)
        runCatching { refused.await() }
        val e = runCatching { http.fetch(PluginHttp.Request("https://bad.other.example/x")) }.exceptionOrNull()
        assertTrue(e is HostNotAllowedException)
        assertEquals("the fetch gate never asked again", emptyList<String>(), rejectedFetchAsks)
    }

    private companion object {
        const val ID = "demo"
        const val NAME = "Demo"
    }
}
