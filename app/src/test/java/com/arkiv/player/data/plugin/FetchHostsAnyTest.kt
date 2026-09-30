package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
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
import java.io.FileNotFoundException
import java.net.InetAddress

/**
 * `fetchHosts: "any"`: a Nuvio-converted plugin's `kino.fetch` may reach any PUBLIC host, approved
 * once on the consent sheet -- never the home network, and never for a hand-written plugin.
 */
class FetchHostsAnyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    // ---- manifest ----

    private fun manifest(api: Int, fetchHosts: Any?) = JSONObject()
        .put("id", "demo").put("name", "Demo").put("version", "1.0.0").put("apiVersion", api)
        .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
        .put("capabilities", JSONArray(listOf("search", "resolve")))
        .apply { if (fetchHosts != null) put("fetchHosts", fetchHosts) }
        .toString()

    @Test fun `fetchHosts any needs apiVersion 4 and only the value any`() {
        assertTrue((ManifestParser.parse(manifest(4, "any")) as ManifestResult.Valid).manifest.fetchHostsAny)
        for (api in 1..3) assertFalse((ManifestParser.parse(manifest(api, "any")) as ManifestResult.Valid).manifest.fetchHostsAny)
        assertFalse((ManifestParser.parse(manifest(4, null)) as ManifestResult.Valid).manifest.fetchHostsAny)
        val bad = ManifestParser.parse(manifest(4, "all")) as ManifestResult.Invalid
        assertEquals("fetchHosts", bad.field)
        assertEquals("El campo \"fetchHosts\" solo admite \"any\"", bad.message)
        assertEquals("fetchHosts", (ManifestParser.parse(manifest(4, true)) as ManifestResult.Invalid).field)
    }

    @Test fun `the Nuvio converter asks for it`() {
        val entry = NuvioScraperEntry("fakesrc", "FakeSrc", "providers/fakesrc.js", true, emptyList(), listOf("movie"), null, emptyList())
        val c = NuvioPluginConverter.convert(entry, scraperJs, repoSlug = "owner/nuvio-repo")
        assertTrue((ManifestParser.parse(c.manifestJson) as ManifestResult.Valid).manifest.fetchHostsAny)
    }

    @Test fun `the record keeps it, and only a Nuvio-converted record honours it`() {
        val nuvio = InstalledRecord("o/r", "1.0.0", "x", listOf("a.com"), 0L, fetchHostsAny = true, pendingFetchHostsAny = true, nuvioRepo = "o/r", nuvioScraperId = "s")
        assertEquals(nuvio, InstalledRecord.fromJson(nuvio.toJson()))
        assertTrue(nuvio.fetchFromAnyHost)
        assertFalse(nuvio.copy(nuvioRepo = null, nuvioScraperId = null).fetchFromAnyHost)
        assertFalse(nuvio.copy(fetchHostsAny = false).fetchFromAnyHost)
        // A record written before the field existed: not granted.
        val old = JSONObject(nuvio.toJson()).apply { remove("fetchHostsAny"); remove("pendingFetchHostsAny") }.toString()
        assertFalse(InstalledRecord.fromJson(old)!!.fetchHostsAny)
    }

    // ---- install / update ----

    private val nuvioManifest = """
        { "name": "nuvio-providers", "scrapers": [
          { "id": "fakesrc", "name": "FakeSrc", "filename": "providers/fakesrc.js", "enabled": true, "supportedTypes": ["movie"] }
        ] }
    """.trimIndent()
    private val scraperJs = """
        function getStreams() { return [{ name: "FakeSrc", title: "t", url: "https://fakesrc.example/v.mp4", quality: "1080p" }]; }
        module.exports = { getStreams: getStreams };
    """.trimIndent()

    private fun fetcher(files: Map<String, String>) = PluginFetcher { url, _ -> files[url]?.toByteArray() ?: throw FileNotFoundException(url) }

    private val store by lazy { PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data")) }
    private val handFiles = HashMap<String, String>()
    private val installer by lazy {
        PluginInstaller(store, fetcher(handFiles), probe = { script, _ ->
            val runtime = PluginRuntime.open("probe", script, ProbePluginHost, PluginEnv(appVersion = "1.0"))
            try { runtime.exports } finally { runtime.close() }
        })
    }
    private val nuvio by lazy {
        NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to nuvioManifest,
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js" to scraperJs,
        )))
    }

    private val redLine = "Puede conectarse a cualquier servidor de internet"

    @Test fun `a Nuvio install shows the red line and approving stores it`() = runBlocking {
        val preview = nuvio.previewScraper("owner/nuvio-repo", "fakesrc")
        assertTrue(preview.newFetchHostsAny)
        val line = PluginConsent.extraLines(preview).single { it.text == redLine }
        assertTrue(line.danger)
        assertFalse(line.isNew) // a first install marks nothing "nuevo"
        val record = nuvio.install(preview)
        assertTrue(record.fetchHostsAny)
        assertTrue(record.fetchFromAnyHost)
        assertTrue(store.get(preview.manifest.id)!!.record.fetchFromAnyHost)
    }

    @Test fun `updating a Nuvio plugin converted before the permission asks for it again, then keeps it`() = runBlocking {
        val current = nuvio.previewScraper("owner/nuvio-repo", "fakesrc")
        val oldJson = JSONObject(current.manifestJson).apply { remove("fetchHosts") }.toString()
        val oldManifest = (ManifestParser.parse(oldJson) as ManifestResult.Valid).manifest
        val oldScript = "export async function search(q) { return []; }\nexport async function episodes(r) { return []; }\nexport async function resolve(r) { return { url: 'https://fakesrc.example/v.mp4' }; }"
        val origin = NuvioOrigin(repo = "owner/nuvio-repo", scraperId = "fakesrc", script = oldScript.toByteArray())
        installer.commit(installer.diffAgainstInstalled(PluginAddress.parse("owner/nuvio-repo")!!, oldManifest, oldJson, origin), oldScript.toByteArray(), icon = null)
        val id = oldManifest.id
        assertFalse(store.get(id)!!.record.fetchFromAnyHost)

        val outcome = nuvio.checkUpdate(id)
        assertTrue(outcome.toString(), outcome is UpdateOutcome.NeedsApproval)
        val preview = (outcome as UpdateOutcome.NeedsApproval).preview
        assertTrue(preview.newFetchHostsAny)
        assertFalse(preview.newStreamHostsAny)
        assertTrue(preview.newHosts.isEmpty())
        assertTrue(store.get(id)!!.record.pendingFetchHostsAny)
        assertTrue(PluginConsent.extraLines(preview).single { it.text == redLine }.isNew)

        nuvio.install(preview)
        val record = store.get(id)!!.record
        assertTrue(record.fetchFromAnyHost)
        assertFalse(record.pendingFetchHostsAny)
        // Already approved: the next re-conversion asks nothing about it.
        assertFalse(nuvio.previewScraper("owner/nuvio-repo", "fakesrc").newFetchHostsAny)
        assertEquals(UpdateOutcome.UpToDate, nuvio.checkUpdate(id))
    }

    @Test fun `a hand-written plugin that declares it installs without it`() = runBlocking {
        val base = "https://raw.githubusercontent.com/o/r/HEAD/"
        handFiles[base + "kino-plugin.json"] = manifest(4, "any")
        handFiles[base + "plugin.js"] = "export async function search(){}\nexport async function resolve(){}"
        val preview = installer.preview("o/r")
        assertTrue(preview.manifest.fetchHostsAny)
        assertFalse(preview.newFetchHostsAny)
        assertTrue(PluginConsent.extraLines(preview).none { it.text == redLine })
        val record = installer.install(preview)
        assertFalse(record.fetchHostsAny)
        assertFalse(record.fetchFromAnyHost)
    }

    // ---- the gate ----

    private val loopback = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }
    private val anyFetch = EffectiveHosts(listOf("declared.example.com"), anyPublicFetchHost = true)

    /** Fails the test if anything asks the person. */
    private val neverAsk = PluginHttp.ReactiveApproval("P", HostApprovalRequester { _, _, host -> throw AssertionError("asked about $host") }, {}, {}, emptySet())

    private fun fetchError(h: PluginHttp, url: String): PluginFetchException =
        assertThrows(PluginFetchException::class.java) { runBlocking { h.fetch(PluginHttp.Request(url)) } }

    @Test fun `with the permission any public host is reached without asking, redirects included`() = runBlocking {
        // Every name resolves to this MockWebServer (loopback is only allowed by the test-only flag).
        val h = PluginHttp(OkHttpClient(), "test", anyFetch, "1.0", allowInsecureLocalhost = true, delegateDns = loopback, reactiveApproval = neverAsk)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://other-cdn.example.net:${server.port}/b"))
        server.enqueue(MockResponse().setBody("hola"))
        val r = h.fetch(PluginHttp.Request("http://random-mirror.example.org:${server.port}/a"))
        assertEquals("hola", r.text)
        assertEquals(2, server.requestCount)
    }

    @Test fun `with the permission private and local addresses stay refused, and nobody is asked`() {
        val h = PluginHttp(OkHttpClient(), "test", anyFetch, "1.0", allowInsecureLocalhost = true, delegateDns = loopback, reactiveApproval = neverAsk)
        listOf(
            "http://localhost:${server.port}/x", "http://127.0.0.1:${server.port}/x", "http://10.0.0.2/x", "http://172.16.5.1/x",
            "http://192.168.1.10/x", "http://169.254.169.254/latest/meta-data", "http://[::1]/x", "http://[fd00::1]/x", "http://[fe80::1]/x",
            "http://router.local/x", "http://nas.lan/x", "https://2130706433/x",
        ).forEach { url -> assertEquals(url, "host_not_allowed", fetchError(h, url).code) }
        assertEquals(0, server.requestCount)
    }

    @Test fun `with the permission a public name that resolves into the LAN is refused`() {
        val lan = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("192.168.1.10")) }
        val h = PluginHttp(OkHttpClient(), "test", anyFetch, "1.0", delegateDns = lan, reactiveApproval = neverAsk)
        assertEquals("host_not_allowed", fetchError(h, "https://rebind.example.com/x").code)
    }

    @Test fun `with the permission a redirect to a private address is refused before it goes out`() {
        val h = PluginHttp(OkHttpClient(), "test", anyFetch, "1.0", allowInsecureLocalhost = true, delegateDns = loopback, reactiveApproval = neverAsk)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://192.168.1.1/admin"))
        server.enqueue(MockResponse().setBody("must not be served"))
        assertEquals("host_not_allowed", fetchError(h, "http://public-mirror.example.org:${server.port}/a").code)
        assertEquals(1, server.requestCount)
    }

    @Test fun `without the permission nothing changes`() {
        val strict = EffectiveHosts(listOf("declared.example.com"))
        assertThrows(HostNotAllowedException::class.java) { PluginHostGate.check("https://random-mirror.example.org/".toHttpUrl(), strict) }
        assertTrue(PluginHostGate.isPromptableMiss("https://random-mirror.example.org/".toHttpUrl(), strict))
        // With it there is no gap left to ask about.
        assertFalse(PluginHostGate.isPromptableMiss("https://random-mirror.example.org/".toHttpUrl(), anyFetch))
        val h = PluginHttp(OkHttpClient(), "test", strict, "1.0", allowInsecureLocalhost = true, delegateDns = loopback)
        assertEquals("host_not_allowed", fetchError(h, "http://random-mirror.example.org:${server.port}/a").code)
        assertEquals(0, server.requestCount)
    }

    @Test fun `the permission never leaks into the player's hosts`() {
        // The player, a DRM license and a download build their hosts from the registry; strict keeps
        // only what kino.fetch already had, and the stream relaxations stay off.
        assertFalse(anyFetch.anyPublicStreamHost)
        assertFalse(anyFetch.strict.anyPublicVideoHost || anyFetch.strict.anyPublicLiveHost)
    }

    @Test fun `with the permission the cookie jar keeps a public host's cookies, never a local one's`() {
        val jar = PluginCookies(tmp.root.resolve("cookies.json"), anyFetch)
        val public = "https://random-mirror.example.org/x".toHttpUrl()
        jar.saveFromResponse(public, listOf(Cookie.parse(public, "sid=1")!!))
        assertEquals(listOf("sid"), jar.loadForRequest(public).map { it.name })
        val strictJar = PluginCookies(tmp.root.resolve("cookies2.json"), EffectiveHosts(listOf("declared.example.com")))
        strictJar.saveFromResponse(public, listOf(Cookie.parse(public, "sid=1")!!))
        assertTrue(strictJar.loadForRequest(public).isEmpty())
    }
}
