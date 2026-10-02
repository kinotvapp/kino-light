package com.arkiv.player.ui.search

import com.arkiv.player.AppGraph

/** Result of trying to prepare a playback: ready with an episodeId, or failed with a message to
 *  show the user (the same texts SearchScreen showed before the extraction). */
sealed class PlaybackResult {
    data class Ready(val episodeId: String) : PlaybackResult()
    data class Failed(val message: String) : PlaybackResult()
}

/**
 * Resolves a plugin result chosen in the search, saves it to the library via [AppGraph.repository]
 * and returns the episodeId ready to play. Extracted VERBATIM from the local functions that lived
 * in `SearchScreen` so the TV can reuse exactly the same logic without duplicating it. Non-Compose
 * on purpose: it only needs the dependency graph, not UI state.
 *
 * `preparing`/`playError`/`onPlay(epId)` still live in the composable that calls these methods:
 * this helper only resolves+saves and returns the result.
 */
class SearchPlayback(private val graph: AppGraph) {

    /** Plays a plugin movie: saves it (see `Arkivicom.addPluginMovie`) and returns where to navigate. */
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
     * tapped one -- looked up by season AND number, never by number alone: a multi-season series
     * has a chapter 1 in every season and by number another one's would play.
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
            backdropUrl = series?.backdropUrl.orEmpty().ifBlank { series?.backdropUrl.orEmpty() },
            tmdbId = series?.tmdbId?.takeIf { it > 0 } ?: season.extra["tmdbId"]?.toIntOrNull()?.takeIf { it > 0 },
            tituloCanonico = series?.takeIf { it.tmdbId > 0 }?.title,
        )
        return if (epId != null) PlaybackResult.Ready(epId)
        else PlaybackResult.Failed("No se pudo preparar el capítulo de ${season.extra["pluginName"] ?: "este plugin"}.")
    }

    /**
     * Saves the plugin series once (the same as [playPluginSeason]) and returns the library episode
     * id of each of [chosen], in order (null for one the save did not keep). For the info page's
     * chapter downloads: "Descargar temporada" must not save the whole list once per chapter.
     */
    suspend fun pluginEpisodeIdsFor(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapters: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        chosen: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ): List<String?> {
        val first = chosen.firstOrNull() ?: return emptyList()
        if (playPluginSeason(season, chapters, first, series) !is PlaybackResult.Ready) return chosen.map { null }
        return com.arkiv.player.data.PluginEntities.chapterIds(
            season.ref,
            chosen.map { com.arkiv.player.data.PluginChapter(it.number, it.title, it.ref, it.season ?: 1) },
        )
    }
}