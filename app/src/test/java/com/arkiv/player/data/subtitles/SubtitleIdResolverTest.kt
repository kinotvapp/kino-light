package com.arkiv.player.data.subtitles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The ids a search uses: what the title has, then TMDB's, then the title alone. */
class SubtitleIdResolverTest {

    private val imdbCalls = mutableListOf<Pair<String, Int>>()
    private val searchCalls = mutableListOf<Triple<String, String, Int?>>()

    private fun resolver(imdb: String? = "tt0133093", tmdb: Int? = 603, fail: Boolean = false) = SubtitleIdResolver(
        imdbOf = { type, id -> imdbCalls += type to id; if (fail) error("tmdb down") else imdb },
        searchTmdb = { type, title, year -> searchCalls += Triple(type, title, year); if (fail) error("tmdb down") else tmdb },
    )

    @Test
    fun `ids the title carries are used as they are, no TMDB call`() = runBlocking {
        val q = resolver().resolve(SubtitleSubject("movie", tmdbId = 603, imdbId = "tt0133093", title = "Matrix"), listOf("es"))
        assertEquals("tt0133093", q.imdbId)
        assertEquals(603, q.tmdbId)
        assertEquals(emptyList<Any>(), imdbCalls + searchCalls)
    }

    @Test
    fun `a TMDB id alone gets its IMDb from TMDB, as a series for an episode`() = runBlocking {
        val q = resolver(imdb = "tt0944947").resolve(SubtitleSubject("tv", tmdbId = 1399, title = "GoT", season = 1, episode = 2), listOf("es"))
        assertEquals("tt0944947", q.imdbId)
        assertEquals(listOf("tv" to 1399), imdbCalls)
        assertEquals(true, q.isEpisode)
        assertEquals(1, q.season)
        assertEquals(2, q.episode)
    }

    @Test
    fun `a Xuper title with no id is found by title and year, then its IMDb`() = runBlocking {
        val q = resolver().resolve(SubtitleSubject("movie", title = "Matrix (1999)"), listOf("es"))
        assertEquals(listOf(Triple("movie", "Matrix", 1999)), searchCalls)
        assertEquals(603, q.tmdbId)
        assertEquals("tt0133093", q.imdbId)
        assertEquals("Matrix", q.title)
        assertEquals(1999, q.year)
    }

    @Test
    fun `when TMDB fails the title is still searchable, and the work is asked once`() = runBlocking {
        val r = resolver(fail = true)
        val q = r.resolve(SubtitleSubject("tv", title = "Serie X", season = 1, episode = 1), listOf("es"))
        assertNull(q.imdbId)
        assertNull(q.tmdbId)
        assertEquals("Serie X", q.title)
        // Another episode of the same series: cached, no new TMDB call.
        r.resolve(SubtitleSubject("tv", title = "Serie X", season = 1, episode = 2), listOf("es"))
        assertEquals(1, searchCalls.size)
    }

    @Test
    fun `nothing at all is an empty query`() = runBlocking {
        assertEquals(true, resolver(imdb = null, tmdb = null).resolve(SubtitleSubject("movie"), listOf("es")).isEmpty)
    }
}
