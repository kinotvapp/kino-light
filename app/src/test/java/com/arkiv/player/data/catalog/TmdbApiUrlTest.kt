package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbApiUrlTest {
    @Test fun `category paths by type`() {
        assertEquals("/movie/popular", tmdbCategoryPath("movie", TmdbCategory.POPULAR))
        assertEquals("/movie/top_rated", tmdbCategoryPath("movie", TmdbCategory.TOP_RATED))
        assertEquals("/movie/now_playing", tmdbCategoryPath("movie", TmdbCategory.NOW_PLAYING))
        assertEquals("/movie/upcoming", tmdbCategoryPath("movie", TmdbCategory.UPCOMING))
        assertEquals("/tv/on_the_air", tmdbCategoryPath("tv", TmdbCategory.NOW_PLAYING))
        assertEquals("/tv/airing_today", tmdbCategoryPath("tv", TmdbCategory.UPCOMING))
        assertEquals("/trending/movie/week", tmdbCategoryPath("movie", TmdbCategory.TRENDING))
        assertEquals("/trending/tv/week", tmdbCategoryPath("tv", TmdbCategory.TRENDING))
    }
}
