package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a converted Nuvio scraper speaks Kino's contract for series and movies: a series is ONE
 * `series` item whose `episodes` come from TMDB (Nuvio's `getStreams` only takes a tmdbId + season +
 * episode, it has no catalogue of its own), each episode resolving to `getStreams(id, "tv", s, e)`;
 * a movie resolves to `getStreams(id, "movie", null, null)`, exactly what Nuvio itself passes. Runs
 * the generated `plugin.js` in the real sandbox against a fake TMDB, and reads `episodes` back
 * through the app's own [PluginOutput.episodes] parser.
 */
class NuvioSeriesAdapterTest {
    private val scraper = NuvioScraperEntry(
        id = "echosrc", name = "EchoSrc", filename = "providers/echosrc.js", enabled = true,
        contentLanguage = listOf("en"), supportedTypes = listOf("movie", "tv"), logo = null, disabledPlatforms = emptyList(),
    )

    /** Echoes exactly what the adapter handed getStreams, types included. */
    private val echoSource = """
        function getStreams(tmdbId, mediaType, season, episode) {
          return Promise.resolve([{ name: "x", title: "t",
            url: "https://cdn.echo.example/" + [typeof tmdbId, tmdbId, mediaType, season, episode].join("/") }]);
        }
        module.exports = { getStreams: getStreams };
    """.trimIndent()

    private val show = """
        {"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20","poster_path":"/bb.jpg","backdrop_path":"/bd.jpg",
         "overview":"Un profesor de química.","genres":[{"id":18,"name":"Drama"}],
         "seasons":[{"season_number":0,"name":"Especiales"},{"season_number":1},{"season_number":2}]}
    """.trimIndent()
    private val season1 = """
        {"season_number":1,"episodes":[
          {"episode_number":1,"season_number":1,"name":"Piloto","still_path":"/s1e1.jpg","overview":"Walter.","air_date":"2008-01-20","runtime":58},
          {"episode_number":2,"season_number":1,"name":"El gato está en la bolsa","still_path":null,"overview":"","air_date":"2008-01-27","runtime":48}]}
    """.trimIndent()
    private val season2 = """
        {"season_number":2,"episodes":[
          {"episode_number":1,"season_number":2,"name":"Siete treinta y siete","still_path":"/s2e1.jpg","overview":"","air_date":"2009-03-08","runtime":47},
          {"episode_number":2,"season_number":2,"name":"Todavía no","still_path":null,"overview":"","air_date":"2999-01-01","runtime":null}]}
    """.trimIndent()
    private val movie = """{"id":603,"title":"Matrix","release_date":"1999-03-30","poster_path":"/matrix.jpg","backdrop_path":"/mbd.jpg","overview":"Neo."}"""

    private fun tmdbHost() = NuvioRoutingHost(
        mapOf(
            "/3/tv/1396?" to NuvioRoutingHost.Reply(body = show),
            "/3/tv/1396/season/0?" to NuvioRoutingHost.Reply(body = """{"episodes":[{"episode_number":1,"name":"Especial"}]}"""),
            "/3/tv/1396/season/1?" to NuvioRoutingHost.Reply(body = season1),
            "/3/tv/1396/season/2?" to NuvioRoutingHost.Reply(body = season2),
            "/3/movie/603?" to NuvioRoutingHost.Reply(body = movie),
        ),
    )

    private val conversion by lazy { NuvioPluginConverter.convert(scraper, echoSource, repoSlug = "owner/repo") }

    private fun <T> withRuntime(host: PluginHost, block: suspend (PluginRuntime) -> T): T = runBlocking {
        val runtime = PluginRuntime.open("series", conversion.script, host, PluginEnv(appVersion = "1.0"))
        try { block(runtime) } finally { runtime.close() }
    }

    private fun query(type: String, tmdbId: Int, season: Int = 0, episode: Int = 0, q: String = "Breaking Bad") =
        PluginContentSource.queryJson(com.arkiv.player.data.gateway.GatewaySearchQuery(q = q, type = type, season = season, episode = episode, tmdbId = tmdbId))

    @Test fun `the manifest declares the episodes capability and the script exports it`() {
        val manifest = (ManifestParser.parse(conversion.manifestJson) as ManifestResult.Valid).manifest
        assertEquals(setOf("search", "episodes", "resolve", "download"), manifest.capabilities)
        withRuntime(ProbePluginHost) { assertEquals(setOf("search", "episodes", "resolve"), it.exports) }
    }

    @Test fun `a series search with no episode chosen answers one series item with TMDB's poster and year`() {
        val host = tmdbHost()
        val items = withRuntime(host) { JSONArray(it.call("search", query("tv", 1396), 5_000)) }
        assertEquals(1, items.length())
        val item = items.getJSONObject(0)
        assertEquals("series", item.getString("kind"))
        assertEquals("Breaking Bad", item.getString("title"))
        assertEquals(1396, item.getJSONObject("ids").getInt("tmdb"))
        assertEquals("2008", item.getString("year"))
        assertEquals("https://image.tmdb.org/t/p/w500/bb.jpg", item.getString("poster"))
        // What Kino itself keeps of it: a series item survives only with the episodes capability.
        val page = PluginOutput.page(items.toString(), 100, allowSeries = true, allowNext = false, hosts = EffectiveHosts(emptyList()))
        assertEquals(listOf("series"), page.items.map { it.kind })
        val tmdb = host.urls().single()
        assertTrue(tmdb, tmdb.startsWith("https://api.themoviedb.org/3/tv/1396?"))
        assertTrue(tmdb, "api_key=${NuvioPluginConverter.TMDB_KEY_MARKER}" in tmdb && "language=es-MX" in tmdb)
    }

    @Test fun `episodes lists every real season from TMDB, skipping specials and unaired episodes, in Kino's shape`() {
        val host = tmdbHost()
        val itemRef = withRuntime(host) { JSONArray(it.call("search", query("tv", 1396), 5_000)).getJSONObject(0).getString("ref") }
        val out = withRuntime(host) { it.call("episodes", JSONObject.quote(itemRef), 10_000) }
        val parsed = PluginOutput.episodes(out)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), parsed.episodes.map { it.season to it.number })
        val pilot = parsed.episodes.first()
        assertEquals("Piloto", pilot.title)
        assertEquals("https://image.tmdb.org/t/p/w300/s1e1.jpg", pilot.still)
        assertEquals("Walter.", pilot.overview)
        assertEquals("2008-01-20", pilot.airDate)
        assertEquals(58, pilot.runtimeMinutes)
        assertEquals("", parsed.episodes[1].still)
        val series = parsed.series!!
        assertEquals("Breaking Bad", series.title)
        assertEquals(1396, series.tmdbId)
        assertEquals("https://image.tmdb.org/t/p/w500/bb.jpg", series.poster)
        assertEquals("2008", series.year)
        assertEquals(listOf("Drama"), series.genres)
        assertFalse("season 0 (specials) must never be fetched", host.urls().any { "/season/0" in it })
    }

    @Test fun `an episode's ref resolves to getStreams with tv and that season and episode`() {
        val host = tmdbHost()
        val refs = withRuntime(host) { rt ->
            val itemRef = JSONArray(rt.call("search", query("tv", 1396), 5_000)).getJSONObject(0).getString("ref")
            PluginOutput.episodes(rt.call("episodes", JSONObject.quote(itemRef), 10_000)).episodes.associate { (it.season to it.number) to it.ref }
        }
        val url = withRuntime(host) { JSONObject(it.call("resolve", JSONObject.quote(refs.getValue(2 to 1)), 5_000)).getString("url") }
        assertEquals("https://cdn.echo.example/string/1396/tv/2/1", url)
    }

    @Test fun `a search that already names the episode still answers one playable item for it`() {
        val host = tmdbHost()
        val items = withRuntime(host) { JSONArray(it.call("search", query("tv", 1396, season = 1, episode = 2), 5_000)) }
        assertEquals(1, items.length())
        val item = items.getJSONObject(0)
        assertEquals("movie", item.getString("kind"))
        // Its tmdb id is a SHOW's: as an `ids.tmdb` on a movie-kind item Kino would enrich it from /movie/1396.
        assertFalse(item.has("ids"))
        val url = withRuntime(host) { JSONObject(it.call("resolve", JSONObject.quote(item.getString("ref")), 5_000)).getString("url") }
        assertEquals("https://cdn.echo.example/string/1396/tv/1/2", url)
    }

    @Test fun `a movie search carries TMDB's poster and year, and resolves to getStreams with movie, null, null`() {
        val host = tmdbHost()
        val items = withRuntime(host) { JSONArray(it.call("search", query("movie", 603, q = "Matrix"), 5_000)) }
        val item = items.getJSONObject(0)
        assertEquals("movie", item.getString("kind"))
        assertEquals("603-movie-0-0", item.getString("id"))
        assertEquals(603, item.getJSONObject("ids").getInt("tmdb"))
        assertEquals("1999", item.getString("year"))
        assertEquals("https://image.tmdb.org/t/p/w500/matrix.jpg", item.getString("poster"))
        val url = withRuntime(host) { JSONObject(it.call("resolve", JSONObject.quote(item.getString("ref")), 5_000)).getString("url") }
        assertEquals("https://cdn.echo.example/string/603/movie//", url)
        // A movie ref saved in a library before this change ({..., season: 0, episode: 0}) resolves the same way.
        val old = withRuntime(host) { JSONObject(it.call("resolve", JSONObject.quote("""{"tmdbId":603,"type":"movie","season":0,"episode":0}"""), 5_000)).getString("url") }
        assertEquals("https://cdn.echo.example/string/603/movie//", old)
    }

    @Test fun `a movie getStreams really receives null, not 0, for season and episode`() {
        val strict = echoSource.replace("[typeof tmdbId, tmdbId, mediaType, season, episode]", "[mediaType, season === null, episode === null]")
        val result = NuvioPluginConverter.convert(scraper, strict, repoSlug = "owner/repo")
        val url = runBlocking {
            val rt = PluginRuntime.open("strict", result.script, tmdbHost(), PluginEnv(appVersion = "1.0"))
            try { JSONObject(rt.call("resolve", JSONObject.quote("""{"tmdbId":603,"type":"movie","season":0,"episode":0}"""), 5_000)).getString("url") } finally { rt.close() }
        }
        assertEquals("https://cdn.echo.example/movie/true/true", url)
    }

    @Test fun `a search still answers its item, just without artwork, when TMDB fails`() {
        val items = withRuntime(NuvioRoutingHost(emptyMap())) { JSONArray(it.call("search", query("movie", 603, q = "Matrix"), 5_000)) }
        assertEquals(1, items.length())
        assertEquals("Matrix", items.getJSONObject(0).getString("title"))
        assertFalse(items.getJSONObject(0).has("poster"))
    }

    /**
     * Measured on a Redmi Note 9 Pro: now and then one Nuvio plugin's search sat the whole 15 s and was
     * dropped as "no respondió" (a strike toward "No responde"), while ten others answered in ~1 s --
     * its only I/O was this artwork request, which had kino.fetch's default 15 s, the search's whole
     * budget. Artwork is optional: it gets a few seconds, and the item comes back without it.
     */
    @Test fun `search's optional TMDB artwork request has a short timeout, far inside the search limit`() {
        val host = tmdbHost()
        withRuntime(host) { it.call("search", query("movie", 603, q = "Matrix"), 5_000) }
        val artwork = host.requests.single()
        assertTrue(artwork.url, artwork.url.startsWith("https://api.themoviedb.org/3/movie/603?"))
        assertTrue("timeoutMs=${artwork.timeoutMs}", artwork.timeoutMs in 1..5_000)
    }

    @Test fun `api themoviedb org is always declared, even when the scraper's own hosts fill the cap`() {
        val many = (1..30).joinToString("\n") { "var u$it = \"https://mirror$it.example/x\";" }
        val source = many + "\n" + echoSource
        val result = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo",
            remoteHosts = NuvioRemoteHosts(preferred = listOf("preferred.example"), others = (1..30).map { "remote$it.example" }))
        val manifest = (ManifestParser.parse(result.manifestJson) as ManifestResult.Valid).manifest
        assertEquals(ManifestParser.MAX_HOSTS, manifest.hosts.size)
        assertTrue(manifest.hosts.toString(), "api.themoviedb.org" in manifest.hosts)
        assertEquals("preferred.example", manifest.hosts[1])
    }
}
