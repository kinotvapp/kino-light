package com.arkiv.player.data.recommendations

import com.arkiv.player.data.db.RecommendationEntity
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What gets saved when adding a "For you" recommendation to the library.
 *
 * The case that originated these tests, measured in production: "My Hero Academia" was added from
 * "For you" and entered the library with **one** episode (`magis:cammiy790r3ky1o::0`, `tipo =
 * "movie"`). The `ref` the gateway sends is the SEASON's (it carries `episode: 0` inside) and,
 * asked via `/v1/episodes`, returns the 13 episodes with their `series` block — but nobody asked
 * for them: the row was saved with `addMagisSource` with no `episode`, which is `MagisEntities.build`'s
 * MOVIE branch (one item, one episode, no `categoryOverride = "series"`).
 *
 * And it didn't fix itself: `SeriesToCheck.choose` filters `episodeCount > 1`, so the background
 * new-chapters searcher would never look at it either.
 */
class RecommendationSavingTest {

    private fun rec(tipo: String) = RecommendationEntity(
        id = "cammiy790r3ky1o",
        tmdbId = 65930,
        tipo = tipo,
        titulo = "My Hero Academia",
        posterUrl = "https://image.tmdb.org/t/p/w500/mho.jpg",
        porque = "porque terminaste Dragon Ball",
        ref = "season-ref",
        orden = 0,
        generadoAt = 1_000L,
    )

    /** The 13 the portal really returned for that recommendation. */
    private val THIRTEEN = (1..13).map {
        GatewayEpisode(
            number = it,
            title = "My Hero Academia Temporada 1_My Hero Academia T1-%02d".format(it),
            ref = "ref-cap-$it",
        )
    }

    private val SERIES = GatewaySeries(imdbId = "tt5626028", tmdbId = 65930, seasonNumber = 1)

    @Test fun a_series_is_saved_with_all_its_chapters() {
        val season = RecommendationSaving.seasonFor(THIRTEEN, SERIES)
        assertNotNull(season)
        assertEquals(13, season!!.chapters.size)
        assertEquals((1..13).toList(), season.chapters.map { it.number })
        // Each CHAPTER's ref, not the season's: that's what the player resolves when playing,
        // and the bug was exactly saving the season's as if it were a chapter's.
        assertEquals("ref-cap-1", season.chapters.first().ref)
        assertEquals("ref-cap-13", season.chapters.last().ref)
    }

    @Test fun a_series_asks_the_gateway_for_its_chapters() {
        assertTrue(RecommendationSaving.needsChapters(rec(tipo = "tv")))
    }

    @Test fun a_movie_does_not_ask_anyone_for_chapters() {
        assertFalse(RecommendationSaving.needsChapters(rec(tipo = "movie")))
    }

    /**
     * The episode listing can legitimately come back empty -- a series whose portal listing failed,
     * or (until this branch's pruning) a legacy recommendation row pointing at a source that could
     * no longer list chapters (`/v1/episodes` used to answer 422 for those). With no season to
     * save, the caller has to fall back to the usual standalone save -- never leave the item half-done.
     */
    @Test fun a_source_that_lists_no_chapters_leaves_no_season() {
        assertNull(RecommendationSaving.seasonFor(emptyList(), SERIES))
    }

    /**
     * With no these three fields the episode has no `episode_still` row: a black, numbered card
     * in the library until someone opens the series. Same reason `SearchPlayback.magisEpisodeIdFor`
     * carries them along.
     */
    @Test fun chapters_carry_a_still_real_name_and_synopsis() {
        val enriched = GatewayEpisode(
            number = 1,
            title = "My Hero Academia Temporada 1_My Hero Academia T1-01",
            ref = "ref-cap-1",
            still = "https://image.tmdb.org/t/p/w300/uno.jpg",
            tmdbTitle = "Izuku Midoriya: Orígenes",
            overview = "En un mundo donde casi todos tienen superpoderes…",
        )
        val chapter = RecommendationSaving.seasonFor(listOf(enriched), SERIES)!!.chapters.single()
        assertEquals("https://image.tmdb.org/t/p/w300/uno.jpg", chapter.still)
        assertEquals("Izuku Midoriya: Orígenes", chapter.tmdbTitle)
        assertEquals("En un mundo donde casi todos tienen superpoderes…", chapter.overview)
        // The portal's title is kept separately: it's the episode's `displayName`.
        assertEquals("My Hero Academia Temporada 1_My Hero Academia T1-01", chapter.title)
    }

    /**
     * With no `seasonNumber`, `ArkivRepository.ensureEpisodeStills` falls into its flatten-from-
     * season-1 branch and gives each episode another one's still (see `MagisEntities.build`'s KDoc).
     */
    @Test fun the_season_travels_so_stills_do_not_get_flattened() {
        assertEquals(1, RecommendationSaving.seasonFor(THIRTEEN, SERIES)!!.seasonNumber)
    }

    @Test fun the_series_tmdbId_travels_with_the_season() {
        assertEquals(65930, RecommendationSaving.seasonFor(THIRTEEN, SERIES)!!.tmdbId)
    }

    /**
     * `GatewaySeries.tmdbId` comes from an `optInt`: a field that didn't come back gives 0, not
     * null. That 0 would beat the `?:` `buildSeason` uses to preserve an already-saved tmdbId.
     */
    @Test fun a_tmdbId_of_zero_is_not_an_identification() {
        val season = RecommendationSaving.seasonFor(THIRTEEN, GatewaySeries("", 0, 1))
        assertNull(season!!.tmdbId)
    }

    /** The gateway doesn't always match the series against TMDB; chapters are still saved. */
    @Test fun with_no_series_block_chapters_are_still_saved() {
        val season = RecommendationSaving.seasonFor(THIRTEEN, null)
        assertEquals(13, season!!.chapters.size)
        assertNull(season.tmdbId)
        assertNull(season.seasonNumber)
    }

    private fun recCon(id: String, ref: String) = RecommendationEntity(
        id = id, tmdbId = 0, tipo = "movie", titulo = "x", posterUrl = "", porque = "", ref = ref,
        orden = 0, generadoAt = 0,
    )

    @Test fun `a Caracol ref goes to Caracol with its contentId`() {
        assertEquals(
            RecommendationTarget.Caracol("99"),
            RecommendationSaving.targetFor(recCon("ditu:99", "ditu1:BUNDLE:99")),
        )
    }

    @Test fun `a Magis ref goes to Magis with its contentId and not the row's id`() {
        assertEquals(
            RecommendationTarget.Magis("C42"),
            RecommendationSaving.targetFor(recCon("otro-id", "magis1:teleplay:0:C42")),
        )
    }

    /** Old gateway rows: their id is the PocketBase record's and their ref may not be understood. */
    @Test fun `a row with a ref that is not understood falls back to Magis with its id`() {
        assertEquals(
            RecommendationTarget.Magis("pbrecord123"),
            RecommendationSaving.targetFor(recCon("pbrecord123", "ilegible")),
        )
    }

    @Test fun `a ref with no known source has no target`() {
        assertNull(RecommendationSaving.targetForRef("otra:cosa"))
    }

    @Test fun `the saved item is the source's`() {
        assertEquals(
            com.arkiv.player.data.MagisEntities.itemIdFor("C42"),
            RecommendationSaving.itemIdFor(RecommendationTarget.Magis("C42")),
        )
        assertEquals(
            com.arkiv.player.data.DituEntities.itemIdFor("99"),
            RecommendationSaving.itemIdFor(RecommendationTarget.Caracol("99")),
        )
    }
}
