package com.arkiv.player.ui.search

import com.arkiv.player.data.catalog.TmdbItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CardContextTest {
    @Test fun `the tmdb card keeps the overview`() {
        val item = TmdbItem(
            id = 1396,
            type = "tv",
            title = "Breaking Bad",
            originalTitle = "Breaking Bad",
            posterUrl = "",
            year = "2008",
            backdropUrl = "",
            overview = "Un profesor de química con cáncer terminal.",
        )
        assertEquals("Un profesor de química con cáncer terminal.", item.toTitleCard().overview)
    }

    @Test fun `with no overview the card ends up with an empty string`() {
        val item = TmdbItem(
            id = 1,
            type = "movie",
            title = "X",
            originalTitle = "X",
            posterUrl = "",
            year = "2020",
            backdropUrl = "",
        )
        assertEquals("", item.toTitleCard().overview)
    }
}
