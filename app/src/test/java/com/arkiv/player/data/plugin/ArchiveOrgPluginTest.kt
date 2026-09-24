package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewaySearchQuery
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Runs `plugins/archive-org/plugin.js` in the real PluginRuntime against recorded archive.org
 * answers (one file per URL, named by the URL's sha1; `index.txt` maps them back).
 *
 * Re-record (network needed) after changing the plugin's URLs:
 * ```
 * rm -r app/src/test/resources/plugins/archive-org
 * KINO_RECORD_FIXTURES=1 ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.arkiv.player.data.plugin.ArchiveOrgPluginTest" --rerun
 * ```
 * (`--no-daemon` so the test JVM surely sees the variable.)
 */
class ArchiveOrgPluginTest {
    // Gradle runs unit tests with the module directory (app/) as the working directory.
    private val pluginDir = File("../plugins/archive-org")
    private val fixtures = File("src/test/resources/plugins/archive-org")
    private val manifest = when (val parsed = ManifestParser.parse(File(pluginDir, "kino-plugin.json").readText())) {
        is ManifestResult.Valid -> parsed.manifest
        is ManifestResult.Invalid -> error("the app's ManifestParser rejects the manifest: ${parsed.field}: ${parsed.message}")
    }
    private val recording = System.getenv("KINO_RECORD_FIXTURES") == "1"
    private lateinit var runtime: PluginRuntime

    private inner class FixtureHost : PluginHost {
        override suspend fun fetch(requestJson: String): String {
            val url = JSONObject(requestJson).getString("url")
            val u = url.toHttpUrl()
            assertEquals("https", u.scheme)
            assertTrue("plugin asked for undeclared host ${u.host}", HostRules.matches(u.host, manifest.hosts))
            val file = File(fixtures, sha1(url) + ".json")
            if (!file.exists()) {
                check(recording) { "no fixture for $url — record with KINO_RECORD_FIXTURES=1 (see the class KDoc)" }
                record(url, file)
            }
            return JSONObject().put("ok", true).put("status", 200).put("url", url)
                .put("headers", JSONObject()).put("body", file.readText()).toString()
        }

        private fun record(url: String, file: File) {
            val body = OkHttpClient().newCall(Request.Builder().url(url).header("User-Agent", "Kino/test (plugin archive-org)").build())
                .execute().use { r -> check(r.isSuccessful) { "$url -> ${r.code}" }; r.body!!.string() }
            file.parentFile!!.mkdirs()
            file.writeText(body)
            File(fixtures, "index.txt").appendText("${file.name} $url\n")
        }

        override fun select(html: String, css: String) = PluginHtml.selectJson(html, css)
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = println("[archive-org] $level: $message")
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Before fun open() = runBlocking {
        runtime = PluginRuntime.open("archive-org", File(pluginDir, manifest.entry).readText(), FixtureHost(), PluginEnv(appVersion = "test"))
    }

    @After fun close() = runtime.close()

    @Test fun `manifest is valid and every capability is exported`() {
        assertEquals("archive-org", manifest.id)
        assertTrue(runtime.exports.containsAll(manifest.capabilities))
    }

    @Test fun `search finds public-domain films as movies`() = runBlocking {
        val json = runtime.call("search", PluginContentSource.queryJson(GatewaySearchQuery(q = "metropolis", type = "movie")), 15_000)
        val items = PluginOutput.items(json, allowSeries = true)
        assertTrue(items.any { it.id == "TheGiantOfMetropolis1961" })
        assertTrue(items.all { it.kind == "movie" })
        assertTrue(items.all { it.poster.startsWith("https://archive.org/services/img/") })
    }

    @Test fun `search for a series returns classic TV as series`() = runBlocking {
        val json = runtime.call("search", PluginContentSource.queryJson(GatewaySearchQuery(q = "dragnet", type = "tv")), 15_000)
        assertTrue(PluginOutput.items(json, allowSeries = true).any { it.id == "Dragnet1951" && it.kind == "series" })
    }

    @Test fun `home has its three rows`() = runBlocking {
        val rows = PluginOutput.rows(runtime.call("home", "null", 20_000), allowSeries = true)
        assertEquals(listOf("films", "tv", "cartoons"), rows.map { it.id })
        assertTrue(rows.all { it.items.isNotEmpty() })
    }

    @Test fun `episodes of a classic TV item come by season and number`() = runBlocking {
        val eps = PluginOutput.episodes(runtime.call("episodes", JSONObject.quote("Dragnet1951"), 20_000)).episodes
        assertTrue(eps.size > 5)
        assertEquals(1 to 1, eps.first().season to eps.first().number)
        assertTrue(eps.first().title.contains("Human Bomb"))
        assertTrue(eps.any { it.season == 2 })
    }

    @Test fun `a movie resolves to its h264 mp4 on a declared host`() = runBlocking {
        val s = PluginOutput.stream(runtime.call("resolve", JSONObject.quote("TheGiantOfMetropolis1961"), 20_000), manifest.hosts)
        assertEquals("https://archive.org/download/TheGiantOfMetropolis1961/GiantOfMetropolisversionUs.mp4", s.url)
        assertEquals("video/mp4", s.mime)
        assertTrue(s.durationMs > 5_000_000)
    }

    @Test fun `an episode resolves with its subtitles`() = runBlocking {
        val first = PluginOutput.episodes(runtime.call("episodes", JSONObject.quote("Dragnet1951"), 20_000)).episodes.first()
        val s = PluginOutput.stream(runtime.call("resolve", JSONObject.quote(first.ref), 20_000), manifest.hosts)
        assertTrue(s.url, s.url.startsWith("https://archive.org/download/Dragnet1951/Dragnet/Season%201/"))
        assertEquals(listOf("en"), s.subtitles.map { it.lang })
    }

    // The tests below pin what real archive.org items did to the first version of the plugin.

    @Test fun `a query with search syntax in it is cleaned up instead of failing`() = runBlocking {
        // advancedsearch answers 200 {"error": ...} to a stray "/" or "-", and to a dangling or/and/not.
        for ((q, expected) in listOf("Romeo AND Juliet" to "romeo-and-juliet-1933", "the -general/" to "TheGeneral")) {
            val json = runtime.call("search", PluginContentSource.queryJson(GatewaySearchQuery(q = q, type = "movie")), 15_000)
            assertTrue(q, PluginOutput.items(json, allowSeries = true).any { it.id == expected })
        }
    }

    @Test fun `an item whose first original cannot be played still resolves`() = runBlocking {
        // Sintel: the first original by name is an .avi documentary with no mp4 made from it.
        val s = PluginOutput.stream(runtime.call("resolve", JSONObject.quote("Sintel"), 20_000), manifest.hosts)
        assertEquals("https://archive.org/download/Sintel/sintel-2048-stereo.mp4", s.url)
    }

    @Test fun `originals with an unusual extension resolve through their derived mp4`() = runBlocking {
        // A Bosko collection: the originals are .divx, only the derivatives (ogv, 512kb mp4) can play.
        val s = PluginOutput.stream(runtime.call("resolve", JSONObject.quote("A_Bosko_Cartoon_Collection_1930-1932"), 20_000), manifest.hosts)
        assertEquals("https://archive.org/download/A_Bosko_Cartoon_Collection_1930-1932/01_Congo_Jazz_1930_512kb.mp4", s.url)
    }

    @Test fun `an item with no video says so`() = runBlocking {
        // Chaplin's His New Job: only metadata files are left on the item.
        val e = runCatching { runtime.call("resolve", JSONObject.quote("HisNewJobCharlesChaplin-1915"), 20_000) }.exceptionOrNull()
        assertTrue(e.toString(), e is PluginScriptException && e.message!!.contains("este item no tiene video"))
    }
}
