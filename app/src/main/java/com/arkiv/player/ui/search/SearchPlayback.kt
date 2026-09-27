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
 * Resolves a source chosen in the search (Caracol or a plugin), saves it to the library via
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
     * Saves a Caracol result (a movie) and returns its episodeId. What's saved never expires
     * (see `DituRef`).
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
     * This is Caracol's path in the chapters window: tapping a
     * chapter brings to the library all the ones in the list the window already loaded with
     * `episodesWithSeries` on opening, so it costs no network call. Only writes through
     * `ArkivRepository.addDituSeason` -- `ditu:` ids, never `magis:` ones.
     *
     * The chosen one is NOT looked up by number alone: in a
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
     * -- never `magis:` ids -- and
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

    /** Plays a plugin movie: saves it (see `ArkivRepository.addPluginMovie`) and returns where to navigate. */
    suspend fun playPlugin(r: com.arkiv.player.data.gateway.GatewayResult): PlaybackResult {
        val epId = graph.repository.addPluginMovie(
            ref = r.ref, title = r.title,
            posterUrl = r.extra["poster"].orEmpty(), backdropUrl = r.extra["backdrop"].orEmpty(),
            // The plugin's ids.tmdb: TMDB art and the library's TMDB grouping (spec §3.3).
            tmdbId = r.extra["tmdbId"]?.toIntOrNull()?.takeIf { it > 0 },
        )
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar la reproducción de ${r.extra["pluginName"] ?: "este plugin"}.")
    }

    /**
     * Saves the whole plugin series with the chapters the list already loaded, and returns the
     * tapped one. Caracol's [playDituSeason] path, for plugins: looked up by season AND number.
     */
    suspend fun playPluginSeason(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapters: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        chosen: com.arkiv.player.data.gateway.GatewayEpisode,
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ): PlaybackResult {
        fun chapter(e: com.arkiv.player.data.gateway.GatewayEpisode) =
            com.arkiv.player.data.PluginChapter(e.number, e.title, e.ref, e.season ?: 1)
        val epId = graph.repository.addPluginSeason(
            seriesRef = season.ref,
            title = season.title,
            chapters = chapters.map(::chapter),
            chosen = chapter(chosen),
            posterUrl = season.extra["poster"].orEmpty().ifBlank { series?.posterUrl.orEmpty() },
            backdropUrl = season.extra["backdrop"].orEmpty().ifBlank { series?.backdropUrl.orEmpty() },
            tmdbId = series?.tmdbId?.takeIf { it > 0 } ?: season.extra["tmdbId"]?.toIntOrNull()?.takeIf { it > 0 },
            tituloCanonico = series?.takeIf { it.tmdbId > 0 }?.title,
        )
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar el capítulo de ${season.extra["pluginName"] ?: "este plugin"}.")
    }
}
