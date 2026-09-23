package com.arkiv.player.ui.search

import com.arkiv.player.AppGraph
import com.arkiv.player.data.DituEntities

/** Result of trying to prepare a playback: ready with an episodeId, or failed with a message to
 *  show the user (the same texts SearchScreen showed before the extraction). */
sealed class PlaybackResult {
    data class Ready(val episodeId: String) : PlaybackResult()
    data class Failed(val message: String) : PlaybackResult()
}

/**
 * Resolves a source chosen in the search (Magis or Caracol), saves it to the library via
 * [AppGraph.repository] and returns the episodeId ready to play. Extracted VERBATIM from the
 * local functions that lived in `SearchScreen` (playArchiveResult, among others) so the TV can
 * reuse exactly the same logic without duplicating it. Non-Compose on purpose: it only needs the
 * dependency graph, not UI state.
 *
 * `preparing`/`playError`/`onPlay(epId)` still live in the composable that calls these methods:
 * this helper only resolves+saves and returns the result.
 */
class SearchPlayback(private val graph: AppGraph) {

    /**
     * Saves a Magis result and returns its episodeId.
     *
     * The id comes from the portal's `contentId`, not from the ref: the ref gets re-emitted on
     * every search and an id derived from it would lose the playback position. The ref is saved
     * alongside and refreshed.
     *
     * The season comes from the result itself (`GatewayResult.season`, which the portal sends in
     * the search) and is NOT left null: an episode with no `season` in an item where the others do
     * have one makes `ArkivRepository.ensureEpisodeStills` fall to its branch that flattens from
     * season 1 and overwrites the whole series' good stills (see `MagisEntities.build`'s KDoc). `0`
     * means "the portal didn't say", not season zero, hence the `takeIf`.
     */
    suspend fun magisEpisodeId(r: com.arkiv.player.data.gateway.GatewayResult): String? {
        val contentId = r.extra["content_id"].orEmpty()
        return graph.repository.addMagisSource(
            ref = r.ref, contentId = contentId, title = r.title, episode = r.episode,
            posterUrl = r.extra["poster"].orEmpty(), backdropUrl = r.extra["backdrop"].orEmpty(),
            season = r.season.takeIf { it > 0 },
        )
    }

    /**
     * Plays a standalone chapter from a Magis season.
     *
     * The chapter enters as an episode of the SEASON's item (one card per series, marked as a
     * series from the first chapter on; see `MagisEntities`), with its own "I'm here" mark.
     */
    /**
     * Saves the chapter and returns its episodeId, without navigating. Used by the batch save (the
     * "Guardar" button in the season dialog, on the phone and on the TV) and [playMagisSeason]'s fallback.
     *
     * [series] is the `series` block from the same response that brought [chapter] (null if the
     * gateway couldn't resolve it against TMDB), and **has no default on purpose**: saving without
     * it was the bug. `upsertEpisodes` is an `@Insert(onConflict = REPLACE)`, so this call rewrites
     * the episode's ENTIRE row; with no `season`, marking three chapters of a season already saved
     * by [playMagisSeason] erased their season number, and from there `ensureEpisodeStills` crosses
     * flattening from T1 and overwrites the whole series' stills (see `MagisEntities.build`'s
     * KDoc). Up until Task 5, `episodes` was also a synced table: that `season = null` traveled to
     * the other device too. With no cloud sync the damage stays contained to this device, but it's
     * still the same local bug.
     *
     * The chapter's three enriched fields (still, real name and synopsis) travel along the same as
     * the season: the dialog ALREADY has them in hand, and without passing them a chapter saved
     * without ever being played was left with no row in `episode_still` -- i.e. a black card in the
     * library until someone opened the series.
     */
    suspend fun magisEpisodeIdFor(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapter: com.arkiv.player.data.gateway.GatewayEpisode,
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ): String? = graph.repository.addMagisSource(
        ref = chapter.ref,
        contentId = season.extra["content_id"].orEmpty(),
        // The item's title is the SEASON's, not the chapter's: the item is the series, and the
        // chapter is named separately inside. Gluing them left cards like "Daima T1 · Daima T1_1".
        title = season.title,
        episode = chapter.number,
        episodeTitle = chapter.title,
        // The chapter inherits its SEASON's images: a GatewayEpisode brings none of its own.
        posterUrl = season.extra["poster"].orEmpty(),
        backdropUrl = season.extra["backdrop"].orEmpty(),
        // The season's ref is the one MagisCatalog.detail resolves; the chapter's isn't.
        seriesRef = season.ref,
        season = series?.seasonNumber,
        // Same shielding as in [playMagisSeason]: `tmdbId` comes from an `optInt`, so a missing
        // field would give 0 and that 0 would beat the `?:` that preserves the already-saved tmdbId.
        tmdbId = series?.tmdbId?.takeIf { it > 0 },
        // TMDB's name, so the card doesn't stay with the portal's.
        tituloCanonico = series?.title,
        still = chapter.still,
        tmdbTitle = chapter.tmdbTitle,
        overview = chapter.overview,
    )

    suspend fun playMagisEpisode(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapter: com.arkiv.player.data.gateway.GatewayEpisode,
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ): PlaybackResult {
        val epId = magisEpisodeIdFor(season, chapter, series)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar el capítulo.")
    }

    /**
     * Saves the ENTIRE season and returns the chapter that was tapped, to play it.
     *
     * Same idea that `playPackRow` (torrent) and `saveWebPack` (web) used to follow, before this
     * branch's pruning removed both: touching a chapter brings the whole season into the library,
     * not just that chapter. The list AND the series were already loaded by the screen
     * with `client.episodesWithSeries` when it opened, so this costs no network call.
     * **Downloads nothing**: the "Guardar" button still does that.
     *
     * [series] is the `series` block from that same response (null if the gateway couldn't resolve
     * the series against TMDB): that's where the `tmdbId` saved on the item comes from. It travels
     * as a parameter and isn't requested again in here -- this is the path playback goes through,
     * so a redundant round-trip is exactly what can't happen.
     *
     * If the season couldn't be saved (the portal sent no `content_id`), it falls back to the usual
     * path -- saving only the chapter -- rather than leaving the user with nothing to play.
     */
    suspend fun playMagisSeason(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapters: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        chosen: com.arkiv.player.data.gateway.GatewayEpisode,
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ): PlaybackResult {
        val saved = graph.repository.addMagisSeason(
            contentId = season.extra["content_id"].orEmpty(),
            title = season.title,
            chapters = chapters.map {
                com.arkiv.player.data.SeasonChapter(
                    number = it.number, title = it.title, ref = it.ref,
                    still = it.still, tmdbTitle = it.tmdbTitle, overview = it.overview,
                )
            },
            seriesRef = season.ref,
            posterUrl = season.extra["poster"].orEmpty(),
            backdropUrl = season.extra["backdrop"].orEmpty(),
            // `GatewaySeries.tmdbId` comes from an `optInt` (GatewayModels.kt): if the field were
            // missing it would give 0, not null, and that 0 would beat `buildSeason`'s `?:` and
            // erase a valid tmdbId already saved. Today the gateway only sends `series` when it DID
            // resolve, so this isn't reachable, but shielding it here costs nothing.
            tmdbId = series?.tmdbId?.takeIf { it > 0 },
        // TMDB's name, so the card doesn't stay with the portal's.
        tituloCanonico = series?.title,
            seasonNumber = series?.seasonNumber,
        )
        val epId = saved[chosen.number] ?: return playMagisEpisode(season, chosen, series)
        return PlaybackResult.Ready(epId)
    }

    /** Plays a Magis result: saves it and returns where to navigate. */
    suspend fun playMagis(r: com.arkiv.player.data.gateway.GatewayResult): PlaybackResult {
        val epId = magisEpisodeId(r)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar la reproducción de Xuper.")
    }

    /**
     * Saves a Caracol result (a movie) and returns its episodeId. Modeled on [magisEpisodeId],
     * with a difference that matters: what's saved never expires (see `DituRef`).
     *
     * The id comes from the `contentId` inside the ref (`ditu1:<contentType>:<contentId>`), and
     * the ref stays in the episode's `torrentData`: that's where `PlayerViewModel.loadDitu` reads
     * it from. For a series it returns null: its chapters get chosen first ([playDituSeason]). See
     * `DituEntities.itemContentId`.
     */
    suspend fun dituEpisodeId(r: com.arkiv.player.data.gateway.GatewayResult): String? =
        graph.repository.addDituSource(ref = r.ref, title = r.title, posterUrl = r.extra["poster"].orEmpty())

    /** Plays a Caracol movie: saves it and returns where to navigate. */
    suspend fun playDitu(r: com.arkiv.player.data.gateway.GatewayResult): PlaybackResult {
        val epId = dituEpisodeId(r)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar la reproducción de Caracol.")
    }

    /**
     * Saves the ENTIRE Caracol series and returns the chapter that was tapped, to play it.
     *
     * This is Caracol's path in the chapters window, modeled on [playMagisSeason]: tapping a
     * chapter brings to the library all the ones in the list the window already loaded with
     * `episodesWithSeries` on opening, so it costs no network call. Only writes through
     * `ArkivRepository.addDituSeason` -- `ditu:` ids, never through [playMagisSeason] or
     * [magisEpisodeIdFor], which build `magis:` ids.
     *
     * The chosen one is NOT looked up by number, unlike [playMagisSeason]: in a
     * `GROUP_OF_BUNDLES` the list brings a chapter 1 in every season, and by number another one's
     * would play. It's looked up by its season and its number (`DituEntities.chosenAmong`), and
     * the list and the chosen one both go through the same [DituEntities.caracolChapter], so
     * their season comes from the same [DituEntities.seasonForChapter].
     *
     * If the series couldn't be saved, or the chosen one didn't end up in it, falls back to
     * [playDituEpisode] -- saving only the chapter -- rather than leaving the person with nothing to play.
     */
    suspend fun playDituSeason(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapters: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        chosen: com.arkiv.player.data.gateway.GatewayEpisode,
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ): PlaybackResult {
        val epId = dituEpisodeIdFor(season, chapters, chosen, series)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar el capítulo de Caracol.")
    }

    /**
     * A Caracol chapter's `episodeId`, saving the entire series. It's [playDituSeason] without
     * playing: what the download button needs, which brings the chapter into the library exactly
     * the same way but sends it to the queue instead of the player.
     *
     * The fallback is the same: if the series couldn't be saved, only the chapter gets saved.
     */
    suspend fun dituEpisodeIdFor(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapters: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        chosen: com.arkiv.player.data.gateway.GatewayEpisode,
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ): String? = graph.repository.addDituSeason(
        seriesRef = season.ref,
        // The item is the series; each chapter is named separately, inside.
        title = season.title,
        chapters = chapters.map { DituEntities.caracolChapter(it, series) },
        chosen = DituEntities.caracolChapter(chosen, series),
        posterUrl = season.extra["poster"].orEmpty().ifBlank { series?.posterUrl.orEmpty() },
        backdropUrl = series?.backdropUrl.orEmpty(),
        // Same shielding as in [playDituEpisode]: a tmdbId of 0 doesn't overwrite one already saved.
        tmdbId = series?.tmdbId?.takeIf { it > 0 },
        // With no TMDB match, `GatewaySeries.title` is Caracol's name, not the canonical one.
        tituloCanonico = series?.takeIf { it.tmdbId > 0 }?.title,
    ) ?: standaloneDituEpisodeId(season, chosen, series)

    /**
     * Queues the download of [chosen] and returns how many entered the queue as NEW.
     *
     * Lives here and not in each screen because there are two -- Caracol's catalog and search --
     * and both have to save the chapter to the library before queuing it: with no row in
     * `episodes` the strategy can't find the `ref` and the download fails with "No se encontró la
     * fuente de Caracol".
     *
     * The source comes from [FuenteDeDescarga], not a hand-written `"ditu"`: the id already says
     * where the chapter came from, and it's the same decision the worker makes when choosing a strategy.
     */
    suspend fun enqueueCaracolDownload(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapters: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        chosen: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ): Int {
        var queued = 0
        for (chapter in chosen) {
            val epId = dituEpisodeIdFor(season, chapters, chapter, series) ?: continue
            val source = com.arkiv.player.data.local.DownloadSource.sourceFor(epId)
            if (graph.localDownloads.enqueue(epId, source) ==
                com.arkiv.player.data.local.EnqueueOutcome.QUEUED
            ) queued++
        }
        return queued
    }

    /**
     * Saves ONE chapter of a Caracol series and returns its episodeId, to play it.
     *
     * No longer the normal path: tapping a chapter, the chapters window calls [playDituSeason],
     * which saves the entire series. This is its fallback, for when the series couldn't be saved
     * or the chosen one didn't end up in it. Only writes through `ArkivRepository.addDituSource`
     * -- never through [playMagisSeason] or [magisEpisodeIdFor], which build `magis:` ids -- and
     * gives the chapter the same id [playDituSeason] gives it (both build it with `DituEntities`).
     *
     * The season is decided by [DituEntities.seasonForChapter].
     */
    suspend fun playDituEpisode(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapter: com.arkiv.player.data.gateway.GatewayEpisode,
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ): PlaybackResult {
        val epId = standaloneDituEpisodeId(season, chapter, series)
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar el capítulo de Caracol.")
    }

    /** The `episodeId` of ONE Caracol chapter saved standalone. [playDituEpisode]'s body. */
    private suspend fun standaloneDituEpisodeId(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapter: com.arkiv.player.data.gateway.GatewayEpisode,
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ): String? = graph.repository.addDituSource(
            ref = chapter.ref,
            seriesRef = season.ref,
            // The item is the series; the chapter is named separately, inside.
            title = season.title,
            episode = chapter.number,
            episodeTitle = chapter.title,
            posterUrl = season.extra["poster"].orEmpty().ifBlank { series?.posterUrl.orEmpty() },
            backdropUrl = series?.backdropUrl.orEmpty(),
            season = DituEntities.seasonForChapter(chapter, series),
            // `DituSource` leaves tmdbId at 0 when TMDB didn't find it: that 0 can't overwrite an
            // already-saved tmdbId.
            tmdbId = series?.tmdbId?.takeIf { it > 0 },
            // With no TMDB match, `GatewaySeries.title` is Caracol's name, not the canonical one.
            tituloCanonico = series?.takeIf { it.tmdbId > 0 }?.title,
        )
}
