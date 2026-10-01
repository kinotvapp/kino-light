package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class NuvioPluginInstallerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: PluginStore
    private lateinit var installer: PluginInstaller
    private lateinit var nuvio: NuvioPluginInstaller

    private val manifestJson = """
        { "name": "nuvio-providers", "scrapers": [
          { "id": "fakesrc", "name": "FakeSrc", "filename": "providers/fakesrc.js", "enabled": true, "supportedTypes": ["movie"] }
        ] }
    """.trimIndent()
    private val scraperJs = """
        function getStreams() { return [{ name: "FakeSrc", title: "t", url: "https://fakesrc.example/v.mp4", quality: "1080p" }]; }
        module.exports = { getStreams: getStreams };
    """.trimIndent()

    private fun fetcher(files: Map<String, String>) = PluginFetcher { url, _ ->
        files[url]?.toByteArray() ?: throw FileNotFoundException(url)
    }

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        val files = mapOf(
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/kino-plugin.json" to "not json", // provokes the fallback path
        )
        installer = PluginInstaller(store, fetcher(files), probe = { script, _ ->
            val runtime = PluginRuntime.open("probe", script, ProbePluginHost, PluginEnv(appVersion = "1.0"))
            try { runtime.exports } finally { runtime.close() }
        })
        nuvio = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to manifestJson,
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js" to scraperJs,
        )))
    }

    @Test fun `previewRepo lists installable scrapers for a Nuvio-format repo`() = runBlocking {
        val preview = nuvio.previewRepo("owner/nuvio-repo")
        assertEquals(listOf("fakesrc"), preview!!.scrapers.map { it.id })
        assertEquals("owner/nuvio-repo", preview.address) // HEAD worked first try: no `@ref` suffix
    }

    /**
     * The full-screen picker (Task: "see the scrapers properly") shows a repo's disabled and
     * Android-disabled scrapers too, dimmed, instead of silently dropping them like the old dialog did:
     * [NuvioPluginInstaller.previewRepo] hands over the manifest's WHOLE list, and it is
     * [NuvioManifestParser.isInstallable] -- checked by the caller -- that decides what can be added.
     */
    @Test fun `previewRepo's scrapers include disabled and android-disabled entries, not just installable ones`() = runBlocking {
        val manifestWithDisabled = """
            { "name": "nuvio-providers", "scrapers": [
              { "id": "fakesrc", "name": "FakeSrc", "filename": "providers/fakesrc.js", "enabled": true, "supportedTypes": ["movie"] },
              { "id": "off", "name": "Off", "filename": "providers/off.js", "enabled": false, "supportedTypes": ["movie"] },
              { "id": "iosonly", "name": "iOS Only", "filename": "providers/iosonly.js", "enabled": true, "supportedTypes": ["movie"], "disabledPlatforms": ["android"] }
            ] }
        """.trimIndent()
        val n = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to manifestWithDisabled,
        )))
        val preview = n.previewRepo("owner/nuvio-repo")
        assertEquals(listOf("fakesrc", "off", "iosonly"), preview!!.scrapers.map { it.id })
        assertEquals(listOf("fakesrc"), preview.scrapers.filter(NuvioManifestParser::isInstallable).map { it.id })
    }

    @Test fun `previewRepo returns null for a repo whose manifest isn't Nuvio-shaped`() = runBlocking {
        val notNuvio = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/owner/other/HEAD/manifest.json" to """{"hello":"world"}""",
        )))
        assertNull(notNuvio.previewRepo("owner/other"))
    }

    // ---- default-branch fallback (spec: yoruix/nuvio-providers's default branch is `template`) ----

    /** Placeholder shape a repo's default branch can serve instead of a real Nuvio manifest. */
    private val placeholderManifest = """[{"disabled":true}]"""

    @Test fun `previewRepo falls back to @main when the default branch parses but isn't Nuvio-shaped`() = runBlocking {
        val n = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/yoruix/nuvio-providers/HEAD/manifest.json" to placeholderManifest,
            "https://raw.githubusercontent.com/yoruix/nuvio-providers/main/manifest.json" to manifestJson,
            "https://raw.githubusercontent.com/yoruix/nuvio-providers/main/providers/fakesrc.js" to scraperJs,
        )))
        val preview = n.previewRepo("yoruix/nuvio-providers")
        assertEquals("yoruix/nuvio-providers@main", preview!!.address)
        assertEquals(listOf("fakesrc"), preview.scrapers.map { it.id })
    }

    @Test fun `previewRepo tries @master after @main also fails`() = runBlocking {
        val n = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/o/r/HEAD/manifest.json" to placeholderManifest,
            // no .../main/manifest.json entry at all: 404s, same as @main not existing
            "https://raw.githubusercontent.com/o/r/master/manifest.json" to manifestJson,
        )))
        val preview = n.previewRepo("o/r")
        assertEquals("o/r@master", preview!!.address)
    }

    @Test fun `an explicit @ref is tried once and never falls back`() = runBlocking {
        val n = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/o/r/HEAD/manifest.json" to manifestJson, // a real Nuvio manifest, but never asked for
            "https://raw.githubusercontent.com/o/r/main/manifest.json" to placeholderManifest,
        )))
        assertNull(n.previewRepo("o/r@main")) // must not fall back to HEAD (nor try @master)
    }

    @Test fun `a pasted raw manifest json URL resolves at its branch and installs from there`() = runBlocking {
        val n = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/latinokodi/latinuvio-V2/main/manifest.json" to manifestJson,
            "https://raw.githubusercontent.com/latinokodi/latinuvio-V2/main/providers/fakesrc.js" to scraperJs,
        )))
        val repoPreview = n.previewRepo("https://raw.githubusercontent.com/latinokodi/latinuvio-V2/main/manifest.json")!!
        assertEquals("latinokodi/latinuvio-V2@main", repoPreview.address)
        assertEquals(listOf("fakesrc"), repoPreview.scrapers.map { it.id })
        val record = n.install(n.previewScraper(repoPreview.address, "fakesrc"))
        assertEquals("latinokodi/latinuvio-V2@main", record.nuvioRepo)
    }

    @Test fun `previewRepo tries no fallback at all when the default branch has no manifest json`() = runBlocking {
        val requested = mutableListOf<String>()
        val n = NuvioPluginInstaller(installer, PluginFetcher { url, _ -> requested += url; throw FileNotFoundException(url) })
        assertNull(n.previewRepo("owner/native-plugin-repo")) // looks like a plain native Kino plugin address
        assertEquals(listOf("https://raw.githubusercontent.com/owner/native-plugin-repo/HEAD/manifest.json"), requested)
    }

    @Test fun `previewScraper installs from the address that actually worked, and records it as the origin`() = runBlocking {
        val n = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/yoruix/nuvio-providers/HEAD/manifest.json" to placeholderManifest,
            "https://raw.githubusercontent.com/yoruix/nuvio-providers/main/manifest.json" to manifestJson,
            "https://raw.githubusercontent.com/yoruix/nuvio-providers/main/providers/fakesrc.js" to scraperJs,
        )))
        val repoPreview = n.previewRepo("yoruix/nuvio-providers")!!
        // Mirrors PluginsViewModel.add()/pickNuvioScraper: the picker re-resolves from the address
        // that actually worked, not the person's raw typed text.
        val preview = n.previewScraper(repoPreview.address, "fakesrc")
        val record = n.install(preview)
        assertEquals("yoruix/nuvio-providers@main", record.nuvioRepo)
    }

    @Test fun `previewScraper converts, validates, and installs, and playback resolves`() = runBlocking {
        val preview = nuvio.previewScraper("owner/nuvio-repo", "fakesrc")
        assertEquals(setOf("search", "episodes", "resolve", "download"), preview.manifest.capabilities)
        assertTrue("fakesrc.example" in preview.newHosts)
        val record = nuvio.install(preview)
        assertEquals("owner/nuvio-repo", record.nuvioRepo)
        assertEquals("fakesrc", record.nuvioScraperId)
        assertEquals(1, store.list().size)
        assertTrue(store.list().first().manifest.id.startsWith("nuvio-fakesrc-"))
    }

    @Test fun `two different repos with a same-id scraper install as two independent plugins`() = runBlocking {
        val other = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/other/repo2/HEAD/manifest.json" to manifestJson, // same scraper id "fakesrc"
            "https://raw.githubusercontent.com/other/repo2/HEAD/providers/fakesrc.js" to scraperJs,
        )))
        nuvio.install(nuvio.previewScraper("owner/nuvio-repo", "fakesrc"))
        other.install(other.previewScraper("other/repo2", "fakesrc"))
        assertEquals(2, store.list().size)
        assertEquals(2, store.list().map { it.manifest.id }.distinct().size)
    }

    @Test fun `a scraper file that isn't valid JavaScript fails install with a clear message, not a crash`() = runBlocking {
        // Needs at least one domain-shaped string so the generated manifest still passes host
        // validation in previewScraper -- the point of this test is the JS itself being unparseable,
        // surfacing only later, at install()'s probe step.
        val broken = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/owner/broken/HEAD/manifest.json" to manifestJson,
            "https://raw.githubusercontent.com/owner/broken/HEAD/providers/fakesrc.js" to "<html>404 not really, see \"https://fakesrc.example\"</html>",
        )))
        val preview = broken.previewScraper("owner/broken", "fakesrc")
        val e = org.junit.Assert.assertThrows(InstallException::class.java) { runBlocking { broken.install(preview) } }
        assertTrue(e.message.orEmpty().isNotBlank())
    }

    @Test fun `a scraper with no detectable domain at all is refused with a clear message`() = runBlocking {
        val noHosts = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/owner/nohosts/HEAD/manifest.json" to manifestJson,
            "https://raw.githubusercontent.com/owner/nohosts/HEAD/providers/fakesrc.js" to
                "function getStreams() { return []; } module.exports = { getStreams: getStreams };",
        )))
        val e = org.junit.Assert.assertThrows(InstallException::class.java) { runBlocking { noHosts.previewScraper("owner/nohosts", "fakesrc") } }
        assertTrue(e.message.orEmpty().contains("ningún dominio"))
    }

    @Test fun `a failed Nuvio import never reports the owner or repo the person typed`() = runBlocking {
        val events = mutableListOf<PluginTelemetry.Event>()
        PluginTelemetry.current = PluginTelemetry(facts = { null }, sink = { events += it })
        try {
            // The repo stage: a typo / private repo -- nothing was even read.
            runCatching { nuvio.previewScraper("pepito-perez/mis-scrapers", "fakesrc") }
            // A later stage on a repo that is not one of the well-known public ones.
            val noHosts = NuvioPluginInstaller(installer, fetcher(mapOf(
                "https://raw.githubusercontent.com/pepito-perez/nohosts/HEAD/manifest.json" to manifestJson,
                "https://raw.githubusercontent.com/pepito-perez/nohosts/HEAD/providers/fakesrc.js" to
                    "function getStreams() { return []; } module.exports = { getStreams: getStreams };",
            )))
            runCatching { noHosts.previewScraper("pepito-perez/nohosts", "fakesrc") }
            assertEquals(2, events.size)
            events.forEach { e ->
                val all = (e.extras.values + e.tags.values + e.fingerprint + e.message).joinToString(" ")
                assertTrue(all, "pepito" !in all && "mis-scrapers" !in all && "nohosts" !in all)
                assertEquals(NuvioPluginInstaller.NUVIO_IMPORT_ID, e.tags["plugin_id"])
                assertNull(e.extras["nuvio_repo"])
            }
            assertEquals(listOf("nuvio:repo", "nuvio:no_domains"), events.map { it.extras["function"] })
            assertNull(events[0].extras["nuvio_scraper"])
            assertEquals("fakesrc", events[1].extras["nuvio_scraper"])
        } finally {
            PluginTelemetry.current = PluginTelemetry.NONE
        }
    }

    @Test fun `a real object-shaped domains json puts the scraper's own rotated domain first`() = runBlocking {
        val domainsUrl = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        val js = """
            var MAIN_URL = "https://new3.moviesdrive.christmas";
            var DOMAINS_URL = "$domainsUrl";
            var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
            function getStreams() { return fetch(DOMAINS_URL).then(function (r) { return r.json(); }).then(function (data) { return data.moviesdrive ? [] : []; }); }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val withDomains = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/owner/md/HEAD/manifest.json" to manifestJson,
            "https://raw.githubusercontent.com/owner/md/HEAD/providers/fakesrc.js" to js,
            domainsUrl to """{"moviesdrive": "https://new4.moviesdrive.christmas", "4khdhub": "https://4khdhub.one"}""",
        )))
        val preview = withDomains.previewScraper("owner/md", "fakesrc")
        assertEquals(
            // TMDB is the adapter's own and always first; then the scraper's rotated domain. 4khdhub.one is
            // another scraper's entry in the shared list: never declared for this one.
            listOf("api.themoviedb.org", "new4.moviesdrive.christmas", "new3.moviesdrive.christmas", "raw.githubusercontent.com"),
            preview.manifest.hosts,
        )
    }

    // ---- checkUpdate: the generated version is always 1.0.0, so change is detected by content ----

    private val repoFiles = mutableMapOf(
        "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to manifestJson,
        "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js" to scraperJs,
    )
    private fun liveNuvio() = NuvioPluginInstaller(installer, PluginFetcher { url, _ ->
        repoFiles[url]?.toByteArray() ?: throw FileNotFoundException(url)
    })

    private fun installedId(n: NuvioPluginInstaller): String = runBlocking {
        n.install(n.previewScraper("owner/nuvio-repo", "fakesrc"))
        store.list().single().manifest.id
    }

    @Test fun `checkUpdate with unchanged upstream code is UpToDate`() = runBlocking {
        val n = liveNuvio()
        val id = installedId(n)
        assertEquals(UpdateOutcome.UpToDate, n.checkUpdate(id))
    }

    @Test fun `checkUpdate applies a code-only change even though the generated version never moves`() = runBlocking {
        val n = liveNuvio()
        val id = installedId(n)
        val oldSha = store.get(id)!!.record.sha256
        repoFiles["https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js"] =
            scraperJs.replace("quality: \"1080p\"", "quality: \"720p\"")
        val outcome = n.checkUpdate(id)
        assertEquals(UpdateOutcome.Applied(NuvioPluginConverter.VERSION), outcome)
        val record = store.get(id)!!.record
        assertTrue(record.sha256 != oldSha)
        assertEquals("owner/nuvio-repo", record.nuvioRepo)
        assertEquals("fakesrc", record.nuvioScraperId)
    }

    @Test fun `checkUpdate needing approval marks the record pending, and a later UpToDate clears it`() = runBlocking {
        val n = liveNuvio()
        val id = installedId(n)
        val jsPath = "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js"
        repoFiles[jsPath] = scraperJs.replace("https://fakesrc.example/v.mp4", "https://newhost.example/v.mp4")
        val outcome = n.checkUpdate(id)
        assertTrue(outcome is UpdateOutcome.NeedsApproval)
        val pending = store.get(id)!!.record
        assertEquals(NuvioPluginConverter.VERSION, pending.pendingVersion)
        assertEquals(listOf("newhost.example"), pending.pendingHosts)
        // What PluginRegistry turns into UPDATE_PENDING in Ajustes.
        assertEquals(PluginStatus.UPDATE_PENDING, store.get(id)!!.let { InstalledPlugin(it.manifest, it.record, null) }.status)

        repoFiles[jsPath] = scraperJs // upstream reverted: nothing to update any more
        assertEquals(UpdateOutcome.UpToDate, n.checkUpdate(id))
        val cleared = store.get(id)!!.record
        assertNull(cleared.pendingVersion)
        assertTrue(cleared.pendingHosts.isEmpty())
    }

    // ---- updating a plugin the converter made before `episodes` and the reserved TMDB host existed ----

    /** Installs [hosts] + `search`/`resolve` under this scraper's id, exactly as the previous converter left it on disk. */
    private fun installAsTheOldConverterDid(hosts: List<String>): String = runBlocking {
        val entry = NuvioScraperEntry("fakesrc", "FakeSrc", "providers/fakesrc.js", true, emptyList(), listOf("movie"), null, emptyList())
        val current = NuvioPluginConverter.convert(entry, scraperJs, repoSlug = "owner/nuvio-repo")
        val oldJson = org.json.JSONObject(current.manifestJson)
            .put("capabilities", org.json.JSONArray(listOf("search", "resolve"))).put("hosts", org.json.JSONArray(hosts))
            .put("apiVersion", 1).put("version", "1.0.0").also { it.remove("streamHosts") }.toString()
        val oldManifest = (ManifestParser.parse(oldJson) as ManifestResult.Valid).manifest
        val oldScript = "export async function search(q) { return []; }\nexport async function resolve(r) { return { url: 'https://fakesrc.example/v.mp4' }; }"
        val origin = NuvioOrigin(repo = "owner/nuvio-repo", scraperId = "fakesrc", script = oldScript.toByteArray())
        installer.commit(installer.diffAgainstInstalled(PluginAddress.parse("owner/nuvio-repo")!!, oldManifest, oldJson, origin), oldScript.toByteArray(), icon = null)
        assertEquals(listOf("search", "resolve"), store.get(oldManifest.id)!!.record.capabilities)
        oldManifest.id
    }

    @Test fun `an old install that already declared TMDB updates to episodes, asking only for the new any-host video permission`() = runBlocking {
        val id = installAsTheOldConverterDid(listOf("api.themoviedb.org", "fakesrc.example"))
        assertTrue(!store.get(id)!!.record.videoFromAnyHost)
        val n = liveNuvio()
        val outcome = n.checkUpdate(id)
        assertTrue(outcome.toString(), outcome is UpdateOutcome.NeedsApproval)
        val preview = (outcome as UpdateOutcome.NeedsApproval).preview
        assertTrue(preview.newStreamHostsAny)
        assertTrue(preview.newHosts.isEmpty())
        assertTrue(store.get(id)!!.record.pendingStreamHostsAny)
        // The consent sheet marks the red line as new.
        assertTrue(PluginConsent.extraLines(preview).any { it.danger && it.isNew })
        // Downloads came with this converter too: the sheet shows their line as new.
        assertEquals(listOf("download"), preview.newCapabilities)
        assertTrue(PluginConsent.extraLines(preview).any { it.text == "Puede descargar videos para verlos sin conexión" && it.isNew })
        assertEquals(NuvioPluginConverter.VERSION, preview.manifest.version) // the sheet shows a new version, not 1.0.0 again
        n.install(preview) // what approving the sheet does
        val record = store.get(id)!!.record
        assertEquals(NuvioPluginConverter.VERSION, record.version)
        assertEquals(listOf("search", "episodes", "resolve", "download"), record.capabilities)
        assertTrue(record.streamHostsAny)
        assertTrue(record.videoFromAnyHost)
        assertTrue(!record.pendingStreamHostsAny)
    }

    @Test fun `an old install without TMDB waits for approval of that one host, then gets episodes`() = runBlocking {
        val id = installAsTheOldConverterDid(listOf("fakesrc.example"))
        val n = liveNuvio()
        val outcome = n.checkUpdate(id)
        assertTrue(outcome.toString(), outcome is UpdateOutcome.NeedsApproval)
        val preview = (outcome as UpdateOutcome.NeedsApproval).preview
        // `episodes` is not a capability that needs consent (only download/drm/channels do): the new
        // host and the converter's `download` are the only asks.
        assertEquals(listOf("api.themoviedb.org"), preview.newHosts)
        assertEquals(listOf("download"), preview.newCapabilities)
        assertEquals(listOf("api.themoviedb.org"), store.get(id)!!.record.pendingHosts)
        assertEquals(NuvioPluginConverter.VERSION, preview.manifest.version) // the sheet shows a new version, not 1.0.0 again
        n.install(preview) // what approving the sheet does
        val record = store.get(id)!!.record
        assertEquals(NuvioPluginConverter.VERSION, record.version)
        assertEquals(listOf("search", "episodes", "resolve", "download"), record.capabilities)
        assertEquals(listOf("api.themoviedb.org", "fakesrc.example"), record.hosts)
        assertNull(record.pendingVersion)
    }
}
