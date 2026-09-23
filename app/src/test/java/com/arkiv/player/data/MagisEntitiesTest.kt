package com.arkiv.player.data

import com.arkiv.player.data.db.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How what arrives from Magis gets saved.
 *
 * The case that started these tests: chapter 1 of "Dragon Ball Daima" was watched and the card
 * came out in the home's **Movies** row. Magis was the only "save a chapter" path that didn't
 * force `categoryOverride = "series"` -- and it also created one item per chapter
 * (`magis:<id>:e1`) -- so the automatic detection (`episodeCount <= 1` in `LibraryRow.isMovie`)
 * read it as a movie. The tests below pin down the two halves of the contract: **one item per
 * season** and **marked as series from the first chapter**.
 */
class MagisEntitiesTest {

    private fun chapter(
        contentId: String = "ABC",
        ref: String = "ref-cap",
        title: String = "Dragon Ball Daima T1",
        episode: Int = 1,
        episodeTitle: String = "",
        seriesRef: String = "ref-temporada",
        existing: ItemEntity? = null,
        season: Int? = null,
        tmdbId: Int? = null,
    ) = MagisEntities.build(
        contentId = contentId, ref = ref, title = title, episode = episode,
        episodeTitle = episodeTitle, posterUrl = "poster.jpg", now = 1_000L,
        seriesRef = seriesRef, existing = existing, season = season, tmdbId = tmdbId,
    )

    @Test fun `a chapter marks the item as series`() {
        // The heart of the bug: without this, a single-episode item falls into Movies.
        val (item, _) = chapter()
        assertEquals("series", item.categoryOverride)
    }

    @Test fun `a chapter's item is the season, not the chapter`() {
        val (item, ep) = chapter(episode = 1)
        assertEquals("magis:ABC", item.identifier)
        assertEquals("magis:ABC", ep.itemId)
    }

    @Test fun `two chapters of the same season land in ONE single item`() {
        // What used to make each chapter its own card in the home screen.
        val (item1, ep1) = chapter(episode = 1)
        val (item2, ep2) = chapter(episode = 2)
        assertEquals(item1.identifier, item2.identifier)
        assertNotEquals(ep1.id, ep2.id)
    }

    @Test fun `the chapter is numbered so it's known which one is missing`() {
        // `NewChapterFinder` compares these numbers against the portal's; without them it can't
        // know which chapter to download.
        val (_, ep) = chapter(episode = 7)
        assertEquals(7, ep.episode)
        assertEquals(7, ep.orderIndex)
    }

    @Test fun `the chapter's ref travels on the chapter`() {
        // It's what the player resolves on playback (`magisRefForEpisode`).
        val (_, ep) = chapter(ref = "ref-del-cap-3", episode = 3)
        assertEquals("ref-del-cap-3", ep.torrentData)
    }

    @Test fun `the season's ref travels on the item`() {
        // The item represents the SEASON: its ref is the one used to ask for the chapter list,
        // not the one for the chapter that was just watched.
        val (item, _) = chapter(ref = "ref-del-cap", seriesRef = "ref-de-la-temporada")
        assertEquals("ref-de-la-temporada", item.torrentData)
    }

    @Test fun `without a season ref, the one already there isn't overwritten`() {
        // Refs expire and get re-issued; a blank one must never erase a good one.
        val previous = chapter(seriesRef = "ref-buena").first
        val (item, _) = chapter(seriesRef = "", existing = previous)
        assertEquals("ref-buena", item.torrentData)
    }

    @Test fun `a new chapter doesn't reset the series`() {
        // upsertItem is REPLACE (delete then insert), so anything not copied here is lost: the
        // added date would reorder the home screen and `episodiosVistosEnLista` would turn the
        // new-episodes badge back on over chapters already watched.
        val previous = ItemEntity(
            identifier = "magis:ABC", title = "Dragon Ball Daima T1", description = null,
            thumbnailUrl = "poster.jpg", addedAt = 500L, categoryOverride = "series",
            source = "magis", torrentData = "ref-temporada", episodiosVistosEnLista = 4, tmdbId = 123,
        )
        val (item, _) = chapter(episode = 5, existing = previous)
        assertEquals(500L, item.addedAt)
        assertEquals(4, item.episodiosVistosEnLista)
        assertEquals(123, item.tmdbId)
    }

    @Test fun `a movie is still a movie`() {
        // episode = 0 is the path it always was, untouched: no override, one episode, an id with no :e.
        val (item, ep) = chapter(title = "Duro de matar", episode = 0, seriesRef = "")
        assertEquals("magis:ABC", item.identifier)
        assertNull(item.categoryOverride)
        assertEquals("magis:ABC::0", ep.id)
    }

    @Test fun `a standalone chapter is typed as tv`() {
        // So `library_items` (PocketBase) knows for certain this is a series, not by fuzzy title
        // -- see ItemEntity.tipo's KDoc.
        val (item, _) = chapter(episode = 3)
        assertEquals("tv", item.tipo)
    }

    @Test fun `a standalone movie is typed as movie`() {
        val (item, _) = chapter(episode = 0)
        assertEquals("movie", item.tipo)
    }

    @Test fun `a chapter's old id can be recognized to sweep it`() {
        // Rows saved before this change were left as `magis:<contentId>:e<n>`, i.e. one
        // movie-card per chapter. They get deleted when that same chapter is saved again.
        assertEquals("magis:ABC:e1", MagisEntities.legacyChapterId("ABC", 1))
    }

    @Test fun `a standalone added chapter can carry its real season`() {
        // `NewChapterFinder.checkMagis`'s path: adds a new chapter in the background and, if
        // the gateway resolved TMDB, already knows the real season. Without this, that chapter
        // would be left with `season = null` mixed in with ones that do have it, and
        // `ensureEpisodeStills` would flatten the whole season instead of cross-referencing by
        // exact (season, chapter) (see `chapterEntity`'s KDoc).
        val (_, ep) = chapter(episode = 8, season = 5)
        assertEquals(5, ep.season)
    }

    @Test fun `a standalone chapter with no resolved season doesn't invent one`() {
        // The gateway couldn't always cross-reference against TMDB (`GatewaySeries` null): then
        // there's no season to save, and none is invented.
        val (_, ep) = chapter()
        assertNull(ep.season)
    }

    @Test fun `a new standalone chapter's tmdbId wins but an absent one doesn't erase what was already there`() {
        // Same contract as `buildSeason` (see that test below), but for the standalone
        // added-chapter path.
        val previous = chapter().first.copy(tmdbId = 123)
        assertEquals(456, chapter(existing = previous, tmdbId = 456).first.tmdbId)
        assertEquals(123, chapter(existing = previous).first.tmdbId)
    }

    private fun season(
        contentId: String = "ABC",
        title: String = "Dragon Ball Daima T1",
        chapters: List<SeasonChapter> = listOf(
            SeasonChapter(1, "El misterio", "ref-1"),
            SeasonChapter(2, "El deseo", "ref-2"),
            SeasonChapter(3, "La aventura", "ref-3"),
        ),
        seriesRef: String = "ref-temporada",
        existing: ItemEntity? = null,
        // Null by default: in the real app `ArkivRepository.addMagisSeason` computes it against
        // the database (see NewEpisodeCounter.reseal) and passes it in already resolved. Here,
        // with no DB, each test that cares about the badge sets it by hand.
        episodiosVistosEnLista: Int? = null,
        tmdbId: Int? = null,
        seasonNumber: Int? = null,
        tituloCanonico: String? = null,
    ) = MagisEntities.buildSeason(
        contentId = contentId, title = title, chapters = chapters,
        posterUrl = "poster.jpg", now = 1_000L, seriesRef = seriesRef, existing = existing,
        episodiosVistosEnLista = episodiosVistosEnLista, tmdbId = tmdbId, seasonNumber = seasonNumber,
        tituloCanonico = tituloCanonico,
    )

    @Test fun `the season comes in as ONE item with all its chapters`() {
        val (item, eps) = season()
        assertEquals("magis:ABC", item.identifier)
        assertEquals("series", item.categoryOverride)
        assertEquals(listOf("magis:ABC::e1", "magis:ABC::e2", "magis:ABC::e3"), eps.map { it.id })
        assertEquals(listOf(1, 2, 3), eps.map { it.episode })
        assertEquals(listOf(1, 2, 3), eps.map { it.orderIndex })
        assertEquals(listOf("ref-1", "ref-2", "ref-3"), eps.map { it.torrentData })
    }

    @Test fun `a season's chapter comes out the same as saved one at a time`() {
        // If they diverged, saving the season would duplicate chapters that were already
        // standalone: the id is the primary key and `upsert` is REPLACE, so it HAS to match.
        val (_, standalone) = chapter(episode = 2, ref = "ref-2", episodeTitle = "El deseo")
        val inSeason = season().second.first { it.episode == 2 }
        assertEquals(standalone.id, inSeason.id)
        assertEquals(standalone.displayName, inSeason.displayName)
        assertEquals(standalone.itemId, inSeason.itemId)
    }

    @Test fun `saving the season again doesn't duplicate or reorder the home screen`() {
        // This runs on EVERY playback: if it moved `addedAt`, the series would jump to the top of
        // the home screen every time a chapter is played.
        val previous = season().first.copy(addedAt = 500L, episodiosVistosEnLista = 4, tmdbId = 123)
        val (item, eps) = season(existing = previous, episodiosVistosEnLista = 4)
        assertEquals(500L, item.addedAt)
        assertEquals(4, item.episodiosVistosEnLista)
        assertEquals(123, item.tmdbId)
        assertEquals(3, eps.size)
        assertEquals(3, eps.map { it.id }.distinct().size)
    }

    @Test fun `buildSeason leaves the badge at what it's given, not at what the existing row had`() {
        // `buildSeason` used to copy `existing?.episodiosVistosEnLista` untouched, and the
        // repository would fix it afterward with a second UPDATE (`markEpisodesSeen`) -- the
        // second write that made the sync trigger recurse if it landed in the same second as the
        // `upsertItem`. Now the caller (the repository, which does have the database to compute
        // the union) already passes in the re-sealed total, and `buildSeason` only saves it: if
        // this went back to reading `existing.episodiosVistosEnLista` instead of the parameter in
        // here, this test would catch it (it would leave 4, not 9).
        val previous = season().first.copy(episodiosVistosEnLista = 4)
        val (item, _) = season(existing = previous, episodiosVistosEnLista = 9)
        assertEquals(9, item.episodiosVistosEnLista)
    }

    @Test fun `the badge stays null if it had never been sealed`() {
        // `NewEpisodeCounter.reseal` returns null when `watched` is null (the detail screen was
        // never opened): sealing it here would turn on the new-episodes badge over chapters that
        // were never actually shown as "new". `buildSeason` must not invent a value on its own.
        val previous = season().first.copy(episodiosVistosEnLista = null)
        val (item, _) = season(existing = previous, episodiosVistosEnLista = null)
        assertNull(item.episodiosVistosEnLista)
    }

    @Test fun `the season's ref wins and blank doesn't overwrite what's saved`() {
        // Refs expire and get re-issued; a blank one must never erase a good one.
        assertEquals("ref-temporada", season().first.torrentData)
        val previous = season(seriesRef = "ref-buena").first
        assertEquals("ref-buena", season(seriesRef = "", existing = previous).first.torrentData)
    }

    @Test fun `a season with no chapters doesn't invent episodes`() {
        val (item, eps) = season(chapters = emptyList())
        assertEquals("magis:ABC", item.identifier)
        assertEquals(0, eps.size)
    }

    @Test fun `the season is always typed as tv`() {
        assertEquals("tv", season().first.tipo)
    }

    @Test fun `a new tmdbId wins but an absent one doesn't erase what was already there`() {
        val previous = season().first.copy(tmdbId = 123)
        // With a NEW tmdbId, that's the one that's left -- if `buildSeason` reversed the
        // priority (`existing?.tmdbId ?: tmdbId`, favoring the old one) this assert would catch
        // it.
        assertEquals(456, season(existing = previous, tmdbId = 456).first.tmdbId)
        // If TMDB didn't resolve this time (tmdbId = null), it can't erase the one already saved
        // in an earlier call: it's exactly the same case as `episodiosVistosEnLista` (see the
        // badge test above), but for tmdbId.
        assertEquals(123, season(existing = previous).first.tmdbId)
    }

    @Test fun `the chapter saves the real season`() {
        // Without this, `ArkivRepository.ensureEpisodeStills` can't cross-reference by exact
        // (season, chapter) and falls to spreading chapters 1..N as if the series started at S1
        // (see `MagisEntities.chapterEntity`'s KDoc): a series that doesn't start there (Breaking
        // Bad S5) would end up with another season's stills, silently. If anyone puts
        // `season = null` back here, this test fails.
        val (_, eps) = season(seasonNumber = 5)
        assertEquals(listOf(5, 5, 5), eps.map { it.season })
    }

    @Test fun `with no resolved season, the chapter is left without a season`() {
        // The gateway couldn't always cross-reference the series against TMDB (`GatewaySeries`
        // null): then there's no season number to save, and none is invented.
        val (_, eps) = season(seasonNumber = null)
        assertEquals(listOf(null, null, null), eps.map { it.season })
    }

    @Test fun `an enriched chapter leaves its still row`() {
        val rows = MagisEntities.seasonStills(
            itemId = "magis:ABC",
            chapters = listOf(
                SeasonChapter(1, "T1_1", "ref-1", still = "https://img/1.jpg", tmdbTitle = "La conspiración", overview = "Goku…"),
                SeasonChapter(2, "T1_2", "ref-2"),
            ),
            now = 1_000L,
        )
        assertEquals(1, rows.size)
        assertEquals("magis:ABC::e1", rows[0].episodeId)
        assertEquals("https://img/1.jpg", rows[0].stillUrl)
        assertEquals("La conspiración", rows[0].title)
        assertEquals("Goku…", rows[0].overview)
    }

    @Test fun `a chapter with nothing to enrich leaves no row`() {
        // With no row, the UI falls back to the portal's displayName. An empty row would show a gap.
        assertEquals(0, MagisEntities.seasonStills("magis:ABC", listOf(SeasonChapter(1, "T1_1", "ref-1")), 1_000L).size)
    }

    @Test fun `the row's id matches the episode's`() {
        // The row is cross-referenced by episodeId: if it didn't match, the image would never show up.
        val (_, eps) = season()
        val rows = MagisEntities.seasonStills("magis:ABC", listOf(SeasonChapter(2, "x", "r", still = "u")), 1_000L)
        assertEquals(eps.first { it.episode == 2 }.id, rows[0].episodeId)
    }

    /**
     * The MOVIE-shaped episode's id, as a function and not as a loose literal: whoever saves a
     * season has to be able to SWEEP it.
     *
     * A series that first came in as a lone ref -- how "For You" used to save things before it
     * knew to ask the gateway for chapters -- leaves this row, and its id isn't any chapter's: the
     * season's upsert doesn't overwrite it and it would be left as a ghost chapter, with the
     * series' title and the whole season's ref.
     */
    @Test fun `a movie's episode doesn't share an id with any chapter`() {
        val itemId = MagisEntities.itemIdFor("ABC")
        val movieId = MagisEntities.movieEpisodeId(itemId)
        assertEquals("magis:ABC::0", movieId)
        (1..13).forEach { assertNotEquals(movieId, MagisEntities.episodeIdFor(itemId, it)) }
    }

    /** And it's EXACTLY the id [MagisEntities.build] saves a movie with: if they diverged, the
     * sweep would delete something it shouldn't, or leave the ghost intact. */
    @Test fun `the sweep points at the same id the movie was saved with`() {
        val (_, ep) = chapter(episode = 0, seriesRef = "")
        assertEquals(MagisEntities.movieEpisodeId(MagisEntities.itemIdFor("ABC")), ep.id)
    }


    // --- the name TMDB knows the series by ---

    /**
     * The portal's title is NOT overwritten: it's saved alongside. "Shin seiki evangerion Temp.1"
     * is what magis calls it and that's what stays in `title`; "Neon Genesis Evangelion" is what
     * the library shows. Overwriting it would lose where the item came from the day TMDB gets it
     * wrong -- and besides, `title` is where the person's manual rename lives.
     */
    @Test fun `the canonical name is saved alongside, without overwriting the portal's`() {
        val (item, _) = season(
            title = "Shin seiki evangerion Temp.1",
            tituloCanonico = "Neon Genesis Evangelion",
        )
        assertEquals("Shin seiki evangerion Temp.1", item.title)
        assertEquals("Neon Genesis Evangelion", item.tituloCanonico)
    }

    /**
     * Same `?:` as [tmdbId]: TMDB not resolving TODAY can't erase the name that was already
     * resolved yesterday. `buildSeason` runs on every save of the season, so without this a
     * single save with the gateway down would leave the card with the portal's name again.
     */
    @Test fun `an absent canonical name doesn't erase the one already there`() {
        val previous = season(tituloCanonico = "Neon Genesis Evangelion").first
        val (item, _) = season(tituloCanonico = null, existing = previous)
        assertEquals("Neon Genesis Evangelion", item.tituloCanonico)
    }

    /** A blank name is "didn't arrive", not a name: it would leave the card with no text. */
    @Test fun `a blank canonical name doesn't erase the one already there either`() {
        val previous = season(tituloCanonico = "Neon Genesis Evangelion").first
        val (item, _) = season(tituloCanonico = "   ", existing = previous)
        assertEquals("Neon Genesis Evangelion", item.tituloCanonico)
    }
}

/**
 * Repairing Magis items saved WITHOUT an identity.
 *
 * `tmdbId` gets written when the season is saved, not when it's opened, so the ones saved when
 * the gateway couldn't resolve the series were left forever without a chapter name, without a
 * thumbnail, and without a synopsis. `seriesRef` DID stay saved: that's enough to ask again once.
 */
class RefToRepairTest {
    private val ref = "eyJzIjoibWFnaXMi"

    @Test fun `a magis item with no tmdbId is repaired with its ref`() {
        assertEquals(ref, MagisEntities.refToRepair("magis:ABC", null, ref))
    }

    @Test fun `an invalid tmdbId counts as absent`() {
        // `GatewaySeries.tmdbId` comes from an `optInt`: an absent field gives 0, not null.
        assertEquals(ref, MagisEntities.refToRepair("magis:ABC", 0, ref))
    }

    @Test fun `if it already has an identity it isn't asked again`() {
        assertNull(MagisEntities.refToRepair("magis:ABC", 12609, ref))
    }

    @Test fun `with no saved ref there's nothing to ask with`() {
        assertNull(MagisEntities.refToRepair("magis:ABC", null, null))
        assertNull(MagisEntities.refToRepair("magis:ABC", null, "  "))
    }

    /** Torrent, web and archive have their own path (`ensureEpisodeStills` by title or tmdbId):
     *  asking the gateway for chapters with what they saved in that field makes no sense. */
    @Test fun `what isn't magis's isn't touched`() {
        assertNull(MagisEntities.refToRepair("torrent:abc123", null, ref))
        assertNull(MagisEntities.refToRepair("web:abc123", null, ref))
    }
}
