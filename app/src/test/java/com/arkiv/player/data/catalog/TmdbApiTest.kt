package com.arkiv.player.data.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Covers how [TmdbApi] authenticates now that it talks DIRECTLY to TMDB (sub-project 2A): the key
 * goes as a query parameter and no session header travels anymore, because there's no gateway on
 * the other end to authenticate against. Not meant to be a complete suite for [TmdbApi].
 */
class TmdbApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun api() = TmdbApi(
        apiKey = "test-key",
        baseUrl = server.url("/3").toString().trimEnd('/'),
        client = OkHttpClient(),
    )

    @Test
    fun `the key and the language go in the query`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))

        api().browse("movie", 1)

        val request = server.takeRequest()
        assertEquals("test-key", request.requestUrl?.queryParameter("api_key"))
        assertEquals("es-MX", request.requestUrl?.queryParameter("language"))
        assertEquals("/3/movie/popular", request.requestUrl?.encodedPath)
    }

    @Test
    fun `no gateway session header travels`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))

        api().browse("movie", 1)

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("X-Arkiv-Device"))
        assertNull(request.getHeader("X-Arkiv-Key"))
    }

    @Test
    fun `search sends the key and does not ask for adult content`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))

        api().search("movie", "batman")

        val url = server.takeRequest().requestUrl!!
        assertEquals("test-key", url.queryParameter("api_key"))
        assertEquals("batman", url.queryParameter("query"))
        assertEquals("false", url.queryParameter("include_adult"))
    }

    @Test
    fun `by default it points at TMDB, not at any server of our own`() {
        // The base is fixed at build time and isn't configurable from Settings, same as the key.
        assertEquals("https://api.themoviedb.org/3", TmdbApi.BASE_TMDB)
    }
}
