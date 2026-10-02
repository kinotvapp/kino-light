package com.arkiv.player.data.subtitles

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** OpenSubtitles' requests as the API wants them, its answers read, and each failure named. */
class OpenSubtitlesProviderTest {

    private lateinit var server: MockWebServer
    private val base get() = server.url("/api/v1").toString().removeSuffix("/")
    private val auth = ProviderAuth("test-key-not-real")

    private fun provider(client: OkHttpClient = OkHttpClient()) = OpenSubtitlesProvider("Kino v0.9.46", client, base)

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `a movie is searched by its bare IMDb number, params lowercase and sorted`() {
        val url = OpenSubtitlesProvider.searchUrl("https://x/api/v1", SubtitleQuery(false, imdbId = "tt0133093", tmdbId = 603, languages = listOf("es", "en")))
        assertEquals("https://x/api/v1/subtitles?imdb_id=133093&languages=en,es&type=movie", url)
    }

    @Test
    fun `an episode is searched by the series' id with season and episode`() {
        val byImdb = OpenSubtitlesProvider.searchUrl("b", SubtitleQuery(true, imdbId = "tt0944947", season = 2, episode = 5, languages = listOf("es")))
        assertEquals("b/subtitles?episode_number=5&languages=es&parent_imdb_id=944947&season_number=2&type=episode", byImdb)
        val byTmdb = OpenSubtitlesProvider.searchUrl("b", SubtitleQuery(true, tmdbId = 1399, season = 1, episode = 1, languages = listOf("es")))
        assertEquals("b/subtitles?episode_number=1&languages=es&parent_tmdb_id=1399&season_number=1&type=episode", byTmdb)
    }

    @Test
    fun `with no id the title goes as query, lowercase, with the year`() {
        val url = OpenSubtitlesProvider.searchUrl("b", SubtitleQuery(false, title = "El Padrino", year = 1972, languages = listOf("es")))
        assertEquals("b/subtitles?languages=es&query=el%20padrino&type=movie&year=1972", url)
    }

    @Test
    fun `search sends the key and user agent and reads every result`() = runBlocking {
        server.enqueue(MockResponse().setBody(SEARCH_JSON))
        val r = provider().search(SubtitleQuery(false, imdbId = "tt0133093"), auth) as SubtitleResult.Ok
        val req = server.takeRequest()
        assertEquals("test-key-not-real", req.getHeader("Api-Key"))
        assertEquals("Kino v0.9.46", req.getHeader("User-Agent"))
        assertEquals(2, r.value.size)
        val first = r.value[0]
        assertEquals("1234567", first.ref)
        assertEquals("es", first.language)
        assertEquals("The.Matrix.1999.1080p.BluRay", first.release)
        assertEquals(4200, first.downloads)
        // No release: the file name names it.
        assertEquals("matrix.en.srt", r.value[1].release)
        assertTrue(r.value[1].hearingImpaired)
    }

    @Test
    fun `a rejected key and a full quota are told apart`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(SubtitleResult.Failed(SubtitleFailure.BAD_KEY), provider().search(SubtitleQuery(false, imdbId = "tt0133093"), auth))
        server.enqueue(MockResponse().setResponseCode(406).setBody("""{"message":"You have downloaded your allowed 5 subtitles for 24h"}"""))
        val sub = OnlineSubtitle(SubtitleProviderId.OPENSUBTITLES, "1234567", "es", "")
        assertEquals(SubtitleResult.Failed(SubtitleFailure.QUOTA), provider().download(sub, auth, SubtitleQuery(false)))
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(SubtitleResult.Failed(SubtitleFailure.RATE_LIMITED), provider().search(SubtitleQuery(false, imdbId = "tt0133093"), auth))
        assertEquals("Límite diario de descargas alcanzado", SubtitleFailure.QUOTA.message)
    }

    @Test
    fun `a service that never answers is a timeout`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val slow = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        assertEquals(SubtitleResult.Failed(SubtitleFailure.TIMEOUT), provider(slow).search(SubtitleQuery(false, imdbId = "tt0133093"), auth))
    }

    @Test
    fun `download posts the file id, follows the link and returns the file`() = runBlocking {
        val link = server.url("/files/sub.srt").toString()
        server.enqueue(MockResponse().setBody("""{"link":"$link","file_name":"sub.srt","requests":1,"remaining":4,"message":"","reset_time":"23 hours"}"""))
        server.enqueue(MockResponse().setBody(SRT))
        val sub = OnlineSubtitle(SubtitleProviderId.OPENSUBTITLES, "1234567", "es", "")
        val r = provider().download(sub, auth, SubtitleQuery(false)) as SubtitleResult.Ok
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("""{"file_id":1234567}""", post.body.readUtf8())
        assertNull(post.getHeader("Authorization"))
        val get = server.takeRequest()
        assertNull("the CDN never gets the key", get.getHeader("Api-Key"))
        assertEquals(SRT, String(r.value))
    }

    @Test
    fun `with an account it logs in once and downloads with the token`() = runBlocking {
        val link = server.url("/files/sub.srt").toString()
        server.enqueue(MockResponse().setBody("""{"token":"jwt-1","base_url":"evil.example.com","user":{}}"""))
        server.enqueue(MockResponse().setBody("""{"link":"$link"}"""))
        server.enqueue(MockResponse().setBody(SRT))
        server.enqueue(MockResponse().setBody("""{"link":"$link"}"""))
        server.enqueue(MockResponse().setBody(SRT))
        val p = provider()
        val withAccount = ProviderAuth("k", "ana", "secreto")
        val sub = OnlineSubtitle(SubtitleProviderId.OPENSUBTITLES, "1", "es", "")
        assertTrue(p.download(sub, withAccount, SubtitleQuery(false)) is SubtitleResult.Ok)
        assertTrue(p.download(sub, withAccount, SubtitleQuery(false)) is SubtitleResult.Ok)
        assertTrue(server.takeRequest().path!!.endsWith("/login"))
        val dl = server.takeRequest()
        // A base_url off opensubtitles.com is ignored: the token never leaves for another host.
        assertTrue(dl.path!!.endsWith("/api/v1/download"))
        assertEquals("Bearer jwt-1", dl.getHeader("Authorization"))
        server.takeRequest()
        assertTrue("logged in only once", server.takeRequest().path!!.endsWith("/download"))
    }

    @Test
    fun `answers that are not what they should be parse to nothing`() {
        assertEquals(emptyList<OnlineSubtitle>(), OpenSubtitlesProvider.parseSearch("<html>"))
        assertNull(OpenSubtitlesProvider.parseDownloadLink("""{"link":"javascript:x"}"""))
        assertNull(OpenSubtitlesProvider.parseLogin("""{"status":401}"""))
        assertEquals("tok" to "vip-api.opensubtitles.com", OpenSubtitlesProvider.parseLogin("""{"token":"tok","base_url":"vip-api.opensubtitles.com"}"""))
    }

    private companion object {
        const val SRT = "1\n00:00:01,000 --> 00:00:02,000\nHola\n"
        val SEARCH_JSON = """
            {"total_count":2,"data":[
              {"id":"1","type":"subtitle","attributes":{"language":"es","download_count":4200,"hearing_impaired":false,
                "release":"The.Matrix.1999.1080p.BluRay","files":[{"file_id":1234567,"file_name":"matrix.es.srt"}]}},
              {"id":"2","type":"subtitle","attributes":{"language":"en","download_count":10,"hearing_impaired":true,
                "release":null,"files":[{"file_id":7654321,"file_name":"matrix.en.srt"}]}},
              {"id":"3","type":"subtitle","attributes":{"language":"en","files":[]}}
            ]}
        """.trimIndent()
    }
}
