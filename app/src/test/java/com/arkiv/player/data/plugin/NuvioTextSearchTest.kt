package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewaySearchQuery
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A typed search (no TMDB id: the TV's "Ir", "Ver otras fuentes" by text) on a converted Nuvio
 * scraper: the adapter asks TMDB's own search, answers its matches as ordinary items whose refs
 * resolve through `getStreams`, and never calls `getStreams` while searching. Runs the generated
 * `plugin.js` in the real sandbox against a fake TMDB.
 */
class NuvioTextSearchTest {
    private fun scraper(types: List<String>) = NuvioScraperEntry(
        id = "echosrc", name = "EchoSrc", filename = "providers/echosrc.js", enabled = true,
        contentLanguage = listOf("es"), supportedTypes = types, logo = null, disabledPlatforms = emptyList(),
    )

    /** Echoes what the adapter handed getStreams, and counts how often it was called. */
    private val echoSource = """
        var calls = 0;
        function getStreams(tmdbId, mediaType, season, episode) {
          calls++;
          console.log("getStreams called " + calls);
          return Promise.resolve([{ name: "x", title: "t",
            url: "https://cdn.echo.example/" + [typeof tmdbId, tmdbId, mediaType, season, episode].join("/") }]);
        }
        module.exports = { getStreams: getStreams };
    """.trimIndent()

    private val multi = """
        {"page":1,"results":[
          {"id":533535,"media_type":"movie","title":"Deadpool y Wolverine","original_title":"Deadpool & Wolverine","release_date":"2024-07-24","poster_path":"/dw.jpg","backdrop_path":"/dwb.jpg","overview":"Wade.","popularity":300.5},
          {"id":99,"media_type":"person","name":"Ryan Reynolds","popularity":900},
          {"id":293660,"media_type":"movie","title":"Deadpool","original_title":"Deadpool","release_date":"2016-02-09","poster_path":"/d1.jpg","overview":"Un mercenario.","popularity":120.0},
          {"id":5555,"media_type":"tv","name":"La serie de Deadpool","original_name":"Deadpool Show","first_air_date":"2019-01-01","poster_path":"/ds.jpg","popularity":10},
          {"id":7777,"media_type":"movie","title":"Una cosa distinta","original_title":"Something Else","release_date":"2010-01-01","popularity":999}
        ]}
    """.trimIndent()
    private val movies = """
        {"page":1,"results":[
          {"id":293660,"title":"Deadpool","original_title":"Deadpool","release_date":"2016-02-09","poster_path":"/d1.jpg","popularity":120.0},
          {"id":383498,"title":"Deadpool 2","original_title":"Deadpool 2","release_date":"2018-05-10","poster_path":"/d2.jpg","popularity":150.0}
        ]}
    """.trimIndent()
    private val shows = """{"page":1,"results":[{"id":5555,"name":"La serie de Deadpool","original_name":"Deadpool Show","first_air_date":"2019-01-01","popularity":10}]}"""
    private val show = """{"id":5555,"name":"La serie de Deadpool","seasons":[{"season_number":1}]}"""
    private val season1 = """{"season_number":1,"episodes":[{"episode_number":1,"name":"Uno","air_date":"2019-01-01"}]}"""

    private fun tmdb(status: Int = 200) = NuvioRoutingHost(
        mapOf(
            "/3/search/multi?" to NuvioRoutingHost.Reply(status, multi),
            "/3/search/movie?" to NuvioRoutingHost.Reply(status, movies),
            "/3/search/tv?" to NuvioRoutingHost.Reply(status, shows),
            "/3/tv/5555/season/1?" to NuvioRoutingHost.Reply(body = season1),
            "/3/tv/5555?" to NuvioRoutingHost.Reply(body = show),
            "/3/movie/293660?" to NuvioRoutingHost.Reply(body = """{"id":293660,"title":"Deadpool","release_date":"2016-02-09"}"""),
        ),
    )

    private fun <T> withRuntime(types: List<String>, host: PluginHost, block: suspend (PluginRuntime) -> T): T = runBlocking {
        val script = NuvioPluginConverter.convert(scraper(types), echoSource, repoSlug = "owner/repo").script
        val runtime = PluginRuntime.open("textsearch", script, host, PluginEnv(appVersion = "1.0"))
        try { block(runtime) } finally { runtime.close() }
    }

    /** What the TV's typed search sends: `freeTextCard` is always a "movie" card with no ids. */
    private fun typed(q: String, type: String = "movie", tmdbId: Int = 0) =
        PluginContentSource.queryJson(GatewaySearchQuery(q = q, type = type, season = 0, episode = 0, tmdbId = tmdbId))

    private fun search(types: List<String>, host: NuvioRoutingHost, query: String): JSONArray =
        withRuntime(types, host) { JSONArray(it.call("search", query, 15_000)) }

    @Test fun `a typed query without a tmdbId answers TMDB's matches, best match first, with artwork`() {
        val host = tmdb()
        val items = search(listOf("movie", "tv"), host, typed("deadpool"))
        val titles = (0 until items.length()).map { items.getJSONObject(it).getString("title") }
        // The person and the match sharing no word are not "Deadpool"; ties go by popularity.
        assertEquals(listOf("Deadpool y Wolverine", "Deadpool", "La serie de Deadpool", "Una cosa distinta"), titles)
        val dw = items.getJSONObject(0)
        assertEquals("movie", dw.getString("kind"))
        assertEquals("533535-movie-0-0", dw.getString("id"))
        assertEquals(533535, dw.getJSONObject("ids").getInt("tmdb"))
        assertEquals("2024", dw.getString("year"))
        assertEquals("https://image.tmdb.org/t/p/w500/dw.jpg", dw.getString("poster"))
        assertEquals("Wade.", dw.getString("overview"))
        val series = items.getJSONObject(2)
        assertEquals("series", series.getString("kind"))
        assertEquals("5555-series", series.getString("id"))
        // Exactly one request, to TMDB's multi search, in es-MX, never getStreams.
        val url = host.urls().single()
        assertTrue(url, url.startsWith("https://api.themoviedb.org/3/search/multi?"))
        assertTrue(url, "query=deadpool" in url && "language=es-MX" in url)
        assertTrue(host.logs.none { "getStreams called" in it })
        // What Kino keeps of it: every item passes its own output checks.
        val page = PluginOutput.page(items.toString(), 100, allowSeries = true, allowNext = false, hosts = EffectiveHosts(emptyList()))
        assertEquals(4, page.items.size)
    }

    @Test fun `the only key a TMDB search carries is the sealed marker`() {
        val host = tmdb()
        search(listOf("movie", "tv"), host, typed("deadpool"))
        val url = host.urls().single()
        val apiKey = Regex("[?&]api_key=([^&]*)").find(url)!!.groupValues[1]
        assertEquals(NuvioPluginConverter.TMDB_KEY_MARKER, apiKey)
    }

    @Test fun `a movie-only scraper asks the movie search and never answers a series`() {
        val host = tmdb()
        val items = search(listOf("movie"), host, typed("deadpool"))
        assertTrue(host.urls().single().startsWith("https://api.themoviedb.org/3/search/movie?"))
        val kinds = (0 until items.length()).map { items.getJSONObject(it).getString("kind") }
        assertEquals(listOf("movie", "movie"), kinds)
        // Popularity breaks the similarity tie: Deadpool 2 (150) before Deadpool (120).
        assertEquals("383498-movie-0-0", items.getJSONObject(0).getString("id"))
    }

    @Test fun `a tv-only scraper asks the tv search, and a series query on a movie-only scraper answers nothing`() {
        val tvHost = tmdb()
        val items = search(listOf("tv"), tvHost, typed("deadpool"))
        assertTrue(tvHost.urls().single().startsWith("https://api.themoviedb.org/3/search/tv?"))
        assertEquals(listOf("series"), (0 until items.length()).map { items.getJSONObject(it).getString("kind") })

        val movieHost = tmdb()
        assertEquals(0, search(listOf("movie"), movieHost, typed("deadpool", type = "tv")).length())
        assertTrue("nothing to ask TMDB for", movieHost.urls().isEmpty())
    }

    @Test fun `a series query narrows a full scraper to the tv search`() {
        val host = tmdb()
        search(listOf("movie", "tv"), host, typed("deadpool", type = "tv"))
        assertTrue(host.urls().single().startsWith("https://api.themoviedb.org/3/search/tv?"))
    }

    @Test fun `a TMDB error or a blank query answers an empty list, never an error`() {
        assertEquals(0, search(listOf("movie", "tv"), tmdb(status = 500), typed("deadpool")).length())
        val blank = tmdb()
        assertEquals(0, search(listOf("movie", "tv"), blank, typed("   ")).length())
        assertTrue(blank.urls().isEmpty())
    }

    @Test fun `with a tmdbId the search is the old one-title lookup`() {
        val host = tmdb()
        val items = search(listOf("movie", "tv"), host, typed("Deadpool", tmdbId = 293660))
        assertEquals(1, items.length())
        assertEquals("293660-movie-0-0", items.getJSONObject(0).getString("id"))
        assertEquals(listOf("https://api.themoviedb.org/3/movie/293660?"), host.urls().map { it.substringBefore("?") + "?" })
        assertFalse(host.urls().any { "/search/" in it })
    }

    @Test fun `a typed match's ref resolves through getStreams, and a series match lists its episodes`() {
        val host = tmdb()
        val types = listOf("movie", "tv")
        val items = search(types, host, typed("deadpool"))
        val movieRef = items.getJSONObject(1).getString("ref")
        val url = withRuntime(types, host) { JSONObject(it.call("resolve", JSONObject.quote(movieRef), 5_000)).getString("url") }
        assertEquals("https://cdn.echo.example/string/293660/movie//", url)

        val seriesRef = items.getJSONObject(2).getString("ref")
        val episodes = withRuntime(types, host) { PluginOutput.episodes(it.call("episodes", JSONObject.quote(seriesRef), 10_000)) }
        assertEquals(listOf(1 to 1), episodes.episodes.map { it.season to it.number })
        val episodeUrl = withRuntime(types, host) {
            JSONObject(it.call("resolve", JSONObject.quote(episodes.episodes.single().ref), 5_000)).getString("url")
        }
        assertEquals("https://cdn.echo.example/string/5555/tv/1/1", episodeUrl)
    }
}
