package com.arkiv.player.data.sync

import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveRecentDao
import com.arkiv.player.data.db.PlaybackDao
import com.arkiv.player.data.db.SkipMarkerDao
import org.json.JSONObject

/**
 * Applies ONE incoming synced row locally, with last-write-wins by `updatedAt` (see [LwwMerge]).
 *
 * Decodes with Task 1's `jsonToX` and reads the local row by its natural-key getter (Task 2's
 * `getXSince` isn't used here -- this is a point read by PK, not a cursor scan). When the remote
 * doesn't strictly win, nothing happens: an older-or-equal incoming row is dropped, a tie keeps
 * the local row untouched.
 *
 * When it does win, the incoming row is upserted with its `updatedAt` written VERBATIM -- never
 * `System.currentTimeMillis()`. Re-seal prevention (so the local write triggers don't clobber a
 * cloud-origin `updatedAt`) is `SyncTriggers`' job, already covered elsewhere; this class's only
 * responsibility is to not touch the clock itself.
 *
 * Field-awareness: `jsonToItem`/`jsonToEpisode` reset device-local columns to the entity's
 * defaults, because those columns are excluded from the wire (see `SyncMappers`' KDoc). Upserting
 * that decoded entity as-is would WIPE the device's own data (the "new episodes" badge counter, a
 * download's file paths...). For `items`/`episodes` ONLY, the incoming entity is overlaid on top
 * of the LOCAL row's device-local columns before upserting. The other four tables (`playback`,
 * `skip_markers`, `live_favorites`, `live_recents`) are entirely shared columns, so they adopt the
 * whole decoded row -- including a tombstone (`deleted=true`), which is just a row like any other:
 * a newer tombstone wins and marks the local row deleted, same as any other field.
 */
class SyncApply(
    private val itemDao: ItemDao,
    private val playbackDao: PlaybackDao,
    private val skipMarkerDao: SkipMarkerDao,
    private val liveFavoriteDao: LiveFavoriteDao,
    private val liveRecentDao: LiveRecentDao,
) {
    /** Production convenience: pulls the DAOs out of the Room database. */
    constructor(db: ArkivDatabase) : this(
        db.itemDao(),
        db.playbackDao(),
        db.skipMarkerDao(),
        db.liveFavoriteDao(),
        db.liveRecentDao(),
    )

    suspend fun apply(table: String, row: JSONObject) {
        when (table) {
            "items" -> applyItem(row)
            "episodes" -> applyEpisode(row)
            "playback" -> applyPlayback(row)
            "skip_markers" -> applyMarker(row)
            "live_favorites" -> applyLiveFavorite(row)
            "live_recents" -> applyLiveRecent(row)
            else -> throw IllegalArgumentException("SyncApply: unknown table \"$table\"")
        }
    }

    private suspend fun applyItem(row: JSONObject) {
        val incoming = jsonToItem(row)
        val local = itemDao.getItem(incoming.identifier)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: Long.MIN_VALUE, incoming.updatedAt)) return
        // Overlay: shared columns from the incoming (already decoded) entity, device-local column
        // (episodiosVistosEnLista) copied from the local row when there is one.
        val toStore = if (local != null) incoming.copy(episodiosVistosEnLista = local.episodiosVistosEnLista) else incoming
        itemDao.upsertItem(toStore)
    }

    private suspend fun applyEpisode(row: JSONObject) {
        val incoming = jsonToEpisode(row)
        val local = itemDao.getEpisode(incoming.id)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: Long.MIN_VALUE, incoming.updatedAt)) return
        // Overlay: shared columns from the incoming entity, device-local download state copied
        // from the local row when there is one.
        val toStore = if (local != null) {
            incoming.copy(
                thumbPath = local.thumbPath,
                originalPath = local.originalPath,
                originalFormat = local.originalFormat,
                originalSize = local.originalSize,
                derivativePath = local.derivativePath,
                derivativeFormat = local.derivativeFormat,
                derivativeSize = local.derivativeSize,
                torrentFileIndex = local.torrentFileIndex,
            )
        } else {
            incoming
        }
        itemDao.upsertEpisodes(listOf(toStore))
    }

    private suspend fun applyPlayback(row: JSONObject) {
        val incoming = jsonToPlayback(row)
        val local = playbackDao.get(incoming.episodeId)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: Long.MIN_VALUE, incoming.updatedAt)) return
        playbackDao.upsert(incoming)
    }

    private suspend fun applyMarker(row: JSONObject) {
        val incoming = jsonToMarker(row)
        val local = skipMarkerDao.getById(incoming.id)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: Long.MIN_VALUE, incoming.updatedAt)) return
        skipMarkerDao.upsert(incoming)
    }

    private suspend fun applyLiveFavorite(row: JSONObject) {
        val incoming = jsonToLiveFavorite(row)
        val local = liveFavoriteDao.get(incoming.code)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: Long.MIN_VALUE, incoming.updatedAt)) return
        liveFavoriteDao.save(incoming)
    }

    private suspend fun applyLiveRecent(row: JSONObject) {
        val incoming = jsonToLiveRecent(row)
        val local = liveRecentDao.get(incoming.code)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: Long.MIN_VALUE, incoming.updatedAt)) return
        liveRecentDao.record(incoming)
    }
}
