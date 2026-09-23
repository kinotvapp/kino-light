package com.arkiv.player.data.ditu

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DituSourceTest {

    private fun source(fake: FakeDituClient, tmdb: com.arkiv.player.data.catalog.TmdbApi? = null) =
        DituSource(DituCatalog(fake), DituEpisodes(fake), DituResolve(fake), tmdb)

    /** With the clock in the test's hand: the 6h expiry is tested without waiting 6h. */
    private fun withClock(fake: FakeDituClient, clock: () -> Long) =
        DituSource(DituCatalog(fake), DituEpisodes(fake), DituResolve(fake), nowMs = clock)

    private fun FakeDituClient.catalogRequests() = calls.count { it.first == DituCatalog.TRAY }

    private val sixHours = 6 * 60 * 60 * 1000L

    private fun withOneTitle() = FakeDituClient().also {
        it.respond(DituCatalog.TRAY, """
        {"resultObj":{"containers":[{"id":"1","metadata":{"title":"Rigo","contentType":"BUNDLE"}}]}}
        """)
    }

    @Test fun `within 6h the catalog isn't requested again`() = runTest {
        val fake = withOneTitle()
        var now = 1_000L
        val f = withClock(fake) { now }

        f.fullCatalog()
        now += sixHours - 1
        val second = f.fullCatalog()

        assertEquals(1, fake.catalogRequests())
        assertEquals(listOf("Rigo"), second.map { it.title })
    }

    @Test fun `past 6h the catalog gets requested again`() = runTest {
        val fake = withOneTitle()
        var now = 1_000L
        val f = withClock(fake) { now }

        f.fullCatalog()
        now += sixHours
        f.fullCatalog()

        assertEquals(2, fake.catalogRequests())
    }

    @Test fun `force requests even if it hasn't expired`() = runTest {
        val fake = withOneTitle()
        val f = withClock(fake) { 1_000L }

        f.fullCatalog()
        f.fullCatalog(force = true)

        assertEquals(2, fake.catalogRequests())
    }

    @Test fun `recognizes its own refs and not others'`() {
        val f = source(FakeDituClient())
        assertTrue(f.recognizes("ditu1:VOD:42"))
        assertFalse(f.recognizes("magis1:movie:0:C42"))
        assertFalse(f.recognizes(""))
    }

    @Test fun `search emits start, results and end`() = runTest {
        val fake = FakeDituClient()
        fake.respond(DituCatalog.TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"Rigo","contentType":"BUNDLE","pictureUrl":"p"}},
          {"id":"2","metadata":{"title":"Peli","contentType":"VOD","contentSubtype":"MOVIE"}}
        ]}}
        """)

        val events = source(fake).search(GatewaySearchQuery(q = "rigo")).toList()

        assertTrue(events.first() is SearchEvent.SourceStart)
        val results = events.filterIsInstance<SearchEvent.ResultEvent>()
        assertEquals(listOf("Rigo", "Peli"), results.map { it.item.title })
        assertEquals(listOf("series", "movie"), results.map { it.item.kind })
        assertEquals(listOf("ditu1:BUNDLE:1", "ditu1:VOD:2"), results.map { it.item.ref })
        assertEquals("ditu", results.first().item.source)
        assertTrue(events.any { it is SearchEvent.SourceDone })
        assertTrue(events.last() is SearchEvent.Done)
    }

    /** A source that goes down emits its error and finishes: it can't leave the Flow hanging. */
    @Test fun `if Caracol fails, search emits SourceError and Done`() = runTest {
        val fake = FakeDituClient()
        fake.failure = DituException("Caracol no responde")

        val events = source(fake).search(GatewaySearchQuery(q = "x")).toList()

        val error = events.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("ditu", error.source)
        assertTrue(error.error.contains("Caracol"))
        assertTrue(events.last() is SearchEvent.Done)
    }

    @Test fun `resolve translates the ref and returns the playable with DRM`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/VOD/42", """
        {"resultObj":{"containers":[{"assets":[{"assetType":"MASTER","assetId":7}]}]}}
        """)
        fake.respond("CONTENT/USERDATA/VOD/42", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.respond("CONTENT/VIDEOURL/VOD/42/7", """{"resultObj":{"src":"https://cdn/x.mpd"}}""")
        fake.token = "t1"

        val play = source(fake).resolve("ditu1:VOD:42")

        assertEquals("https://cdn/x.mpd", play.url)
        assertTrue(play.drmLicenseUrl.endsWith("/CONTENT/LICENSE"))
        assertEquals("playback_token=t1", play.drmLicenseHeaders["Cookie"])
    }

    @Test fun `a ref that isn't Caracol's is a GatewayException`() = runTest {
        val e = runCatching { source(FakeDituClient()).resolve("magis1:movie:0:C1") }.exceptionOrNull()
        assertTrue(e is com.arkiv.player.data.gateway.GatewayException)
    }

    @Test fun `chapters arrive with their ref and the series with its images`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", """
        {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},"containers":[
          {"id":"e1","metadata":{"episodeNumber":1,"episodeTitle":"Uno","season":1},
           "assets":[{"assetType":"MASTER","assetId":1}]}
        ]}]}}
        """)

        val (eps, series) = source(fake).episodesWithSeries("ditu1:BUNDLE:99")

        assertEquals(1, eps.single().number)
        assertEquals("Uno", eps.single().title)
        assertEquals("ditu1:VOD:e1", eps.single().ref)
        assertEquals("Rigo", series!!.title)
        assertTrue(series.posterUrl.endsWith("portrait-thin-promotional-tablet.jpg"))
        assertTrue(series.backdropUrl.endsWith("landscape-regular-clean-tablet.jpg"))
        // With no TMDB wired in there's no id: the block still travels with what Caracol does know.
        assertEquals(0, series.tmdbId)
    }

    /**
     * In a GROUP_OF_BUNDLES each season can carry its own chapter 1. The season has to travel on
     * each chapter: `GatewaySeries.seasonNumber` is a single value for the whole list, and without
     * this whoever saves to the library can't tell season 1's chapter 1 apart from season 2's.
     */
    @Test fun `in a group each chapter carries its own season`() = runTest {
        val fake = FakeDituClient()
        fake.respond(DituCatalog.TRAY, """{"resultObj":{"containers":[{"id":"b1"},{"id":"b2"}]}}""")
        for ((bundle, cap) in listOf("b1" to "e1", "b2" to "e2")) {
            fake.respond("CONTENT/DETAIL/BUNDLE/$bundle", """
            {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},"containers":[
              {"id":"$cap","metadata":{"episodeNumber":1,"episodeTitle":"Uno","season":1},
               "assets":[{"assetType":"MASTER","assetId":1}]}
            ]}]}}
            """)
        }

        val (eps, _) = source(fake).episodesWithSeries("ditu1:GROUP_OF_BUNDLES:g9")

        assertEquals(listOf(1, 1), eps.map { it.number })
        assertEquals(listOf(1, 2), eps.map { it.season })
        assertEquals(listOf("ditu1:VOD:e1", "ditu1:VOD:e2"), eps.map { it.ref })
    }

    /**
     * `TmdbApi` with a real server that goes down BEFORE the call (same pattern as
     * `MagisFuenteTest`'s "if TMDB goes down, the chapters still come out"): unlike `tmdb = null`,
     * here crossing against TMDB is actually attempted and the call really fails — it's
     * `episodesWithSeries`'s central guarantee (that a downed TMDB doesn't cost the chapters) and
     * until now no test exercised it, because they all used `tmdb = null`.
     */
    @Test fun `if TMDB goes down, the chapters still come out`() = runTest {
        val server = MockWebServer().also { it.start() }
        val tmdb = TmdbApi(apiKey = "x", baseUrl = server.url("/3").toString().trimEnd('/'), client = OkHttpClient())
        server.shutdown()

        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", """
        {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},"containers":[
          {"id":"e1","metadata":{"episodeNumber":1,"episodeTitle":"Uno","season":1},
           "assets":[{"assetType":"MASTER","assetId":1}]}
        ]}]}}
        """)

        val (eps, series) = source(fake, tmdb).episodesWithSeries("ditu1:BUNDLE:99")

        assertEquals(1, eps.size)
        assertEquals("Uno", eps.single().title)
        assertEquals("Rigo", series!!.title)
        assertTrue(series.posterUrl.endsWith("portrait-thin-promotional-tablet.jpg"))
        // A downed TMDB contributes no id: the title and images are Caracol's, which did answer.
        assertEquals(0, series.tmdbId)
    }

    /** A fake TMDB that answers [json] on the first search. */
    private fun tmdbAnswering(json: String): Pair<MockWebServer, TmdbApi> {
        val server = MockWebServer().also { it.enqueue(MockResponse().setBody(json)); it.start() }
        val tmdb = TmdbApi(apiKey = "x", baseUrl = server.url("/3").toString().trimEnd('/'), client = OkHttpClient())
        return server to tmdb
    }

    private fun seriesFetch(title: String) = FakeDituClient().also {
        it.respond("CONTENT/DETAIL/BUNDLE/99", """
        {"resultObj":{"containers":[{"metadata":{"title":"$title","pictureUrl":"pic"},"containers":[
          {"id":"e1","metadata":{"episodeNumber":1,"episodeTitle":"Uno","season":1},
           "assets":[{"assetType":"MASTER","assetId":1}]}
        ]}]}}
        """)
    }

    /** The title matches even with different case, accents and punctuation: TMDB's id is accepted. */
    @Test fun `if TMDB's title matches, the series takes its id`() = runTest {
        val (server, tmdb) = tmdbAnswering(
            """{"results":[{"id":77,"name":"Café con aroma de mujer","original_name":"Café con aroma de mujer"}]}""",
        )
        try {
            val (_, series) = source(seriesFetch("¡CAFE, con aroma de Mujer!"), tmdb).episodesWithSeries("ditu1:BUNDLE:99")

            assertEquals(77, series!!.tmdbId)
        } finally {
            server.shutdown()
        }
    }

    /**
     * TMDB's first result is ANOTHER series: accepting it would give it its `tmdbId`, which is
     * what the library groups by, and it would merge the two. With no match there's no id and the
     * title is Caracol's.
     */
    @Test fun `if TMDB returns another series, there's no id and the title is Caracol's`() = runTest {
        val (server, tmdb) = tmdbAnswering(
            """{"results":[{"id":55,"name":"Rigoberta","original_name":"Rigoberta","poster_path":"/otra.jpg"}]}""",
        )
        try {
            val (_, series) = source(seriesFetch("Rigo"), tmdb).episodesWithSeries("ditu1:BUNDLE:99")

            assertEquals(0, series!!.tmdbId)
            assertEquals("Rigo", series.title)
            assertTrue(series.posterUrl.endsWith("portrait-thin-promotional-tablet.jpg"))
        } finally {
            server.shutdown()
        }
    }
}
