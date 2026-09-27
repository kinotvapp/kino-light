package com.arkiv.player.ui.titleinfo

import com.arkiv.player.AppGraph
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.local.DownloadSource
import com.arkiv.player.data.plugin.PluginRef
import com.arkiv.player.ui.home.MagisDownloadActions
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback

/** What TMDB can be asked from: an id the source published, either may be empty. Never a title. */
data class TmdbHint(val tmdbId: Int = 0, val imdbId: String = "")

/** The name and color a source shows next to a title (a plugin's name and its manifest color). */
data class TitleBadge(val label: String, val colorArgb: Long)

/**
 * Everything the information page needs from the source a title came from, so the view model and
 * the two screens never name a source's ids or playback paths. `PluginTitleSource` is the one
 * implementation today (Xuper is a plugin; the native Magis source, and the title source that
 * wrapped it, are gone).
 *
 * Where a series' seasons come from is not the source's to declare: its listing says. When it names
 * sibling seasons (`SeriesListing.seasons`, each a separate title), choosing one swaps the page's
 * item for [siblingItem] and loads that title's chapters; when it names none, the seasons are read
 * from the chapter list and choosing one only changes which chapters are shown.
 */
interface TitleSource {
    /**
     * The item the page swaps to when the person picks [season], a sibling the listing named: the
     * same card under that season's id, ref and title, so its chapters load and its progress reads
     * under its own library id.
     */
    fun siblingItem(current: CatalogItem, season: SeasonRef): CatalogItem

    /** Null when the source cannot download; the screens then draw no download UI. */
    val downloads: MagisDownloadActions?
    val canDownload: Boolean get() = downloads != null

    val badge: TitleBadge? get() = null

    /** A year the source already knows, shown before TMDB answers. */
    val initialYear: String get() = ""

    /**
     * Whether the TV page prints a failure's own message under its fixed line. A plugin's message is
     * worded for the person ("Esto venía del plugin X, que ya no está instalado"); a source whose
     * raw cause is not (the native Magis one named the portal's host in English) keeps the fixed
     * line alone.
     */
    val showsFailureDetail: Boolean get() = false

    /** The library item id progress rows are keyed under. */
    fun itemId(item: CatalogItem): String
    fun movieEpisodeId(item: CatalogItem): String
    fun chapterEpisodeId(item: CatalogItem, chapter: GatewayEpisode): String

    /** The item as the playback and download code of the source expects it. */
    fun gatewayResult(item: CatalogItem): GatewayResult

    suspend fun tmdbHint(item: CatalogItem): TmdbHint
    suspend fun playMovie(result: GatewayResult): PlaybackResult
    suspend fun playSeason(
        result: GatewayResult,
        chapters: List<GatewayEpisode>,
        chosen: GatewayEpisode,
        series: GatewaySeries?,
    ): PlaybackResult
}

/** The source a route's [origin] names, wired to the app's real paths, for the page's [item]. */
internal fun titleSourceFor(graph: AppGraph, origin: TitleOrigin, item: CatalogItem): TitleSource = when (origin) {
    is TitleOrigin.Plugin -> pluginTitleSource(graph, origin.extras, PluginRef.decode(item.ref)?.pluginId.orEmpty())
}

/**
 * Wires [PluginTitleSource] to the app's real plugin playback paths, with the download actions
 * when [pluginId]'s titles download ([pluginTitlesDownload]: the recognized Xuper install only).
 */
internal fun pluginTitleSource(graph: AppGraph, extras: PluginTitleExtras, pluginId: String): PluginTitleSource {
    val playback = SearchPlayback(graph)
    return PluginTitleSource(
        extras = extras,
        onPlayMovie = { playback.playPlugin(it) },
        onPlaySeason = { season, chapters, chosen, series -> playback.playPluginSeason(season, chapters, chosen, series) },
        downloads = xuperDownloadActions(graph, playback)
            .takeIf { pluginTitlesDownload(pluginId, graph::isXuperPlugin, graph.downloadStrategies.keys) },
    )
}

/**
 * Wires [MagisDownloadActions] to the plugin save paths (the same ones playing uses, so the row a
 * download hangs off is the row playing writes) and the download queue, under the source a Xuper
 * plugin episode maps to (`DownloadSource.XUPER`).
 */
internal fun xuperDownloadActions(graph: AppGraph, playback: SearchPlayback) = MagisDownloadActions(
    // `playPlugin` only saves the movie and answers its episode id; the caller decides what to do with it.
    episodeIdForMovie = { (playback.playPlugin(it) as? PlaybackResult.Ready)?.episodeId },
    episodeIdsForChapters = { season, chapters, chosen, series -> playback.pluginEpisodeIdsFor(season, chapters, chosen, series) },
    sourceFor = { DownloadSource.sourceFor(it, graph::isXuperPlugin) },
    enqueue = { episodeId, source -> graph.localDownloads.enqueue(episodeId, source) },
)
