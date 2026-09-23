package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.ditu.DituSource
import com.arkiv.player.data.ditu.DituRef
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySeries

/**
 * A Caracol series' chapter, exactly as [DituEntities] needs it.
 *
 * Its own model and not `GatewayEpisode`, for the same reason as [SeasonChapter] in Magis: this is
 * pure/JVM and doesn't depend on the `gateway` package. The caller maps one to the other.
 *
 * [season] is the chapter's own (in a `GROUP_OF_BUNDLES`, its bundle's); null = unknown, and it's
 * saved as 1 ([DituEntities.savedSeason]).
 */
data class CaracolChapter(
    val number: Int,
    val title: String,
    val ref: String,
    val season: Int? = null,
)

/**
 * What [DituEntities.buildSeries] builds: the item, its episodes, and the episodeId of the
 * chapter that was tapped ([chosenId]), or null if that chapter wasn't saved with its own ref.
 */
data class CaracolSeries(
    val item: ItemEntity,
    val episodes: List<EpisodeEntity>,
    val chosenId: String?,
)

/**
 * Builds (item + episodes) from what arrives from Caracol. Pure/JVM (no `android.*`) so it can be
 * tested without Room; the repository only saves it ([ArkivRepository.addDituSource] for a movie
 * or a standalone chapter, [ArkivRepository.addDituSeason] for a whole series).
 *
 * Modeled after [MagisEntities], and smaller: Caracol never had old rows in this branch, so
 * there's no legacy ids to sweep or identity to repair.
 *
 * Unlike Magis, what's saved here keeps working afterward: the `ref` encodes Caracol ids, which
 * are stable (see [DituRef]). That ref goes in the EPISODE's `torrentData`, which is where
 * `PlayerViewModel.loadDitu` reads it from (via [ArkivRepository.magisRefForEpisode]) to resolve.
 */
object DituEntities {

    /**
     * Has to start with `"ditu:"`: that's what makes `PlayerSource.kindFor` return
     * `SourceKind.DITU` and the player go through `loadDitu`. With any other prefix, what's saved
     * would open with another source's player.
     */
    const val PREFIX = "ditu:"

    /** The item for a movie or for a whole series. */
    fun itemIdFor(contentId: String): String = PREFIX + contentId

    /** The id of a chapter inside its series' item. Same format as Magis. */
    fun episodeIdFor(itemId: String, number: Int): String = "$itemId::e$number"

    /** The id of a movie's single episode. */
    fun movieEpisodeId(itemId: String): String = "$itemId::0"

    /**
     * A chapter's id knowing its season: [episodeIdFor] as-is in S1, and with the season inside
     * from S2 onward.
     *
     * Exists because of `GROUP_OF_BUNDLES`: `DituEpisodes` flattens every season of the series
     * into a single list and takes each chapter's number from its bundle's `episodeNumber`, so two
     * seasons can each bring their own chapter 1 (that's how `DituEpisodesTest` builds it). With
     * [episodeIdFor] alone, S2's chapter 1 would fall into S1's chapter 1 row --
     * `upsertEpisodes` is a REPLACE -- and that chapter would end up playing the other one.
     */
    fun chapterEpisodeId(itemId: String, season: Int, number: Int): String =
        if (season <= 1) episodeIdFor(itemId, number) else "$itemId::t${season}e$number"

    /**
     * The season a chapter is saved with: the one that arrives, or 1 if none arrives (or it
     * arrives as 0). Same rule `DituEpisodes` uses to read a chapter that doesn't carry one.
     */
    fun savedSeason(season: Int?): Int = season?.takeIf { it > 0 } ?: 1

    /**
     * The season a Caracol chapter is saved with: the chapter's own ([GatewayEpisode.season],
     * which `DituSource` fills in per chapter) and [series]'s only when that's missing.
     *
     * Order matters: in a `GROUP_OF_BUNDLES`, [series]'s season is a single value (season 1)
     * flattened across every season in the group, so if it won, season 2's chapter 1 would save
     * as season 1's chapter 1 and overwrite its row (see [chapterEpisodeId]).
     *
     * Moved here from `ui/search/SearchPlayback.kt` once a second data-layer caller
     * (`NewChapterFinder.checkDitu`) needed it too, alongside `RecommendationAggregator`.
     */
    fun seasonForChapter(
        chapter: GatewayEpisode,
        series: GatewaySeries?,
    ): Int? = chapter.season ?: series?.seasonNumber

    /**
     * A Caracol chapter shaped the way [DituEntities] saves it, with the season from
     * [seasonForChapter].
     *
     * Callers that map a whole list AND a chosen chapter through this (like
     * `SearchPlayback.playDituSeason`) must map both with it: if they didn't pull the season from
     * the same place, the chosen chapter would be looked up in a season that isn't its own.
     */
    fun caracolChapter(
        chapter: GatewayEpisode,
        series: GatewaySeries?,
    ): CaracolChapter = CaracolChapter(
        number = chapter.number,
        title = chapter.title,
        ref = chapter.ref,
        season = seasonForChapter(chapter, series),
    )

    /**
     * The id a chapter is saved with, whether standalone ([build]) or with its series
     * ([buildSeries]): both build it via [chapterEntity], which pulls it from here. And
     * [chosenAmong] looks it up with this same calculation, so the chapter being searched for is
     * the one that was saved.
     */
    fun chapterId(itemId: String, season: Int?, number: Int): String =
        chapterEpisodeId(itemId, savedSeason(season), number)

    /**
     * The `contentId` of the item this goes to, or null if it can't be saved.
     *
     * - Movie ([episode] is 0): the item is itself, and its ref has to be a `VOD`. A series isn't
     *   saved this way: `DituResolve.vod` knows how to start a series' first chapter, but the card
     *   would be left as a movie and would always open that chapter. Chapters get chosen first.
     * - Chapter ([episode] > 0): the item is the series, so the contentId comes from [seriesRef],
     *   which has to be a series; the chapter's ref, a `VOD`.
     *
     * Both refs have to be Caracol's ([DituRef.decode]). This is the guard that keeps a ref from
     * another source from ever ending up saved with a `ditu:` id, which the player would send to
     * Caracol.
     */
    fun itemContentId(ref: String, seriesRef: String, episode: Int): String? {
        val own = DituRef.decode(ref) ?: return null
        if (own.isSeries) return null
        if (episode <= 0) return own.contentId
        val series = DituRef.decode(seriesRef) ?: return null
        return series.contentId.takeIf { series.isSeries }
    }

    /**
     * [episode] > 0 = a series chapter; 0 = movie.
     *
     * [ref] is the chapter's (or the movie's) and goes on the episode: it's what the player
     * resolves. [seriesRef] is the series' and goes on the item.
     *
     * [existing] is the row already in the database, if there is one: `upsertItem` is a REPLACE,
     * so anything not copied from there is lost (the added-on date would reorder the home
     * screen). Same care as [MagisEntities.build].
     *
     * [season] is never left null on a chapter: with no season it goes to 1, the same rule
     * `DituEpisodes` uses to read a chapter that doesn't carry one. A null season among others
     * with one would make `ArkivRepository.ensureEpisodeStills` flatten from S1 (see
     * `MagisEntities.chapterEntity`'s KDoc).
     *
     * The `orderIndex` from S2 onward is offset by [ORDER_PER_SEASON] per season: the library
     * orders by `orderIndex` (`ItemDao.getEpisodesOf`), and with the bare number a multi-season
     * series' chapters would come out interleaved. In S1 it's the number, same as in Magis.
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
        val item = itemFor(
            itemId = itemId, ref = ref, title = title, isChapter = isChapter, posterUrl = posterUrl,
            now = now, seriesRef = seriesRef, existing = existing,
            episodiosVistosEnLista = existing?.episodiosVistosEnLista,
            tmdbId = tmdbId, tituloCanonico = tituloCanonico,
        )
        val ep = if (isChapter) {
            chapterEntity(itemId, CaracolChapter(episode, episodeTitle, ref, season))
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
     * The WHOLE series: the item once, one episode for each of [chapters], and which of those
     * episodes is the [chosen] one (the chapter that was tapped, to play it).
     *
     * This is what gets saved when a chapter is tapped to watch it ([ArkivRepository.addDituSeason]),
     * with the list the chapters window already loaded: no network call at all. Runs on every
     * playback, so it has to be idempotent: ids derived from the content, and everything
     * `upsertItem` (REPLACE) would erase copied over from [existing], same as in [build].
     *
     * The item and each episode come from the SAME pieces [build] uses ([itemFor] and
     * [chapterEntity]): a chapter that was already saved standalone lands in that same row, not a
     * duplicate one.
     *
     * [episodiosVistosEnLista] arrives already re-sealed by the caller, as in
     * `MagisEntities.buildSeason`: the post-save total is the union of what was already in the
     * database with what [chapters] brings, and this function has nothing to read the database
     * with.
     */
    fun buildSeries(
        contentId: String,
        seriesRef: String,
        title: String,
        chapters: List<CaracolChapter>,
        chosen: CaracolChapter,
        posterUrl: String,
        now: Long,
        existing: ItemEntity?,
        episodiosVistosEnLista: Int?,
        tmdbId: Int? = null,
        tituloCanonico: String? = null,
    ): CaracolSeries {
        val itemId = itemIdFor(contentId)
        val item = itemFor(
            itemId = itemId, ref = "", title = title, isChapter = true, posterUrl = posterUrl,
            now = now, seriesRef = seriesRef, existing = existing,
            episodiosVistosEnLista = episodiosVistosEnLista,
            tmdbId = tmdbId, tituloCanonico = tituloCanonico,
        )
        // One episode per id: if Caracol repeated a season and number across two chapters, both
        // would land in the same row, so the last one is kept and exactly that reaches the database.
        val episodes = chapters.map { chapterEntity(itemId, it) }.associateBy { it.id }.values.toList()
        return CaracolSeries(item, episodes, chosenAmong(episodes, chosen))
    }

    /**
     * The ones from [chapters] that can be saved in the [seriesRef] series: the ones that pass
     * [itemContentId]'s guard, so that a ref that isn't Caracol's never ends up with a `ditu:` id.
     * A chapter at 0 doesn't get in either: with `episode = 0` that guard treats it as a movie,
     * and its contentId would be the chapter's, not the series'.
     */
    fun saveableChapters(seriesRef: String, chapters: List<CaracolChapter>): List<CaracolChapter> =
        chapters.filter { it.number > 0 && itemContentId(it.ref, seriesRef, it.number) != null }

    /**
     * The episodeId of the [chosen] chapter among [saved] (what [buildSeries] returned), or null
     * if it wasn't saved with its own ref.
     *
     * Looked up by season AND number, with the same calculation used to save it ([chapterId]),
     * never by number alone: in a `GROUP_OF_BUNDLES` the list brings S1's chapter 1 and S2's
     * chapter 1 (see [chapterEpisodeId]), and by number alone S2's would play S1's.
     *
     * And the row has to have the [chosen] one's ref: if Caracol repeated a season and number
     * across two chapters, both would go to the same id and [buildSeries] only saves one, so
     * playing that row would play a different one. With null, the caller falls back to saving the
     * chosen one alone.
     */
    fun chosenAmong(saved: List<EpisodeEntity>, chosen: CaracolChapter): String? =
        saved.firstOrNull { ep ->
            ep.id == chapterId(ep.itemId, chosen.season, chosen.number) && ep.torrentData == chosen.ref
        }?.id

    /**
     * The item, for [build] and [buildSeries]. A chapter ([isChapter]) goes in its series' item,
     * with the series' ref in `torrentData`; a movie carries its own ([ref]).
     */
    private fun itemFor(
        itemId: String,
        ref: String,
        title: String,
        isChapter: Boolean,
        posterUrl: String,
        now: Long,
        seriesRef: String,
        existing: ItemEntity?,
        episodiosVistosEnLista: Int?,
        tmdbId: Int?,
        tituloCanonico: String?,
    ) = ItemEntity(
        identifier = itemId,
        title = title.ifBlank { "Caracol" },
        description = null,
        // An empty poster doesn't erase the one already there.
        thumbnailUrl = posterUrl.ifBlank { existing?.thumbnailUrl.orEmpty() },
        addedAt = existing?.addedAt ?: now,
        categoryOverride = if (isChapter) "series" else existing?.categoryOverride,
        source = DituSource.SOURCE,
        torrentData = if (isChapter) seriesRef else ref,
        episodiosVistosEnLista = episodiosVistosEnLista,
        // An absent tmdbId doesn't erase the one already saved, same as in Magis.
        tmdbId = tmdbId ?: existing?.tmdbId,
        tipo = if (isChapter) "tv" else "movie",
        tituloCanonico = tituloCanonico?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.tituloCanonico,
    )

    /**
     * A single chapter's episode. Shared by [build] and [buildSeries] on purpose: `id` is the
     * primary key, so if the two paths didn't build it identically, saving the series would
     * duplicate a chapter that was already saved standalone. Same care as
     * `MagisEntities.chapterEntity`.
     */
    private fun chapterEntity(itemId: String, chapter: CaracolChapter): EpisodeEntity {
        val season = savedSeason(chapter.season)
        val number = chapter.number
        return EpisodeEntity(
            id = chapterId(itemId, chapter.season, number),
            itemId = itemId,
            section = "",
            displayName = "E$number" + chapter.title.trim().takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty(),
            orderIndex = (season - 1) * ORDER_PER_SEASON + number,
            durationSeconds = 0.0,
            thumbPath = null,
            originalPath = null,
            originalFormat = null,
            originalSize = 0,
            derivativePath = null,
            derivativeFormat = null,
            derivativeSize = 0,
            season = season,
            episode = number,
            torrentFileIndex = null,
            torrentData = chapter.ref,
        )
    }

    private const val ORDER_PER_SEASON = 10_000
}
