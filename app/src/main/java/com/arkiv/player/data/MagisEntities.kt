package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.EpisodeStillEntity
import com.arkiv.player.data.db.ItemEntity

/**
 * A season's chapter, exactly as [MagisEntities] needs it.
 *
 * Its own model and not `GatewayEpisode` on purpose: `MagisEntities` is pure/JVM (tested without
 * Room or the network) and must not depend on the `gateway` package. The caller maps one to the
 * other.
 *
 * [still], [tmdbTitle] and [overview] are what the gateway adds by crossing the chapter against
 * TMDB (see `GatewayEpisode`); optional because TMDB doesn't always resolve.
 * [MagisEntities.seasonStills] uses them to build each chapter's `episode_still` row.
 */
data class SeasonChapter(
    val number: Int,
    val title: String,
    val ref: String,
    val still: String? = null,
    val tmdbTitle: String? = null,
    val overview: String? = null,
)

/**
 * Builds (item + episode) from what arrives from Magis. Pure/JVM (no `android.*`) so it can be
 * tested without Room; the repository only saves it. Same mold as [PackEntities].
 *
 * **A season is ONE item, and its chapters are its episodes.** Each chapter used to be its own
 * item (`magis:<contentId>:e1`, `…:e2`, …), and since the category is auto-detected by episode
 * count (`LibraryRow.isMovie`: `episodeCount <= 1`), a lone chapter would be read as a **movie**
 * and the series would show up in the wrong home row. This does the same thing the rest of the
 * chapter-bearing sources already did (`addWebSeriesEpisode`, `addSeriesEpisode`,
 * `addAnimeEpisode`): a stable id per series + `categoryOverride = "series"` from the very first
 * chapter, without waiting for there to be two.
 *
 * The id comes from the portal's `contentId` -- which in Magis is already per season -- and not
 * from the `ref`: the ref is re-issued on every search and an id derived from it would lose the
 * "here's where you're at" marker.
 */
object MagisEntities {

    const val PREFIX = "magis:"

    /** The item for a movie or for a whole season. */
    fun itemIdFor(contentId: String): String = PREFIX + contentId

    /**
     * The id a chapter had back when each one was its own item.
     *
     * Used to sweep those rows: they were left in the library as standalone movie-cards and no
     * Room migration touches them (see the plans in `docs/superpowers/plans/`), so they're cleaned
     * up whenever that same chapter is saved again.
     */
    fun legacyChapterId(contentId: String, episode: Int): String = "${itemIdFor(contentId)}:e$episode"

    /**
     * The id of a chapter INSIDE its season's item. Used by [chapterEntity] to build the
     * `EpisodeEntity`, and also by the repository: to know how many episodes the season is going
     * to have AFTER saving (the union of what was already there with what the portal sends, see
     * `ArkivRepository.addMagisSeason`) it needs to compare against the same ids without
     * duplicating the `$itemId::e$number` format here and there.
     */
    fun episodeIdFor(itemId: String, number: Int): String = "$itemId::e$number"

    /**
     * The episode id for a MOVIE -- or for a series that came in as a lone ref, which is how "For
     * You" used to save things before it knew to ask the gateway for chapters.
     *
     * It has its own name because writing it inline where it's saved isn't enough:
     * `ArkivRepository.addMagisSeason` SWEEPS it when saving the season of a series that had
     * already come in that way. Its id isn't any chapter's ([episodeIdFor] always carries `:e`),
     * so the season's upsert doesn't overwrite it and it would be left as a ghost chapter -- with
     * the series' title and the whole season's ref -- forever. If the sweep and the save didn't
     * compute exactly the same id, one would delete something it shouldn't and the other would
     * leave the ghost intact.
     */
    fun movieEpisodeId(itemId: String): String = "$itemId::0"

    /**
     * A single chapter's episode. Shared by [build] and [buildSeason] on purpose: `id` is the
     * primary key, so if the two paths didn't build it identically, saving the season would
     * duplicate chapters that were already saved standalone.
     *
     * [season] is the real season number when it's known ([buildSeason], which pulls it from
     * `GatewaySeries.seasonNumber`) or `null` when it isn't ([build], which saves a standalone
     * chapter with no that context). It matters more than it looks:
     * `ArkivRepository.ensureEpisodeStills` cross-references by (season, chapter) ONLY if ALL of
     * an item's episodes have `season` set: a single `null` one makes it fall to its branch that
     * spreads chapters 1..N per season starting from 1, which for a series that doesn't start at
     * S1 (Breaking Bad S5, for example) sets the wrong chapter's still. Hence this is NOT
     * cosmetic.
     */
    private fun chapterEntity(itemId: String, number: Int, title: String, ref: String, season: Int?) = EpisodeEntity(
        id = episodeIdFor(itemId, number),
        itemId = itemId,
        section = "",
        displayName = "E$number" + title.trim().takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty(),
        orderIndex = number,
        durationSeconds = 0.0,
        thumbPath = null,
        originalPath = null,
        originalFormat = null,
        originalSize = 0,
        derivativePath = null,
        derivativeFormat = null,
        derivativeSize = 0,
        season = season,
        // Numbered on purpose: this is how `MissingChapters` knows which one is missing.
        episode = number,
        torrentFileIndex = null,
        torrentData = ref,
    )

    /**
     * The season ref to ask the gateway again for the identity of a Magis item that was saved
     * without it, or null if there's nothing to repair.
     *
     * `tmdbId` gets written when the season is SAVED, not when it's opened: items that came in
     * back when the gateway still couldn't identify the series were left without it, and along
     * with it without a real chapter name, without a thumbnail, and without a synopsis -- forever,
     * because opening the screen doesn't ask again. `seriesRef` DID stay saved (the same field
     * where web saves its `pageUrl`), and that's enough to ask just once.
     *
     * A `tmdbId` of 0 counts as absent: `GatewaySeries.tmdbId` comes from an `optInt`, and a field
     * that didn't arrive gives 0, not null.
     */
    fun refToRepair(identifier: String, tmdbId: Int?, torrentData: String?): String? {
        if (!identifier.startsWith("magis:")) return null
        if (tmdbId != null && tmdbId > 0) return null
        return torrentData?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The WHOLE season: one item and one episode per chapter.
     *
     * This is what gets saved when a chapter is tapped to watch it -- the screen already has the
     * list, so it doesn't cost a single network call. Runs on every playback, so **it has to be
     * idempotent**: ids derived from the content, and everything `upsertItem` (REPLACE) would
     * erase copied over from [existing], same as in [build].
     *
     * [seriesRef] is the season's ref (the one `MagisCatalog.detail` resolves). Blank doesn't
     * overwrite what was saved: refs expire and an expired one is better than none. Unlike
     * [build], this does NOT fall back to a chapter's ref as a last resort -- a chapter's ref on
     * the item would make `NewChapterFinder` ask a chapter for its list of chapters.
     *
     * [watchedInListCount] arrives COMPLETE, already computed by the caller, and not read in here
     * as `existing?.episodiosVistosEnLista`. It used to be saved untouched and the repository
     * would fix it afterward with a second, pointed UPDATE (`markEpisodesSeen`) -- two writes to
     * the same row in the same call, which is exactly what made the sync trigger recurse when they
     * landed in the same second (see `SyncTriggers`). It can't be computed alone in here: the
     * post-save total is the UNION of the chapters that were already in the database with the
     * ones [chapters] brings (`addMagisSeason` upserts, doesn't replace, so it doesn't lose old
     * chapters the portal no longer lists), and this function is pure/JVM -- it has nothing to
     * query the database with. That's why the repository resolves it (with
     * `ItemDao.getEpisodesOf`) and passes it in already resolved.
     *
     * [tmdbId] and [seasonNumber] come last and with a default because every caller uses named
     * arguments; the order doesn't matter, only that they're optional so they don't break whoever
     * was already calling this function before they existed.
     */
    fun buildSeason(
        contentId: String,
        title: String,
        chapters: List<SeasonChapter>,
        posterUrl: String,
        now: Long,
        seriesRef: String,
        existing: ItemEntity?,
        episodiosVistosEnLista: Int?,
        // Can arrive null when TMDB didn't resolve this time (or the gateway is old), and that's
        // not a reason to discard what was already saved -- hence the `?:` below.
        tmdbId: Int? = null,
        // `GatewaySeries`'s `season_number`: passed to [chapterEntity] so that
        // `ArkivRepository.ensureEpisodeStills` can cross-reference by exact (season, chapter)
        // instead of flattening from season 1 (see [chapterEntity]'s KDoc). Null when the gateway
        // didn't resolve the series: the episode is left with `season = null`, same as today.
        seasonNumber: Int? = null,
        // The name TMDB knows the series by (`GatewaySeries.title`). Same `?:` as [tmdbId]: an
        // absent one doesn't erase what was already saved. See [canonicalTitle].
        tituloCanonico: String? = null,
    ): Pair<ItemEntity, List<EpisodeEntity>> {
        val itemId = itemIdFor(contentId)
        val item = ItemEntity(
            identifier = itemId,
            title = title.ifBlank { "Xuper" },
            description = null,
            thumbnailUrl = posterUrl,
            addedAt = existing?.addedAt ?: now,
            categoryOverride = "series",
            source = "magis",
            torrentData = seriesRef.ifBlank { existing?.torrentData.orEmpty() }.takeIf { it.isNotBlank() },
            episodiosVistosEnLista = episodiosVistosEnLista,
            // An absent tmdbId (TMDB didn't resolve this time, or the gateway is old) can't erase
            // the one that was already saved: imdb/tmdb refs don't change, so an old one is still
            // valid.
            tmdbId = tmdbId ?: existing?.tmdbId,
            // It's ALWAYS a season -- no need for tmdbId's `?:` here, there's no ambiguity to
            // preserve, every call to buildSeason is for a series.
            tipo = "tv",
            tituloCanonico = canonicalTitle(tituloCanonico, existing),
        )
        return item to chapters.map { chapterEntity(itemId, it.number, it.title, it.ref, seasonNumber) }
    }

    /**
     * One `episode_still` row per chapter TMDB was able to enrich (still, real name or synopsis)
     * -- the ones with nothing are left out, so the UI falls back to the portal's `displayName`
     * instead of showing an empty row.
     *
     * `episodeId` comes from [episodeIdFor], the SAME calculation [chapterEntity] uses for the
     * `EpisodeEntity`'s id: it's the key this row is cross-referenced against its episode by, and
     * if they didn't match the image would never show up.
     */
    fun seasonStills(
        itemId: String,
        chapters: List<SeasonChapter>,
        now: Long,
    ): List<EpisodeStillEntity> = chapters
        .filter { it.still != null || it.tmdbTitle != null || it.overview != null }
        .map { cap ->
            EpisodeStillEntity(
                episodeId = episodeIdFor(itemId, cap.number),
                stillUrl = cap.still,
                fetchedAt = now,
                title = cap.tmdbTitle,
                overview = cap.overview,
            )
        }

    /**
     * [episode] > 0 = a series chapter; 0 = movie (the path it always was, untouched).
     *
     * [ref] is the chapter's (or the movie's) and travels on the episode, which is what the player
     * resolves on playback. [seriesRef] is the SEASON's and travels on the item, which is what's
     * used to ask the portal whether a new chapter came out; blank does NOT overwrite what was
     * already saved, because refs expire and an expired one is better than none.
     *
     * [existing] is the row already in the database, if there is one: `upsertItem` is a REPLACE
     * (delete then insert), so anything not copied from there is lost -- the added-on date would
     * reorder the home screen and `episodiosVistosEnLista` would turn the badge back on over
     * chapters already watched.
     *
     * [season] and [tmdbId] are optional (default `null`) for compatibility, **not because there's
     * a path that doesn't care about them**: EVERY caller that can know the season has to pass it.
     * `ItemDao.upsertEpisodes` is an `@Insert(onConflict = REPLACE)`, so each of these calls
     * rewrites the episode's whole row; one without [season] ERASES the season on a chapter some
     * other path had already saved correctly, the item ends up with mixed episodes (some with a
     * season, some without) and `ArkivRepository.ensureEpisodeStills` falls to its branch that
     * flattens from S1 (see [chapterEntity]'s KDoc), silently overwriting the whole series' stills.
     *
     * Who passes the season today, and where they get it from:
     *  - `SearchPlayback.magisEpisodeIdFor` (the season dialog's "Save" button, phone and TV) and
     *    `NewChapterFinder.checkMagis` (new chapters in the background): from the
     *    `series` block's (`GatewaySeries`) `season_number`, returned by `MagisCatalog.detail`.
     *  - `SearchPlayback.magisEpisodeId` and `CineDetailScreen.playMagis` (a lone search result,
     *    no chapter list): from the `GatewayResult`'s own `season`.
     *
     * It's left `null` only when it's genuinely unknown: the gateway couldn't cross-reference the
     * series against TMDB, or the portal didn't send a season in the result. Making one up would
     * be worse.
     *
     * Same `?:` as [buildSeason] for [tmdbId]: an absent new one doesn't erase what was already
     * there.
     */
    fun build(
        contentId: String,
        ref: String,
        title: String,
        episode: Int,
        episodeTitle: String,
        posterUrl: String,
        now: Long,
        seriesRef: String,
        existing: ItemEntity?,
        season: Int? = null,
        tmdbId: Int? = null,
        tituloCanonico: String? = null,
    ): Pair<ItemEntity, EpisodeEntity> {
        val itemId = itemIdFor(contentId)
        val isChapter = episode > 0
        val item = ItemEntity(
            identifier = itemId,
            title = title.ifBlank { "Xuper" },
            description = null,
            thumbnailUrl = posterUrl,
            addedAt = existing?.addedAt ?: now,
            categoryOverride = if (isChapter) "series" else existing?.categoryOverride,
            source = "magis",
            torrentData = if (isChapter) {
                seriesRef.ifBlank { existing?.torrentData?.ifBlank { null } ?: ref }
            } else {
                ref
            },
            episodiosVistosEnLista = existing?.episodiosVistosEnLista,
            tmdbId = tmdbId ?: existing?.tmdbId,
            // `episode` says for certain whether this is a series chapter or a movie: no need for
            // tmdbId's `?:` here, each call knows which of the two it is.
            tipo = if (isChapter) "tv" else "movie",
            tituloCanonico = canonicalTitle(tituloCanonico, existing),
        )
        val ep = if (isChapter) {
            chapterEntity(itemId, episode, episodeTitle, ref, season = season)
        } else {
            EpisodeEntity(
                id = movieEpisodeId(itemId),
                itemId = itemId,
                section = "",
                displayName = MetadataParser.cleanName(title),
                orderIndex = 0,
                durationSeconds = 0.0,
                thumbPath = null,
                originalPath = null,
                originalFormat = null,
                originalSize = 0,
                derivativePath = null,
                derivativeFormat = null,
                derivativeSize = 0,
                torrentFileIndex = null,
                torrentData = ref,
            )
        }
        return item to ep
    }

    /**
     * The canonical title left after saving: the one that arrived, or the one already there.
     *
     * Blank counts as absent, not as a name: `GatewaySeries.title` comes in empty when the gateway
     * is old or TMDB didn't resolve, and adopting that string would leave the card WITHOUT TEXT.
     * And absent doesn't erase: [build] and [buildSeason] run on every save, so without this `?:`
     * a single pass with the gateway down would revert the card to the portal's name.
     */
    private fun canonicalTitle(newTitle: String?, existing: ItemEntity?): String? =
        newTitle?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.tituloCanonico
}
