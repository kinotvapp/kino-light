package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.model.Episode

/** Poster, title and source of the item the group belongs to. Comes from `LibraryRow`. */
data class DownloadItemMeta(
    val title: String,
    val thumbnailUrl: String,
    val source: String,
)

/** State of one of the series' episodes on the Downloads screen. */
sealed interface EpisodeDownloadStatus {
    /** Never queued: there's no row in `downloads` for this episode. */
    data object NotDownloaded : EpisodeDownloadStatus

    /** Has a row in `downloads`; the real state lives in `row.state` (see [LocalDownloadState]). */
    data class Tracked(val row: DownloadRow) : EpisodeDownloadStatus
}

/** One of the series' episodes merged with its download, if it has one. */
data class GroupedEpisode(
    val episode: Episode,
    val status: EpisodeDownloadStatus,
)

/** Header + episodes of an item (series or movie) for the Downloads screen. */
data class DownloadGroup(
    val itemId: String,
    val itemTitle: String,
    val itemThumbnailUrl: String,
    val source: String,
    /** ALL of the item's episodes, in natural order -- not just the ones that went through the queue. */
    val episodes: List<GroupedEpisode>,
) {
    /** A movie (a single episode) has nothing to fold: it's shown as a plain row. */
    val isSingleEpisode: Boolean get() = episodes.size <= 1
}

/**
 * Groups the download queue by item and builds each header's summary ("3 de 24 guardados · 1
 * bajando"). Pure: doesn't touch Room or WorkManager, so it's tested on the JVM without
 * Robolectric (same convention as [DownloadQueuePolicy]/[FreeSpacePolicy]/[StagingProgress]).
 */
object DownloadGroupPolicy {

    /**
     * [episodesByItem] ALWAYS carries the full list of each item's episodes (not just the queued
     * ones): the screen offers "download" for the ones that don't have a row yet. [itemMeta] is
     * (title, poster, source) by itemId, comes from `LibraryRow`.
     *
     * The groups' order is their order of appearance in [downloads] (which already arrives `ORDER
     * BY createdAt DESC` from `observeDownloadRows`): the item with the most recent activity stays
     * on top, same as the old flat list.
     *
     * An itemId with no metadata in [itemMeta] gets skipped -- can happen in the instant between an
     * episode getting queued and `observeLibrary()` resolving, or if the item got deleted from the
     * library with downloads still in the table. An itemId with no entry in [episodesByItem]
     * (`episodesOf`'s async fetch hasn't resolved yet) doesn't get skipped: it shows only the
     * episodes already known from [downloads] and fills in on its own as soon as the fetch
     * resolves, so the screen doesn't flash to "empty" while loading.
     */
    fun buildGroups(
        downloads: List<DownloadRow>,
        episodesByItem: Map<String, List<Episode>>,
        itemMeta: Map<String, DownloadItemMeta>,
    ): List<DownloadGroup> {
        val byItem = downloads.groupBy { it.itemId }
        val order = downloads.map { it.itemId }.distinct()
        return order.mapNotNull { itemId ->
            val meta = itemMeta[itemId] ?: return@mapNotNull null
            val trackedRows = byItem[itemId].orEmpty()
            val allEpisodes = episodesByItem[itemId]
            val grouped = if (allEpisodes != null) {
                val trackedByEpisode = trackedRows.associateBy { it.episodeId }
                allEpisodes.sortedBy { it.orderIndex }.map { ep ->
                    val status = trackedByEpisode[ep.id]?.let { EpisodeDownloadStatus.Tracked(it) }
                        ?: EpisodeDownloadStatus.NotDownloaded
                    GroupedEpisode(ep, status)
                }
            } else {
                trackedRows.map { row -> GroupedEpisode(row.toPlaceholderEpisode(), EpisodeDownloadStatus.Tracked(row)) }
            }
            DownloadGroup(itemId, meta.title, meta.thumbnailUrl, meta.source, grouped)
        }
    }

    /** "3 de 24 guardados · 1 bajando" -- omits zero-valued clauses. */
    fun summarize(episodes: List<GroupedEpisode>): String {
        val total = episodes.size
        var saved = 0
        var downloading = 0
        var staging = 0
        var queued = 0
        var failed = 0
        var needsConfirmation = 0
        for (grouped in episodes) {
            val row = (grouped.status as? EpisodeDownloadStatus.Tracked)?.row ?: continue
            when (row.state) {
                LocalDownloadState.COMPLETED -> saved++
                LocalDownloadState.DOWNLOADING -> downloading++
                LocalDownloadState.STAGING -> staging++
                LocalDownloadState.QUEUED -> queued++
                LocalDownloadState.FAILED -> failed++
                LocalDownloadState.NEEDS_CONFIRMATION -> needsConfirmation++
            }
        }
        val clauses = mutableListOf("$saved de $total guardados")
        if (downloading > 0) clauses += "$downloading bajando"
        if (staging > 0) clauses += "$staging preparando"
        if (queued > 0) clauses += "$queued en cola"
        if (failed > 0) clauses += "$failed con error"
        if (needsConfirmation > 0) clauses += "$needsConfirmation por confirmar"
        return clauses.joinToString(" · ")
    }

    /**
     * Episodes "cancel all" has to stop: queued or in flight. Meant to be passed, row by row, to
     * [LocalDownloadManager.cancel] -- it's the only path that actually cuts off the worker when
     * the one in flight is one of these (see `cancel`'s KDoc); calling it for the extra queued ones
     * too does nothing odd, because `cancel` already tells which row is running. Skipping this,
     * "cancel all" would only cross out queue rows and leave the in-progress download running on
     * its own -- the orphan-file bug that already got fixed once.
     */
    fun activeEpisodeIds(group: DownloadGroup): List<String> =
        group.episodes.mapNotNull { (it.status as? EpisodeDownloadStatus.Tracked)?.row }
            .filter {
                it.state == LocalDownloadState.QUEUED ||
                    it.state == LocalDownloadState.DOWNLOADING ||
                    it.state == LocalDownloadState.STAGING
            }
            .map { it.episodeId }

    /** Episodes "retry failed" has to re-queue. */
    fun failedEpisodeIds(group: DownloadGroup): List<String> =
        group.episodes.mapNotNull { (it.status as? EpisodeDownloadStatus.Tracked)?.row }
            .filter { it.state == LocalDownloadState.FAILED }
            .map { it.episodeId }

    /** All episodes with a row in `downloads` (any state), for "remove all". */
    fun trackedEpisodeIds(group: DownloadGroup): List<String> =
        group.episodes.mapNotNull { (it.status as? EpisodeDownloadStatus.Tracked)?.row?.episodeId }

    /**
     * The group's first episode that's already playable offline (download `COMPLETED`), in the
     * order [DownloadGroup.episodes] already carries them in (the series' natural order) -- not the
     * first one that finished downloading. Null if none is complete yet.
     */
    fun firstPlayableEpisodeId(group: DownloadGroup): String? =
        group.episodes
            .firstOrNull { (it.status as? EpisodeDownloadStatus.Tracked)?.row?.state == LocalDownloadState.COMPLETED }
            ?.episode?.id

    /**
     * Thumbnail to render for a Downloads row: the episode's own artwork ([episodeThumb], from
     * `Episode.thumbPath`/`DownloadRow.thumbPath`) when there is one, otherwise the item's poster
     * ([itemThumbnailUrl], from `items.thumbnailUrl`). Today's sources (Magis, Caracol) never set a
     * per-episode thumb, so in practice this always resolves to the item poster -- kept as a
     * fallback chain rather than always using the item poster so a future source that does provide
     * one is picked up for free.
     */
    fun rowThumbnail(episodeThumb: String?, itemThumbnailUrl: String): String = episodeThumb ?: itemThumbnailUrl

    /**
     * Rebuilds a minimal [Episode] from a `downloads` row, for the (transient) case where
     * `episodesOf(itemId)` hasn't resolved yet. Fields not carried by [DownloadRow] stay at their
     * neutral value: they're not shown in the row (see `DownloadItem` in the UI) and get replaced
     * by the real ones as soon as the full fetch arrives.
     */
    private fun DownloadRow.toPlaceholderEpisode(): Episode = Episode(
        id = episodeId,
        itemId = itemId,
        section = "",
        displayName = displayName,
        orderIndex = 0,
        durationSeconds = 0.0,
        thumbPath = thumbPath,
    )
}
