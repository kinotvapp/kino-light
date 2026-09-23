package com.arkiv.player.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListGenreTest {
    @Test fun `query includes genre_in only when there's a genre`() {
        val withGenre = buildAnimeBrowseQuery("TRENDING_DESC", hasSearch = false, hasGenre = true)
        assertTrue(withGenre.contains("genre_in"))
        assertTrue(withGenre.contains("\$genre"))
        val without = buildAnimeBrowseQuery("TRENDING_DESC", hasSearch = false, hasGenre = false)
        assertFalse(without.contains("genre_in"))
    }
}
