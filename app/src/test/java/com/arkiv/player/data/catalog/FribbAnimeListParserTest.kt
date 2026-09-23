package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FribbAnimeListParserTest {

    // Real dataset shapes: imdb_id as an array, themoviedb_id as an object {tv:..},
    // season/episode_offset as an object {tvdb:..}. Some entries don't carry season/offset.
    private val json = """
        [
          {"anilist_id":21,"mal_id":21,"tvdb_id":81797,"imdb_id":["tt0388629"],
           "themoviedb_id":{"tv":37854},"simkl_id":38636,"type":"tv"},
          {"anilist_id":110277,"tvdb_id":267440,"imdb_id":["tt2560140"],
           "themoviedb_id":{"tv":1429},"season":{"tvdb":4,"tmdb":4}},
          {"anilist_id":821,"tvdb_id":70900,"season":{"tvdb":0},"episode_offset":{"tvdb":2},"type":"OVA"},
          {"mal_id":999,"tvdb_id":123}
        ]
    """.trimIndent()

    @Test
    fun `indexes by anilist_id and skips entries with no anilist`() {
        val map = FribbAnimeListParser.parse(json)
        assertEquals(3, map.size)          // the 4th one has no anilist_id
        assertNull(map[999L])
    }

    @Test
    fun `parses cross-ids with an imdb array and a tmdb tv object`() {
        val m = FribbAnimeListParser.parse(json)[21L]!!
        assertEquals(81797L, m.tvdbId)
        assertEquals("tt0388629", m.imdbId)
        assertEquals(37854, m.tmdbId)
        assertEquals(38636L, m.simklId)
        assertNull(m.tvdbSeason)           // no season
    }

    @Test
    fun `parses tvdb season and offset when present`() {
        val aot = FribbAnimeListParser.parse(json)[110277L]!!
        assertEquals(4, aot.tvdbSeason)
        assertNull(aot.episodeOffset)
        val ova = FribbAnimeListParser.parse(json)[821L]!!
        assertEquals(2, ova.episodeOffset)
        assertEquals(0, ova.tvdbSeason)
    }

    @Test
    fun `invalid json returns an empty map`() {
        assertTrue(FribbAnimeListParser.parse("not json").isEmpty())
    }
}
