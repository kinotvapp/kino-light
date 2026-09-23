package com.arkiv.player.data

import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which season a Caracol chapter gets saved with. If the order is reversed, in a
 * GROUP_OF_BUNDLES every chapter takes the series' single season and S2's chapter 1 overwrites
 * S1's chapter 1.
 *
 * Moved here from `ui/search/TemporadaDelCapituloTest.kt` alongside `DituEntities.caracolChapter`
 * / `DituEntities.seasonForChapter`, which used to live in `ui/search/SearchPlayback.kt`.
 */
class SeasonForChapterTest {

    private fun series(season: Int) = GatewaySeries(imdbId = "", tmdbId = 0, seasonNumber = season)

    @Test fun `the chapter's own season wins over the series'`() {
        val chapterFromS2 = GatewayEpisode(number = 1, title = "Uno", ref = "ditu1:VOD:b", season = 2)
        assertEquals(2, DituEntities.seasonForChapter(chapterFromS2, series(1)))
    }

    @Test fun `with no season of its own, the series' is used`() {
        assertEquals(3, DituEntities.seasonForChapter(GatewayEpisode(number = 1, title = "Uno", ref = "r"), series(3)))
    }

    @Test fun `with neither of the two, none is invented`() {
        assertNull(DituEntities.seasonForChapter(GatewayEpisode(number = 1, title = "Uno", ref = "r"), null))
    }

    /**
     * `SearchPlayback.playDituSeason`'s chain without the database: the list and the chosen one go
     * through [DituEntities.caracolChapter] and from there to [DituEntities.buildSeries]. In a
     * group, the series brings ONE season (season 1) for all of them, so if the chosen one took it
     * from there, or were looked up by number, S2's chapter 1 would play S1's.
     */
    @Test fun `in a group, tapping S2's chapter 1 plays S2's`() {
        val fromTheGroup = series(1)
        val list = listOf(
            GatewayEpisode(number = 1, title = "Uno", ref = "ditu1:VOD:a1", season = 1),
            GatewayEpisode(number = 2, title = "Dos", ref = "ditu1:VOD:a2", season = 1),
            GatewayEpisode(number = 1, title = "Uno", ref = "ditu1:VOD:b1", season = 2),
        )
        val tapped = list[2]
        val saved = DituEntities.buildSeries(
            contentId = "G1", seriesRef = "ditu1:GROUP_OF_BUNDLES:G1", title = "Pedro",
            chapters = list.map { DituEntities.caracolChapter(it, fromTheGroup) },
            chosen = DituEntities.caracolChapter(tapped, fromTheGroup),
            posterUrl = "", now = 1L, existing = null, episodiosVistosEnLista = null,
        )

        assertEquals(3, saved.episodes.size)
        assertEquals("ditu:G1::t2e1", saved.chosenId)
        assertEquals("ditu1:VOD:b1", saved.episodes.single { it.id == saved.chosenId }.torrentData)
        // The fallback (`playDituEpisode`, a standalone chapter) lands in the same row.
        val standalone = DituEntities.build(
            contentId = "G1", ref = tapped.ref, title = "Pedro", episode = tapped.number,
            episodeTitle = tapped.title, posterUrl = "", now = 1L,
            seriesRef = "ditu1:GROUP_OF_BUNDLES:G1", existing = null,
            season = DituEntities.seasonForChapter(tapped, fromTheGroup),
        )
        assertEquals(saved.chosenId, standalone.second.id)
    }
}
