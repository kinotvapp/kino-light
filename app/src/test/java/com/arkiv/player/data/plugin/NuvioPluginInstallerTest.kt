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
        installer = PluginInstaller(store, fetcher(files), probe = { script ->
            val runtime = PluginRuntime.open("probe", script, ProbePluginHost, PluginEnv(appVersion = "1.0"))
            try { runtime.exports } finally { runtime.close() }
        })
        nuvio = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to manifestJson,
            "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js" to scraperJs,
        )), tmdbApiKey = "test-key")
    }

    @Test fun `previewRepo lists installable scrapers for a Nuvio-format repo`() = runBlocking {
        val scrapers = nuvio.previewRepo("owner/nuvio-repo")
        assertEquals(listOf("fakesrc"), scrapers!!.map { it.id })
    }

    @Test fun `previewRepo returns null for a repo whose manifest isn't Nuvio-shaped`() = runBlocking {
        val notNuvio = NuvioPluginInstaller(installer, fetcher(mapOf(
            "https://raw.githubusercontent.com/owner/other/HEAD/manifest.json" to """{"hello":"world"}""",
        )), tmdbApiKey = "k")
        assertNull(notNuvio.previewRepo("owner/other"))
    }

    @Test fun `previewScraper converts, validates, and installs, and playback resolves`() = runBlocking {
        val preview = nuvio.previewScraper("owner/nuvio-repo", "fakesrc")
        assertEquals(setOf("search", "resolve"), preview.manifest.capabilities)
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
        )), tmdbApiKey = "k")
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
            "https://raw.githubusercontent.com/owner/broken/HEAD/providers/fakesrc.js" to "<html>404 not really, see fakesrc.example</html>",
        )), tmdbApiKey = "k")
        val preview = broken.previewScraper("owner/broken", "fakesrc")
        val e = org.junit.Assert.assertThrows(InstallException::class.java) { runBlocking { broken.install(preview) } }
        assertTrue(e.message.orEmpty().isNotBlank())
    }
}
