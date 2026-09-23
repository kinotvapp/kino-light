package com.arkiv.player.data.local

import com.arkiv.player.data.SeriesItemIds

/**
 * Origin of a library episode: its id + (torrent only) the index of the file inside the torrent.
 * It's also `DownloadDao.completedOrigins`'s projection, so the field names are that query's
 * aliases.
 */
data class EpisodeOrigin(val episodeId: String, val torrentFileIndex: Int?)

/**
 * Is this episode the SAME content as something already downloaded on the device?
 *
 * Needed because the same series can be saved under two different items (it came in through the
 * anime screen and through the series one; see [SeriesItemIds.canonicalSeriesId], which closes the
 * hole from here on but doesn't touch what's already saved). Without this, tapping "download" on
 * the duplicate item downloads the same gigabytes already on disk all over again.
 *
 * The identity is NOT the episodeId: it's the chapter's ORIGIN, which already travels inside the
 * id itself.
 *
 * **Note (torrent/web pruning):** this app no longer creates `torrent:*`/`web:*` items — the
 * `ArkivRepository` functions that generated them were deleted along with the torrent/web sources.
 * The prefixes below (`WEB_SERIES_PREFIX`/`TORRENT_SERIES_PREFIX`/`TORRENT_ANIME_PREFIX`) stay
 * alive only to detect duplicates in items from those sources that already existed in a user's
 * library BEFORE this branch; no equivalent path is needed for Magis/Ditu/archive.org because none
 * of the three can end up duplicated under two different items (see the last bullet):
 *
 * - `web:series:<seriesId>::<pageUrl hash>` (legacy). The suffix depends ONLY on the
 *   pageUrl, so two different items of the same chapter share a suffix. This is the real case that
 *   motivated all of this.
 * - `torrent:series:<seriesId>::<infohash>` and `torrent:anime:<anilistId>::<infohash>` (legacy).
 *   The suffix is the infohash; the file chosen inside the torrent lives apart, in
 *   `episodes.torrentFileIndex`, so the key includes it ("the infohash + the file"). If the two
 *   paths chose different files from the same pack, the duplicate isn't detected and it downloads
 *   anyway: downloading too much is preferred over blocking a legitimate download.
 * - archive.org (now removed too) -> the itemId WAS archive.org's identifier, unique; the same
 *   chapter could never end up under two different items. Same for Magis/Ditu (id derived from the
 *   portal's `contentId`/`ref`) and the legacy standalone web/torrent movies
 *   (`web:<pageUrl hash>::0`, `torrent:<infohash>::…`), where the suffix is a file index and
 *   comparing it across items would be straight-up wrong. None of those get a key, i.e. no check
 *   -- they don't need one.
 *
 * Pure and Room-free, like [DownloadQueuePolicy]/[FreeSpacePolicy]: [LocalDownloadManager] runs
 * the query and the decision is made here.
 */
object DuplicateDownloadPolicy {

    /**
     * [origin]'s origin key, or `null` if that episode can't have a twin under another item (see
     * the object's KDoc). Two episodes with the same key are literally the same file.
     */
    fun originKeyOf(origin: EpisodeOrigin): String? {
        val id = origin.episodeId
        val suffix = id.substringAfterLast("::", "")
        if (suffix.isBlank() || suffix == id) return null
        return when {
            id.startsWith(SeriesItemIds.WEB_SERIES_PREFIX) -> "web:$suffix"
            id.startsWith(SeriesItemIds.TORRENT_SERIES_PREFIX) ||
                id.startsWith(SeriesItemIds.TORRENT_ANIME_PREFIX) ->
                "torrent:$suffix#${origin.torrentFileIndex ?: -1}"
            else -> null
        }
    }

    /**
     * episodeId of an ALREADY COMPLETED download with the same origin as [target], or `null` if
     * none. [completed] are `downloads` rows in `completed` state with their `torrentFileIndex`.
     *
     * [target] itself is excluded: whether an episode is already downloaded is the queue's concern
     * ([LocalDownloadManager.enqueue] cuts it off earlier), not this cross-item detection.
     */
    fun completedDuplicateOf(target: EpisodeOrigin, completed: List<EpisodeOrigin>): String? {
        val key = originKeyOf(target) ?: return null
        return completed
            .firstOrNull { it.episodeId != target.episodeId && originKeyOf(it) == key }
            ?.episodeId
    }

    /**
     * Notice for the user when the queue skipped [skipped] downloads as duplicates. `null` = there's
     * nothing to notify. A pack sends ALL its results together so a single notice comes out instead
     * of one per chapter.
     */
    fun skippedNotice(skipped: Int): String? = when {
        skipped <= 0 -> null
        skipped == 1 -> "Ya lo tienes descargado en el dispositivo"
        else -> "$skipped capítulos ya estaban descargados en el dispositivo"
    }

    /**
     * Of [candidates] (absolute paths a `remove` is about to delete), the ones that **can** actually
     * be deleted: the ones no OTHER `downloads` row still declares as its file.
     *
     * Exists because two rows can share a file: when the worker finds that content was already on
     * disk under another item, it adopts the twin's file instead of re-downloading it (see
     * `LocalDownloadWorker.adoptTwinIfAlreadyDownloaded`). Deleting it from either one would leave
     * the other saying "Listo" over a file that's no longer there.
     *
     * Filters by PATH and not by episodeId on purpose: `LocalDownloadManager.remove` deletes
     * through two paths — the row's explicit path and a sweep by name (`sanitize(episodeId) +
     * "."`) — and the second one also reaches the shared file. Watch the asymmetry, which was
     * exactly the hole: the file is named after the ORIGINAL twin's episodeId, so removing the
     * adopter doesn't touch it, but removing the original would sweep it even if the explicit
     * delete had skipped it. With the path filter both paths end up covered by the same rule.
     *
     * The `.part` / `.part.src` files the sweep drags along are never another row's `filePath`, so
     * they keep getting deleted same as before.
     */
    fun deletablePaths(candidates: List<String>, referencedByOthers: Set<String>): List<String> =
        candidates.filter { it !in referencedByOthers }

    /** Shortcut of [deletablePaths] for a single path. */
    fun canDeleteFile(path: String, referencedByOthers: Set<String>): Boolean =
        deletablePaths(listOf(path), referencedByOthers).isNotEmpty()

    /** Label for the row skipped because the file was already on disk under another item. */
    const val ADOPTED_REASON = "Ya estaba descargado"
}
