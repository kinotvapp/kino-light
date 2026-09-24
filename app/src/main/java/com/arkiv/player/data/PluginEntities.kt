package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.plugin.PluginRef

/** A plugin chapter as [PluginEntities] saves it; [season] already defaulted to ≥ 1. */
data class PluginChapter(val number: Int, val title: String, val ref: String, val season: Int)

data class PluginSeriesEntities(val item: ItemEntity, val episodes: List<EpisodeEntity>, val chosenId: String?)

/**
 * Library rows for plugin titles. Modeled on [DituEntities] — same episode-id scheme, same
 * `orderIndex` per season, same care with an existing row — because plugin item ids are stable by
 * contract, like Caracol's. Pure/JVM.
 *
 * Item id `plugin:<pluginId>:<itemId>` (see [PluginIds.itemIdFor]); `items.source =
 * "plugin:<pluginId>"`; the wrapped [PluginRef] goes in `torrentData` (the item's for a movie, each
 * chapter's on its episode), which is where `PlayerViewModel.loadPlugin` reads it from
 * (`ArkivRepository.magisRefForEpisode`). Every id starts with `plugin:`: that is what makes
 * `PlayerSource.kindFor` answer `SourceKind.PLUGIN`.
 */
object PluginEntities {
    private const val ORDER_PER_SEASON = 10_000

    fun movieEpisodeId(itemId: String): String = "$itemId::0"

    fun chapterId(itemId: String, season: Int, number: Int): String =
        if (season <= 1) "$itemId::e$number" else "$itemId::t${season}e$number"

    fun movieItemId(ref: String): String? =
        PluginRef.decode(ref)?.takeIf { it.kind == PluginRef.MOVIE }?.let { PluginIds.itemIdFor(it.pluginId, it.itemId) }

    fun seriesItemId(seriesRef: String): String? =
        PluginRef.decode(seriesRef)?.takeIf { it.kind == PluginRef.SERIES }?.let { PluginIds.itemIdFor(it.pluginId, it.itemId) }

    /** Only chapters that are episode refs of THIS series of THIS plugin, numbered > 0. */
    fun saveableChapters(seriesRef: String, chapters: List<PluginChapter>): List<PluginChapter> {
        val series = PluginRef.decode(seriesRef)?.takeIf { it.kind == PluginRef.SERIES } ?: return emptyList()
        return chapters.filter { c ->
            c.number > 0 && PluginRef.decode(c.ref)?.let {
                it.kind == PluginRef.EPISODE && it.pluginId == series.pluginId && it.itemId == series.itemId
            } == true
        }
    }

    fun buildMovie(ref: String, title: String, posterUrl: String, now: Long, existing: ItemEntity?): Pair<ItemEntity, EpisodeEntity>? {
        val r = PluginRef.decode(ref)?.takeIf { it.kind == PluginRef.MOVIE } ?: return null
        val itemId = PluginIds.itemIdFor(r.pluginId, r.itemId)
        val item = itemFor(itemId, r.pluginId, ref, title, isSeries = false, posterUrl, now, existing, existing?.episodiosVistosEnLista, null, null)
        val ep = EpisodeEntity(
            id = movieEpisodeId(itemId), itemId = itemId, section = "",
            displayName = MetadataParser.cleanName(title), orderIndex = 0, durationSeconds = 0.0,
            thumbPath = null, originalPath = null, originalFormat = null, originalSize = 0,
            derivativePath = null, derivativeFormat = null, derivativeSize = 0,
            torrentFileIndex = null, torrentData = ref,
        )
        return item to ep
    }

    fun buildSeries(
        seriesRef: String,
        title: String,
        chapters: List<PluginChapter>,
        chosen: PluginChapter,
        posterUrl: String,
        now: Long,
        existing: ItemEntity?,
        episodiosVistosEnLista: Int?,
        tmdbId: Int?,
        tituloCanonico: String?,
    ): PluginSeriesEntities? {
        val s = PluginRef.decode(seriesRef)?.takeIf { it.kind == PluginRef.SERIES } ?: return null
        val itemId = PluginIds.itemIdFor(s.pluginId, s.itemId)
        val item = itemFor(itemId, s.pluginId, seriesRef, title, isSeries = true, posterUrl, now, existing, episodiosVistosEnLista, tmdbId, tituloCanonico)
        val episodes = saveableChapters(seriesRef, chapters).map { chapterEntity(itemId, it) }.associateBy { it.id }.values.toList()
        val chosenId = episodes.firstOrNull {
            it.id == chapterId(itemId, chosen.season.coerceAtLeast(1), chosen.number) && it.torrentData == chosen.ref
        }?.id
        return PluginSeriesEntities(item, episodes, chosenId)
    }

    private fun itemFor(
        itemId: String, pluginId: String, torrentData: String, title: String, isSeries: Boolean,
        posterUrl: String, now: Long, existing: ItemEntity?, episodiosVistosEnLista: Int?,
        tmdbId: Int?, tituloCanonico: String?,
    ) = ItemEntity(
        identifier = itemId,
        title = title.ifBlank { pluginId },
        description = null,
        thumbnailUrl = posterUrl.ifBlank { existing?.thumbnailUrl.orEmpty() },
        addedAt = existing?.addedAt ?: now,
        categoryOverride = if (isSeries) "series" else existing?.categoryOverride,
        source = PluginIds.sourceFor(pluginId),
        torrentData = torrentData,
        episodiosVistosEnLista = episodiosVistosEnLista,
        tmdbId = tmdbId ?: existing?.tmdbId,
        tipo = if (isSeries) "tv" else "movie",
        tituloCanonico = tituloCanonico?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.tituloCanonico,
    )

    private fun chapterEntity(itemId: String, c: PluginChapter): EpisodeEntity {
        val season = c.season.coerceAtLeast(1)
        return EpisodeEntity(
            id = chapterId(itemId, season, c.number), itemId = itemId, section = "",
            displayName = "E${c.number}" + c.title.trim().takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty(),
            orderIndex = (season - 1) * ORDER_PER_SEASON + c.number, durationSeconds = 0.0,
            thumbPath = null, originalPath = null, originalFormat = null, originalSize = 0,
            derivativePath = null, derivativeFormat = null, derivativeSize = 0,
            season = season, episode = c.number, torrentFileIndex = null, torrentData = c.ref,
        )
    }
}
