package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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

    /**
     * `ProbePluginHost` always refuses `fetch` (it's the install-time no-network probe), so this uses
     * a real working host stub -- same idea as `ArchiveOrgPluginTest.FixtureHost`, canned instead of
     * recorded -- to actually exercise the shim's `fetch(...).then(r => r.json())` path end to end.
     * This is the regression test for the bug where the shim read `r.text`/`r.json` as data instead
     * of calling them as the functions kino.fetch's resolved response really exposes them as: before
     * the fix, `r.text || ""` always evaluated to the function itself (functions are truthy), and
     * `JSON.parse` on it threw a SyntaxError, so `getStreams` never resolved with real stream data.
     */
    private class FakeJsonHost(private val body: String) : PluginHost {
        val requestedUrls = mutableListOf<String>()
        override suspend fun fetch(requestJson: String): String {
            val url = JSONObject(requestJson).getString("url")
            requestedUrls += url
            return JSONObject().put("ok", true).put("status", 200).put("url", url)
                .put("headers", JSONObject()).put("text", body).toString()
        }

        override fun select(html: String, css: String): String = PluginHtml.selectJson(html, css)
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String, ttlMs: Long?) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
    }

    @Test fun `resolve actually parses kino fetch's JSON response by calling r_json(), not reading it as data`() = runBlocking {
        val result = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", extraHosts = emptyList(), tmdbApiKey = "test-key")
        val host = FakeJsonHost(
            """{"streams":[{"title":"The Matrix","url":"https://cdn.fakesrc.example/matrix.mp4","quality":"1080p"}]}""",
        )
        val runtime = PluginRuntime.open("probe", result.script, host, PluginEnv(appVersion = "1.0"))
        try {
            val refJson = """{"tmdbId":603,"type":"movie","season":0,"episode":0}"""
            // A quoted JSON string, exactly how `PluginContentSource.resolve` calls a real plugin's
            // `resolve` (JSONObject.quote(own.ref)) -- exercising the adapter's string-ref branch,
            // the one the torrent-rejection test above doesn't (it passes a raw object).
            val out = JSONObject(runtime.call("resolve", JSONObject.quote(refJson), 20_000))
            assertEquals("https://cdn.fakesrc.example/matrix.mp4", out.getString("url"))
            assertEquals(listOf("https://fakesrc.example/api/603"), host.requestedUrls)
        } finally {
            runtime.close()
        }
    }

    @Test fun `a long scraper id never lets the slug truncate away the anti-collision hash`() {
        val longScraper = scraper.copy(id = "a".repeat(60))
        val result = NuvioPluginConverter.convert(longScraper, source, repoSlug = "owner/repo", extraHosts = emptyList(), tmdbApiKey = "k")
        val manifest = (ManifestParser.parse(result.manifestJson) as ManifestResult.Valid).manifest
        assertTrue(manifest.id.length <= 40)
        val hashSuffix = manifest.id.substringAfterLast('-')
        assertEquals(6, hashSuffix.length)
        assertTrue(hashSuffix.all { it in "0123456789abcdef" })
    }
}
