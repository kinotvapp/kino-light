package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.PluginEntities
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.plugin.PluginColors
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.plugin.PluginRef
import com.arkiv.player.ui.home.MagisDownloadActions
import com.arkiv.player.ui.search.PlaybackResult

/**
 * What only a plugin card carries. Travels in the route next to the common card fields, and is
 * decoded again here, so [com.arkiv.player.data.gateway.CatalogItem] stays source-neutral.
 */
data class PluginTitleExtras(
    val pluginName: String = "",
    /** The manifest's `#RRGGBB`, or "". */
    val color: String = "",
    val year: String = "",
    val tmdbId: Int = 0,
    val imdbId: String = "",
)

/**
 * A title an installed plugin published. A plugin either returns every episode of a series in ONE
 * list, each with its season (the Internet Archive one), or keeps each season as its own title and
 * names the others as siblings (Xuper); the page reads which from the listing. The library ids are
 * the `PluginEntities` ones, so progress and "Continuar viendo" read the same rows playback writes.
 * Plugin titles have no downloads (a v1 non-goal of the plugin system).
 */
class PluginTitleSource(
    private val extras: PluginTitleExtras,
    private val onPlayMovie: suspend (GatewayResult) -> PlaybackResult,
    private val onPlaySeason: suspend (GatewayResult, List<GatewayEpisode>, GatewayEpisode, GatewaySeries?) -> PlaybackResult,
) : TitleSource {

    override val downloads: MagisDownloadActions? = null
    override val badge: TitleBadge? =
        extras.pluginName.takeIf { it.isNotBlank() }?.let { TitleBadge(it, PluginColors.parse(extras.color)) }
    override val initialYear: String get() = extras.year
    override val showsFailureDetail: Boolean get() = true

    /**
     * The library item id, or an id no row can have when the ref is not a plugin ref of the item's
     * kind (a corrupt or foreign route): progress then reads as empty instead of crashing.
     */
    override fun itemId(item: CatalogItem): String {
        val id = if (item.type == "series") PluginEntities.seriesItemId(item.ref) else PluginEntities.movieItemId(item.ref)
        return id ?: "$UNKNOWN_ITEM:${item.id}"
    }

    /**
     * The sibling's own item id and wrapped series ref (`PluginContentSource` wrapped it, so the
     * same plugin answers for it). The title follows the native Magis rule, "Show T1" -> "Show T2",
     * when the plugin numbered the season; an unnumbered one keeps the current title.
     */
    override fun siblingItem(current: CatalogItem, season: SeasonRef): CatalogItem = current.copy(
        id = season.contentId,
        ref = season.ref,
        title = if (season.number > 0) seasonTitle(current.title, season.number) else current.title,
        episodeCount = 0,
    )

    override fun movieEpisodeId(item: CatalogItem): String = PluginEntities.movieEpisodeId(itemId(item))

    override fun chapterEpisodeId(item: CatalogItem, chapter: GatewayEpisode): String =
        PluginEntities.chapterId(itemId(item), chapter.seasonOrOne, chapter.number)

    /** What `SearchPlayback.playPlugin` / `playPluginSeason` read from a plugin result. */
    override fun gatewayResult(item: CatalogItem): GatewayResult {
        val pluginId = PluginRef.decode(item.ref)?.pluginId.orEmpty()
        return GatewayResult(
            source = PluginIds.sourceFor(pluginId),
            title = item.title.ifBlank { item.id },
            ref = item.ref,
            kind = if (item.type == "series") "series" else "movie",
            year = extras.year,
            extra = buildMap {
                item.poster?.takeIf { it.isNotBlank() }?.let { put("poster", it) }
                item.backdrop?.takeIf { it.isNotBlank() }?.let { put("backdrop", it) }
                put("pluginName", extras.pluginName)
                put("color", extras.color)
                put("pluginItemId", item.id)
                if (extras.tmdbId > 0) put("tmdbId", extras.tmdbId.toString())
                if (extras.imdbId.isNotBlank()) put("imdbId", extras.imdbId)
            },
        )
    }

    override suspend fun tmdbHint(item: CatalogItem): TmdbHint = TmdbHint(extras.tmdbId, extras.imdbId)

    override suspend fun playMovie(result: GatewayResult): PlaybackResult = onPlayMovie(result)

    override suspend fun playSeason(
        result: GatewayResult,
        chapters: List<GatewayEpisode>,
        chosen: GatewayEpisode,
        series: GatewaySeries?,
    ): PlaybackResult = onPlaySeason(result, chapters, chosen, series)

    private companion object {
        const val UNKNOWN_ITEM = "plugin-unknown"
    }
}
