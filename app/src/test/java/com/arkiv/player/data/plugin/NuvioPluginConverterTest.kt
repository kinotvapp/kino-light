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
        val result = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", tmdbApiKey = "test-key")
        val parsed = ManifestParser.parse(result.manifestJson)
        assertTrue(parsed is ManifestResult.Valid)
        val manifest = (parsed as ManifestResult.Valid).manifest
        assertEquals(setOf("search", "episodes", "resolve"), manifest.capabilities)
        assertEquals(listOf("api.themoviedb.org", "fakesrc.example"), manifest.hosts)
        assertTrue(manifest.id.startsWith("nuvio-fakesrc-"))
        assertEquals(4, manifest.apiVersion)
        assertTrue(manifest.streamHostsAny)
    }

    /**
     * A realistic esbuild-bundled scraper head (modelled on phisher98's real `moviesdrive.js`): a
     * `User-Agent` full of version numbers, an IP-shaped `Chrome/120.0.0.0`, `module.exports`,
     * `cheerio.load`, CSS selectors, file names, comments and a regex literal with a quote in it.
     * Before the string-literal-only extraction this failed ManifestParser outright (`5.0`/`537.36`
     * are invalid hosts) -- and the assertion is EXACT, not `containsAll`, so junk can't hide.
     */
    private val realisticSource = """
        var cheerio = require("cheerio-without-node-native");
        var TMDB_BASE_URL = "https://api.themoviedb.org/3";
        var MAIN_URL = "https://new3.moviesdrive.christmas";
        var DOMAINS_URL = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json";
        // don't touch: see https://github.com/phisher98 for the upstream
        var HEADERS = {
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
          "Referer": `${'$'}{MAIN_URL}/`
        };
        var MIRRORS = ["drivebot.cfd", "drivebot.sbs"];
        function clean(s) { return s.replace(/['"]/g, "").split("index.html")[0]; }
        function getStreams(tmdbId, mediaType, season, episode) {
          var ${'$'} = cheerio.load("<div></div>");
          ${'$'}("div.entry-content a.btn").each(function () {});
          var img = "poster.png";
          return fetch(`https://new6.gdflix.dad/file/${'$'}{tmdbId}`).then(function (r) { return []; });
        }
        module.exports = { getStreams: getStreams };
    """.trimIndent()

    @Test fun `a realistic scraper declares exactly its real domains, no version numbers or identifiers`() {
        val result = NuvioPluginConverter.convert(scraper, realisticSource, repoSlug = "owner/repo", tmdbApiKey = "k")
        val parsed = ManifestParser.parse(result.manifestJson)
        assertTrue("generated manifest must validate, got $parsed", parsed is ManifestResult.Valid)
        assertEquals(
            listOf(
                "api.themoviedb.org", "new3.moviesdrive.christmas", "raw.githubusercontent.com",
                "drivebot.cfd", "drivebot.sbs", "new6.gdflix.dad",
            ),
            (parsed as ManifestResult.Valid).manifest.hosts,
        )
    }

    @Test fun `TMDB goes first, then remote domains, garbage never takes a cap slot, and unnamed remote ones go last`() {
        val remote = NuvioRemoteHosts(
            preferred = listOf("new4.moviesdrive.christmas"),
            others = (1..30).map { "mirror$it.example" },
        )
        val result = NuvioPluginConverter.convert(scraper, realisticSource, repoSlug = "owner/repo", tmdbApiKey = "k", remoteHosts = remote)
        val manifest = (ManifestParser.parse(result.manifestJson) as ManifestResult.Valid).manifest
        assertEquals(ManifestParser.MAX_HOSTS, manifest.hosts.size)
        assertEquals(
            listOf(
                "api.themoviedb.org",
                "new4.moviesdrive.christmas",
                "new3.moviesdrive.christmas", "raw.githubusercontent.com",
                "drivebot.cfd", "drivebot.sbs", "new6.gdflix.dad",
            ) + (1..13).map { "mirror$it.example" },
            manifest.hosts,
        )
        assertTrue(result.warnings.any { "más de 20 dominios" in it })
    }

    @Test fun `the host-cap warning reaches the manifest description the consent sheet shows`() {
        val remote = NuvioRemoteHosts(emptyList(), (1..30).map { "mirror$it.example" })
        val longName = scraper.copy(name = "N".repeat(80))
        val result = NuvioPluginConverter.convert(longName, realisticSource, repoSlug = "an-owner-with-a-long-name/a-very-long-repository-name", tmdbApiKey = "k", remoteHosts = remote)
        assertEquals(1, result.warnings.size)
        val description = (ManifestParser.parse(result.manifestJson) as ManifestResult.Valid).manifest.description
        result.warnings.forEach { assertTrue("missing \"$it\" in \"$description\"", it in description) }
        assertTrue(description.length <= ManifestParser.MAX_DESCRIPTION_CHARS)
    }

    @Test fun `cheerio tree walking is no longer a warning - the real library runs it`() {
        val withParent = realisticSource.replace(".each(", ".parent().nextAll(\"h5\").each(")
        assertTrue(NuvioPluginConverter.convert(scraper, withParent, repoSlug = "o/r", tmdbApiKey = "k").warnings.isEmpty())
    }

    /** Records exactly what the adapter hands the wrapped scraper's getStreams. */
    private val echoTypeSource = """
        function getStreams(tmdbId, mediaType, season, episode) {
          return [{ name: "x", title: "t", url: "https://cdn.example/" + mediaType + "/" + season + "/" + episode }];
        }
        module.exports = { getStreams: getStreams };
    """.trimIndent()

    private fun resolvedUrl(refJson: String): String = runBlocking {
        val result = NuvioPluginConverter.convert(scraper, echoTypeSource, repoSlug = "o/r", tmdbApiKey = "k")
        val runtime = PluginRuntime.open("probe", result.script, ProbePluginHost, PluginEnv(appVersion = "1.0"))
        try {
            JSONObject(runtime.call("resolve", JSONObject.quote(refJson), 5_000)).getString("url")
        } finally {
            runtime.close()
        }
    }

    @Test fun `a series ref reaches the scraper as Nuvio's tv, never Kino's series`() {
        assertEquals("https://cdn.example/tv/2/5", resolvedUrl("""{"tmdbId":1399,"type":"series","season":2,"episode":5}"""))
        assertEquals("https://cdn.example/movie/null/null", resolvedUrl("""{"tmdbId":603,"type":"movie","season":0,"episode":0}"""))
    }

    @Test fun `an any-typed ref is decided from its episode fields`() {
        assertEquals("https://cdn.example/tv/1/3", resolvedUrl("""{"tmdbId":1399,"type":"any","season":1,"episode":3}"""))
        assertEquals("https://cdn.example/movie/null/null", resolvedUrl("""{"tmdbId":603,"type":"any","season":0,"episode":0}"""))
    }

    @Test fun `search then resolve for a TV episode calls getStreams with tv end to end`() = runBlocking {
        val result = NuvioPluginConverter.convert(scraper, echoTypeSource, repoSlug = "o/r", tmdbApiKey = "k")
        val runtime = PluginRuntime.open("probe", result.script, ProbePluginHost, PluginEnv(appVersion = "1.0"))
        try {
            val query = PluginContentSource.queryJson(com.arkiv.player.data.gateway.GatewaySearchQuery(q = "GoT", type = "tv", season = 2, episode = 5, tmdbId = 1399))
            val ref = JSONArray(runtime.call("search", query, 5_000)).getJSONObject(0).getString("ref")
            val out = JSONObject(runtime.call("resolve", JSONObject.quote(ref), 5_000))
            assertEquals("https://cdn.example/tv/2/5", out.getString("url"))
        } finally {
            runtime.close()
        }
    }

    @Test fun `the generated script exports search and resolve and runs the wrapped scraper`() = runBlocking {
        val result = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", tmdbApiKey = "test-key")
        val runtime = PluginRuntime.open("probe", result.script, ProbePluginHost, PluginEnv(appVersion = "1.0"))
        try {
            assertEquals(setOf("search", "episodes", "resolve"), runtime.exports)
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
        val result = NuvioPluginConverter.convert(scraper, torrentSource, repoSlug = "owner/repo", tmdbApiKey = "k")
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
        val result = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", tmdbApiKey = "test-key")
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

    /**
     * Mirrors the real MoviesDrive bug (`phisher98/phisher-nuvio-providers`): a bundled/minified
     * scraper that redeclares `module`, `exports`, `require` AND `fetch` at its own top level. Before
     * the IIFE-scoping fix, concatenating this straight after the shim (which ALSO declares those
     * four names at ITS top level) made the whole `plugin.js` ES module fail to load with
     * `SyntaxError: invalid redefinition of global identifier`, since two top-level declarations of
     * the same name in one module scope collide -- see [NuvioPluginConverter]'s KDoc.
     */
    private val selfShadowingSource = """
        var module = { exports: {} };
        var exports = module.exports;
        function require(name) { throw new Error("should never be called: " + name); }
        function fetch() { throw new Error("should never be called"); }
        function getStreams(tmdbId, mediaType, season, episode) {
          return [{ name: "x", title: "t", url: "https://cdn.example/self-shadow" }];
        }
        module.exports = { getStreams: getStreams };
    """.trimIndent()

    @Test fun `a scraper that redeclares module, exports, require and fetch at its own top level still converts and runs`() = runBlocking {
        val result = NuvioPluginConverter.convert(scraper, selfShadowingSource, repoSlug = "owner/repo", tmdbApiKey = "k")
        val runtime = PluginRuntime.open("probe", result.script, ProbePluginHost, PluginEnv(appVersion = "1.0"))
        try {
            val out = JSONObject(runtime.call("resolve", """{"tmdbId":1,"type":"movie","season":0,"episode":0}""", 5_000))
            assertEquals("https://cdn.example/self-shadow", out.getString("url"))
        } finally {
            runtime.close()
        }
    }

    @Test fun `a long scraper id never lets the slug truncate away the anti-collision hash`() {
        val longScraper = scraper.copy(id = "a".repeat(60))
        val result = NuvioPluginConverter.convert(longScraper, source, repoSlug = "owner/repo", tmdbApiKey = "k")
        val manifest = (ManifestParser.parse(result.manifestJson) as ManifestResult.Valid).manifest
        assertTrue(manifest.id.length <= 40)
        val hashSuffix = manifest.id.substringAfterLast('-')
        assertEquals(6, hashSuffix.length)
        assertTrue(hashSuffix.all { it in "0123456789abcdef" })
    }
}
