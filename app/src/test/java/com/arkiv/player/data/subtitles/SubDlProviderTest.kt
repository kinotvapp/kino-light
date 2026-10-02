package com.arkiv.player.data.subtitles

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** SubDL's request, its answers (episodes, packs, a bad key) and the zip it serves. */
class SubDlProviderTest {

    private lateinit var server: MockWebServer
    private val auth = ProviderAuth("subdl-test-key")

    private fun provider() = SubDlProvider(
        OkHttpClient(),
        server.url("/api/v1").toString().removeSuffix("/"),
        server.url("/").toString().removeSuffix("/"),
    )

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `the search names the title by its strongest id, type, episode and SubDL's language codes`() {
        assertEquals(
            "b/subtitles?api_key=k&imdb_id=tt0133093&type=movie&languages=ES,EN&subs_per_page=30",
            SubDlProvider.searchUrl("b", SubtitleQuery(false, imdbId = "tt0133093", tmdbId = 603, languages = listOf("es", "en")), "k"),
        )
        assertEquals(
            "b/subtitles?api_key=k&tmdb_id=1399&type=tv&season_number=2&episode_number=3&languages=ES&subs_per_page=30",
            SubDlProvider.searchUrl("b", SubtitleQuery(true, tmdbId = 1399, season = 2, episode = 3, languages = listOf("es")), "k"),
        )
        assertEquals(
            "b/subtitles?api_key=k&film_name=El%20Padrino&year=1972&type=movie&languages=ES,BR_PT&subs_per_page=30",
            SubDlProvider.searchUrl("b", SubtitleQuery(false, title = "El Padrino", year = 1972, languages = listOf("es", "pt-br")), "k"),
        )
    }

    @Test
    fun `an episode search keeps that episode and season packs, never another episode`() {
        val q = SubtitleQuery(true, tmdbId = 1, season = 1, episode = 2)
        val r = SubDlProvider.parseSearch(SEARCH_JSON, q)
        assertEquals(listOf("/subtitle/1-2.zip", "/subtitle/1-pack.zip"), r.map { it.ref })
        assertEquals("es", r[0].language)
        assertEquals("Show.S01E02.WEB", r[0].release)
        // A movie search keeps them all; `lang` names it when `language` is missing.
        val all = SubDlProvider.parseSearch(SEARCH_JSON, SubtitleQuery(false, tmdbId = 1))
        assertEquals(3, all.size)
        assertEquals("en", all[2].language)
    }

    @Test
    fun `a status false naming the key is a bad key, an unknown title is no results`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":false,"error":"Invalid API key"}"""))
        assertEquals(SubtitleResult.Failed(SubtitleFailure.BAD_KEY), provider().search(SubtitleQuery(false, imdbId = "tt0133093"), auth))
        assertEquals("subdl-test-key", server.takeRequest().requestUrl!!.queryParameter("api_key"))
        server.enqueue(MockResponse().setBody("""{"status":false,"error":"can't find movie or tv"}"""))
        assertEquals(SubtitleResult.Ok(emptyList<OnlineSubtitle>()), provider().search(SubtitleQuery(false, imdbId = "tt0133093"), auth))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(SubtitleResult.Failed(SubtitleFailure.BAD_KEY), provider().test(auth))
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(SubtitleResult.Failed(SubtitleFailure.RATE_LIMITED), provider().search(SubtitleQuery(false, imdbId = "tt0133093"), auth))
    }

    @Test
    fun `download fetches the zip from the download host and takes the episode's srt out`() = runBlocking {
        val zip = zipOf("Show.S01E01.srt" to "1\n00:00:01,000 --> 00:00:02,000\nUno\n", "Show.S01E02.srt" to SRT2, "readme.nfo" to "x")
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val sub = OnlineSubtitle(SubtitleProviderId.SUBDL, "/subtitle/1-pack.zip", "es", "")
        val r = provider().download(sub, auth, SubtitleQuery(true, season = 1, episode = 2)) as SubtitleResult.Ok
        assertEquals("/subtitle/1-pack.zip", server.takeRequest().path)
        assertEquals(SRT2, String(r.value))
    }

    @Test
    fun `a download path off SubDL's host is refused`() {
        assertEquals("https://dl.subdl.com/subtitle/1.zip", SubDlProvider.downloadUrl(SubDlProvider.DOWNLOAD_BASE, "/subtitle/1.zip"))
        assertNull(SubDlProvider.downloadUrl(SubDlProvider.DOWNLOAD_BASE, "https://evil.example.com/x.zip"))
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private companion object {
        const val SRT2 = "1\n00:00:01,000 --> 00:00:02,000\nDos\n"
        val SEARCH_JSON = """
            {"status":true,"results":[{"sd_id":1,"type":"tv","name":"Show","imdb_id":"tt1","tmdb_id":1}],
             "subtitles":[
               {"release_name":"Show.S01E02.WEB","name":"SUBDL::show-s01e02.zip","lang":"spanish","language":"ES","url":"/subtitle/1-2.zip","season":1,"episode":2,"hi":false},
               {"release_name":"Show.S01E03.WEB","lang":"spanish","language":"ES","url":"/subtitle/1-3.zip","season":1,"episode":3},
               {"release_name":"Show.S01.Pack","lang":"english","url":"/subtitle/1-pack.zip","season":1,"episode":0,"full_season":true}
             ]}
        """.trimIndent()
    }
}
