package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the generated `plugin.js` through the REAL QuickJS sandbox ([PluginRuntime]/[ProbePluginHost],
 * the same pair `ArchiveOrgPluginTest`/`PluginInstaller.probe` use), not just checked as a string: a
 * converter that produced syntactically-plausible-but-broken JS would fail these, not just a Kotlin
 * unit test around [NuvioPluginConverter] alone.
 */
class NuvioPluginConverterTest {
    private val scraper = NuvioScraperEntry(
        id = "fakesrc", name = "FakeSrc", filename = "providers/fakesrc.js", enabled = true,
        contentLanguage = listOf("en"), supportedTypes = listOf("movie"), logo = null, disabledPlatforms = emptyList(),
    )

    // A tiny but representative scraper: fetch + JSON, no cheerio/crypto -- those get their own tests.
    private val source = """
        function getStreams(tmdbId, mediaType, season, episode) {
          return fetch("https://fakesrc.example/api/" + tmdbId)
            .then(function (r) { return r.json(); })
            .then(function (d) { return d.streams.map(function (s) { return { name: "FakeSrc", title: s.title, url: s.url, quality: s.quality }; }); });
        }
        module.exports = { getStreams: getStreams };
    """.trimIndent()

    @Test fun `the generated manifest validates and declares the extracted hosts`() {
        val result = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", extraHosts = emptyList(), tmdbApiKey = "test-key")
        val parsed = ManifestParser.parse(result.manifestJson)
        assertTrue(parsed is ManifestResult.Valid)
        val manifest = (parsed as ManifestResult.Valid).manifest
        assertEquals(setOf("search", "resolve"), manifest.capabilities)
        assertTrue("fakesrc.example" in manifest.hosts)
        assertTrue(manifest.id.startsWith("nuvio-fakesrc-"))
    }

    @Test fun `the generated script exports search and resolve and runs the wrapped scraper`() = runBlocking {
        val result = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", extraHosts = emptyList(), tmdbApiKey = "test-key")
        val runtime = PluginRuntime.open("probe", result.script, ProbePluginHost, PluginEnv(appVersion = "1.0"))
        try {
            assertEquals(setOf("search", "resolve"), runtime.exports)
            // Not just "exports exist": actually call search() through the real sandbox and check the
            // adapter + wrapped scraper agree on the shape resolve() will later need back.
            val items = JSONArray(
                runtime.call("search", """{"tmdbId":603,"type":"movie","season":0,"episode":0,"q":"The Matrix"}""", 5_000),
            )
            assertEquals(1, items.length())
            val item = items.getJSONObject(0)
            assertEquals("The Matrix", item.getString("title"))
            assertEquals("603-movie-0-0", item.getString("id"))
        } finally {
            runtime.close()
        }
    }

    @Test fun `resolve rejects a torrent-only result with kino error unavailable`() = runBlocking {
        val torrentSource = """
            function getStreams() { return [{ name: "x", title: "t", infoHash: "abc123", quality: "1080p" }]; }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val result = NuvioPluginConverter.convert(scraper, torrentSource, repoSlug = "owner/repo", extraHosts = emptyList(), tmdbApiKey = "k")
        val runtime = PluginRuntime.open("probe", result.script, ProbePluginHost, PluginEnv(appVersion = "1.0"))
        try {
            val e = assertThrows(PluginErrorException::class.java) {
                runBlocking { runtime.call("resolve", """{"tmdbId":1,"type":"movie","season":0,"episode":0}""", 5_000) }
            }
            assertEquals("unavailable", e.code)
        } finally {
            runtime.close()
        }
    }
}
