package com.arkiv.player.data

import com.arkiv.player.data.model.EpisodeNumbering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins down the season/chapter parsing that `EpisodeNumbering.displayLabel` (the player header's
 * label) depends on: `seasonOf` is one of its season fallbacks, today its only real caller
 * (`grep -rn "seasonOf(\|episodeOf(" app/src/main/java`). Nobody in production calls `episodeOf`;
 * these tests are its only coverage. The example texts are literally what `addWebSeriesEpisode` /
 * `addSeriesEpisodeMagnet` build.
 */
class EpisodeNumberingTest {

    @Test
    fun `pulls the season out of the section`() {
        assertEquals(1, EpisodeNumbering.seasonOf("Temporada 1"))
        assertEquals(12, EpisodeNumbering.seasonOf("Temporada 12"))
    }

    @Test
    fun `with no number in the section there's no season`() {
        assertNull(EpisodeNumbering.seasonOf(""))
        assertNull(EpisodeNumbering.seasonOf("Extras"))
    }

    @Test
    fun `pulls the chapter out of a web series' displayName`() {
        // addWebSeriesEpisode's format: "T<season> · E<chapter>  <name>".
        assertEquals(1, EpisodeNumbering.episodeOf("T1 · E1"))
        assertEquals(7, EpisodeNumbering.episodeOf("T2 · E7  El regreso"))
    }

    @Test
    fun `the chapter's name doesn't beat the real number`() {
        // find() returns the FIRST match, so an "E9" inside the title doesn't clobber E3.
        assertEquals(3, EpisodeNumbering.episodeOf("T1 · E3  Fuga del bloque E9"))
    }

    @Test
    fun `with no chapter marker there's no number`() {
        assertNull(EpisodeNumbering.episodeOf("Pelicula completa"))
    }
}
