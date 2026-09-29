package com.arkiv.player.ui.search

import com.arkiv.player.data.catalog.TmdbEpisode
import com.arkiv.player.data.catalog.TmdbSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefineDataTest {
    private fun season(n: Int) = TmdbSeason(seasonNumber = n, episodeCount = 10, name = "S$n")

    @Test
    fun `the default season is the first real one, skipping the specials`() {
        assertEquals(1, defaultRefineSeason(listOf(season(0), season(1), season(2))))
    }

    @Test
    fun `a show with only specials falls back to them`() {
        assertEquals(0, defaultRefineSeason(listOf(season(0))))
    }

    @Test
    fun `no seasons means no default`() {
        assertNull(defaultRefineSeason(emptyList()))
    }

    @Test
    fun `season chips read T and the number, season zero reads Especiales`() {
        assertEquals("T3", refineSeasonLabel(3))
        assertEquals("Especiales", refineSeasonLabel(0))
    }

    @Test
    fun `an episode reads E, its number and its name`() {
        val ep = TmdbEpisode(season = 2, episode = 5, name = "El regreso", overview = "", air = "", stillUrl = "")
        assertEquals("E5 · El regreso", refineEpisodeLabel(ep))
    }
}
