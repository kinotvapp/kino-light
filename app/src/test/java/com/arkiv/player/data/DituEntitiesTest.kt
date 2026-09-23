package com.arkiv.player.data

import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How what arrives from Caracol gets saved to the library.
 *
 * The contract that matters is with the player: what's saved here is opened by
 * `PlayerViewModel.loadDitu`, which only enters if the id starts with `ditu:`
 * (`PlayerSource.kindFor`) and reads the ref from the episode's `torrentData`. If either of those
 * two things breaks, what's saved never plays again.
 */
class DituEntitiesTest {

    private fun movie(existing: ItemEntity? = null, tmdbId: Int? = null) = DituEntities.build(
        contentId = "P1", ref = "ditu1:VOD:P1", title = "Rigo", episode = 0, episodeTitle = "",
        posterUrl = "poster.jpg", now = 1_000L, seriesRef = "", existing = existing, tmdbId = tmdbId,
    )

    private fun chapter(
        contentId: String = "B9",
        ref: String = "ditu1:VOD:E3",
        number: Int = 3,
        season: Int? = 1,
        seriesRef: String = "ditu1:BUNDLE:B9",
    ) = DituEntities.build(
        contentId = contentId, ref = ref, title = "Pedro el escamoso", episode = number,
        episodeTitle = "El regreso", posterUrl = "poster.jpg", now = 1_000L,
        seriesRef = seriesRef, existing = null, season = season,
    )

    @Test fun `all three ids have their exact shape`() {
        assertEquals("ditu:B9", DituEntities.itemIdFor("B9"))
        assertEquals("ditu:B9::e3", DituEntities.episodeIdFor("ditu:B9", 3))
        assertEquals("ditu:P1::0", DituEntities.movieEpisodeId("ditu:P1"))
    }

    /** The link to Task 9: without this, what's saved never enters through `loadDitu`. */
    @Test fun `what's saved enters the player through Caracol`() {
        val ofAChapter = DituEntities.episodeIdFor(DituEntities.itemIdFor("B9"), 3)
        val ofAMovie = DituEntities.movieEpisodeId(DituEntities.itemIdFor("P1"))

        assertEquals(SourceKind.DITU, PlayerSource.kindFor(ofAChapter))
        assertEquals(SourceKind.DITU, PlayerSource.kindFor(ofAMovie))
        assertEquals(SourceKind.DITU, PlayerSource.kindFor(chapter().second.id))
        assertEquals(SourceKind.DITU, PlayerSource.kindFor(movie().second.id))
        // A season that isn't the first also carries its id with Caracol's prefix.
        assertEquals(SourceKind.DITU, PlayerSource.kindFor(chapter(season = 2).second.id))
    }

    @Test fun `the ref goes in the episode's torrentData`() {
        assertEquals("ditu1:VOD:E3", chapter().second.torrentData)
        assertEquals("ditu1:VOD:P1", movie().second.torrentData)
    }

    @Test fun `a chapter lands in its series' item, marked as series`() {
        val (item, ep) = chapter()
        assertEquals("ditu:B9", item.identifier)
        assertEquals("ditu:B9", ep.itemId)
        assertEquals("ditu:B9::e3", ep.id)
        assertEquals("series", item.categoryOverride)
        assertEquals("tv", item.tipo)
        assertEquals("ditu", item.source)
        assertEquals(1, ep.season)
        assertEquals(3, ep.episode)
    }

    @Test fun `a movie is its own item`() {
        val (item, ep) = movie()
        assertEquals("ditu:P1", item.identifier)
        assertEquals("ditu:P1::0", ep.id)
        assertEquals("movie", item.tipo)
        assertEquals("ditu", item.source)
        assertNull(item.categoryOverride)
    }

    /**
     * `ensureEpisodeStills` only cross-references by (season, chapter) if ALL of an item's
     * episodes have a season: one at null makes it flatten from S1 (see
     * `MagisEntities.chapterEntity`'s KDoc).
     */
    @Test fun `a chapter with no season is left at 1, never at null`() {
        assertEquals(1, chapter(season = null).second.season)
        assertEquals(1, chapter(season = 0).second.season)
    }

    /**
     * In a GROUP_OF_BUNDLES each season brings its own chapter 1 (that's how `DituEpisodesTest`
     * builds it): if the season didn't enter the id, S2's chapter 1 would overwrite S1's row and
     * would end up playing a different chapter.
     */
    @Test fun `in a group, each season's chapter 1 doesn't overwrite the other`() {
        val group = "ditu1:GROUP_OF_BUNDLES:G1"
        val (_, s1) = chapter(contentId = "G1", ref = "ditu1:VOD:a", number = 1, season = 1, seriesRef = group)
        val (_, s2) = chapter(contentId = "G1", ref = "ditu1:VOD:b", number = 1, season = 2, seriesRef = group)

        assertNotEquals(s1.id, s2.id)
        assertEquals("ditu:G1::e1", s1.id)
        assertEquals("ditu1:VOD:b", s2.torrentData)
        // The library orders by orderIndex (`ItemDao.getEpisodesOf`): S2 comes after S1.
        assertTrue(s1.orderIndex < s2.orderIndex)
    }

    @Test fun `only what's Caracol's gets saved`() {
        assertEquals("P1", DituEntities.itemContentId("ditu1:VOD:P1", seriesRef = "", episode = 0))
        assertEquals("B9", DituEntities.itemContentId("ditu1:VOD:E3", seriesRef = "ditu1:BUNDLE:B9", episode = 3))
        // A Magis ref never comes out with a `ditu:` id: the player would send it to Caracol.
        assertNull(DituEntities.itemContentId("magis1:movie:0:C1", seriesRef = "", episode = 0))
        assertNull(DituEntities.itemContentId("ditu1:VOD:E3", seriesRef = "magis1:teleplay:0:C1", episode = 3))
        // A series isn't saved as a movie: its chapters get chosen first.
        assertNull(DituEntities.itemContentId("ditu1:BUNDLE:B9", seriesRef = "", episode = 0))
        // A chapter with no series has no item to go to.
        assertNull(DituEntities.itemContentId("ditu1:VOD:E3", seriesRef = "", episode = 3))
    }

    @Test fun `saving again doesn't lose the added date or the tmdbId`() {
        val (first, _) = movie(tmdbId = 77)
        val (second, _) = movie(existing = first.copy(addedAt = 5L))
        assertEquals(5L, second.addedAt)
        assertEquals(77, second.tmdbId)
    }

    // ── The whole series: what gets saved when a chapter is tapped (`ArkivRepository.addDituSeason`) ──

    private val group = "ditu1:GROUP_OF_BUNDLES:G1"

    /** A two-season GROUP_OF_BUNDLES, each with its own chapter 1 (like `DituEpisodesTest`). */
    private val listFromTheGroup = listOf(
        CaracolChapter(1, "Uno", "ditu1:VOD:a1", season = 1),
        CaracolChapter(2, "Dos", "ditu1:VOD:a2", season = 1),
        CaracolChapter(1, "Uno de la T2", "ditu1:VOD:b1", season = 2),
        CaracolChapter(2, "Dos de la T2", "ditu1:VOD:b2", season = 2),
    )

    private fun series(
        chapters: List<CaracolChapter> = listFromTheGroup,
        chosen: CaracolChapter = chapters.first(),
        existing: ItemEntity? = null,
        episodiosVistosEnLista: Int? = null,
        posterUrl: String = "poster.jpg",
        tmdbId: Int? = null,
        tituloCanonico: String? = null,
    ) = DituEntities.buildSeries(
        contentId = "G1", seriesRef = group, title = "Pedro el escamoso", chapters = chapters,
        chosen = chosen, posterUrl = posterUrl, now = 1_000L, existing = existing,
        episodiosVistosEnLista = episodiosVistosEnLista, tmdbId = tmdbId, tituloCanonico = tituloCanonico,
    )

    /** The chapter saved standalone, as `ArkivRepository.addDituSource` saves it. */
    private fun standalone(cap: CaracolChapter) = DituEntities.build(
        contentId = "G1", ref = cap.ref, title = "Pedro el escamoso", episode = cap.number,
        episodeTitle = cap.title, posterUrl = "poster.jpg", now = 1_000L, seriesRef = group,
        existing = null, season = cap.season,
    )

    /** The bug the person reported: the library showed the series with a single chapter. */
    @Test fun `the whole series comes in, each chapter with its ref`() {
        val saved = series(chosen = listFromTheGroup[2])

        assertEquals(listFromTheGroup.map { it.ref }, saved.episodes.map { it.torrentData })
        assertEquals(listFromTheGroup.size, saved.episodes.map { it.id }.toSet().size)
        assertTrue(saved.episodes.all { it.itemId == "ditu:G1" })
        assertTrue(saved.episodes.all { PlayerSource.kindFor(it.id) == SourceKind.DITU })
        // The item is the series, with its ref: the group's, not any chapter's.
        assertEquals("ditu:G1", saved.item.identifier)
        assertEquals(group, saved.item.torrentData)
        assertEquals("series", saved.item.categoryOverride)
        assertEquals("tv", saved.item.tipo)
        assertEquals("ditu", saved.item.source)
    }

    @Test fun `in the series, S1's chapter 1 and S2's don't overwrite each other`() {
        val (s1, s2) = series().episodes.filter { it.episode == 1 }
        assertNotEquals(s1.id, s2.id)
        assertEquals("ditu:G1::e1", s1.id)
        assertEquals("ditu1:VOD:b1", s2.torrentData)
        assertEquals(2, s2.season)
        assertTrue(s1.orderIndex < s2.orderIndex)
    }

    /**
     * What prevents duplicates: the person already has the series saved with ONE chapter (via
     * `addDituSource`), and tapping another saves the whole series. If the same chapter had a
     * different id saved with its series, it would be left twice in the library.
     */
    @Test fun `the same chapter has the same id standalone and saved with its series`() {
        val withItsSeries = series()
        for (cap in listFromTheGroup) {
            val (standaloneItem, standaloneEp) = standalone(cap)
            val epWithSeries = withItsSeries.episodes.single { it.torrentData == cap.ref }
            assertEquals(standaloneEp.id, epWithSeries.id)
            assertEquals(standaloneEp, epWithSeries)
            assertEquals(standaloneItem, withItsSeries.item)
        }
        // With no season, both paths send it to 1.
        val noSeason = CaracolChapter(3, "Tres", "ditu1:VOD:a3", season = null)
        assertEquals(standalone(noSeason).second, series(chapters = listOf(noSeason)).episodes.single())
        assertEquals(1, series(chapters = listOf(noSeason)).episodes.single().season)
    }

    @Test fun `saving the series again respects what's saved`() {
        val first = series(tmdbId = 77, tituloCanonico = "Pedro el Escamoso").item.copy(addedAt = 5L)
        // The second time arrives empty: TMDB didn't find it, or no poster came in.
        val second = series(existing = first, posterUrl = "", tmdbId = null, tituloCanonico = "  ").item

        assertEquals(5L, second.addedAt)
        assertEquals(77, second.tmdbId)
        assertEquals("Pedro el Escamoso", second.tituloCanonico)
        assertEquals("poster.jpg", second.thumbnailUrl)
    }

    /** The badge gets re-sealed by the repository against the union; only what arrives is saved here. */
    @Test fun `the badge is left at what it's given, not at what it had`() {
        val existing = series().item.copy(episodiosVistosEnLista = 2)
        assertEquals(6, series(existing = existing, episodiosVistosEnLista = 6).item.episodiosVistosEnLista)
        assertNull(series(existing = existing, episodiosVistosEnLista = null).item.episodiosVistosEnLista)
    }

    /** The repeated-numbers trap: by number alone, tapping S2's 1 would open S1's. */
    @Test fun `tapping S2's chapter 1 plays S2's`() {
        assertEquals("ditu:G1::t2e1", series(chosen = listFromTheGroup[2]).chosenId)
        assertEquals("ditu:G1::e1", series(chosen = listFromTheGroup[0]).chosenId)
        assertEquals("ditu:G1::t2e2", series(chosen = listFromTheGroup[3]).chosenId)
    }

    /**
     * If Caracol repeated a season and number, the two chapters land in the same row and only one
     * is left: tapping the other one can't play that row, it has to fall back to saving it alone
     * (null).
     */
    @Test fun `a chosen one that wasn't saved with its ref doesn't play`() {
        val repeated = listOf(
            CaracolChapter(1, "A", "ditu1:VOD:a", season = 1),
            CaracolChapter(1, "B", "ditu1:VOD:b", season = 1),
        )
        assertEquals(1, series(chapters = repeated).episodes.size)
        assertNull(series(chapters = repeated, chosen = repeated[0]).chosenId)
        assertEquals("ditu:G1::e1", series(chapters = repeated, chosen = repeated[1]).chosenId)
    }

    @Test fun `only Caracol chapters get into the series`() {
        val fromMagis = CaracolChapter(4, "Cuatro", "magis1:movie:0:C1", season = 1)
        val atZero = CaracolChapter(0, "Cero", "ditu1:VOD:z", season = 1)
        val aSeries = CaracolChapter(5, "Cinco", "ditu1:BUNDLE:B2", season = 1)

        assertEquals(
            listFromTheGroup,
            DituEntities.saveableChapters(group, listFromTheGroup + fromMagis + atZero + aSeries),
        )
        // With a series that isn't Caracol's, none get in.
        assertTrue(DituEntities.saveableChapters("magis1:teleplay:0:C1", listFromTheGroup).isEmpty())
    }
}
