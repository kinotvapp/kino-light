package com.arkiv.player.data.sync

import com.arkiv.player.data.ChapterMarker
import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.db.SkipMarkerEntity
import org.json.JSONObject

/**
 * Entity (Room) <-> JSON mapping for the companion LAN sync. Pure (no Android, no coroutines):
 * only the six user-data tables that travel between paired devices. Natural keys per table:
 * items=identifier, episodes=epId(=EpisodeEntity.id), playback=episodeId,
 * skip_markers=markerId(=SkipMarkerEntity.id), live_favorites/live_recents=code.
 *
 * Every mapper below selects SYNCED FIELDS ONLY: identity + shared metadata + `updatedAt` (+
 * `deleted` where the table has a tombstone). Device-local columns (download/thumbnail paths,
 * formats, sizes, `torrentFileIndex`) are never read or written here -- the peer restores them
 * locally (see Task 3's merge).
 */

private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

// ---- items <-> ItemEntity ----
// `episodiosVistosEnLista` is device-local (the "new episodes" badge counter as of the last time
// THIS device opened the series) and is intentionally excluded: adopting a peer's value would
// make the badge disappear on a device that never actually opened the detail screen.

fun itemToJson(entity: ItemEntity): JSONObject = JSONObject().apply {
    put("identifier", entity.identifier)
    put("title", entity.title)
    put("description", entity.description)
    put("thumbnailUrl", entity.thumbnailUrl)
    put("addedAt", entity.addedAt)
    put("categoryOverride", entity.categoryOverride)
    put("source", entity.source)
    put("torrentData", entity.torrentData)
    put("updatedAt", entity.updatedAt)
    put("deleted", entity.deleted)
    put("tmdbId", entity.tmdbId)
    put("tipo", entity.tipo)
    put("tituloCanonico", entity.tituloCanonico)
}

fun jsonToItem(json: JSONObject): ItemEntity = ItemEntity(
    identifier = json.optString("identifier"),
    title = json.optString("title"),
    description = json.optStringOrNull("description"),
    thumbnailUrl = json.optString("thumbnailUrl"),
    addedAt = json.optLong("addedAt"),
    categoryOverride = json.optStringOrNull("categoryOverride"),
    source = json.optStringOrNull("source") ?: "archive",
    torrentData = json.optStringOrNull("torrentData"),
    updatedAt = json.optLong("updatedAt"),
    deleted = json.optBoolean("deleted"),
    // Not synced -- see the KDoc above. Stays at the entity default until this device opens it.
    episodiosVistosEnLista = null,
    // 0 counts as absent, not as a real tmdbId: PocketBase-era rows born without it read as 0.
    tmdbId = json.optIntOrNull("tmdbId")?.takeIf { it > 0 },
    tipo = json.optStringOrNull("tipo"),
    // Blank counts as absent, same reasoning as tmdbId's 0.
    tituloCanonico = json.optStringOrNull("tituloCanonico")?.takeIf { it.isNotBlank() },
)

// ---- episodes <-> EpisodeEntity ----
// Excludes thumbPath/originalPath/originalFormat/originalSize/derivativePath/derivativeFormat/
// derivativeSize/torrentFileIndex: device-local download state. `torrentData` stays IN -- for a
// per-episode torrent it's the identity needed to play the episode on the peer, not local state.

fun episodeToJson(entity: EpisodeEntity): JSONObject = JSONObject().apply {
    put("epId", entity.id)
    put("itemId", entity.itemId)
    put("section", entity.section)
    put("displayName", entity.displayName)
    put("orderIndex", entity.orderIndex)
    put("durationSeconds", entity.durationSeconds)
    put("season", entity.season)
    put("episode", entity.episode)
    put("torrentData", entity.torrentData)
    put("updatedAt", entity.updatedAt)
    put("deleted", entity.deleted)
}

fun jsonToEpisode(json: JSONObject): EpisodeEntity = EpisodeEntity(
    id = json.optString("epId"),
    itemId = json.optString("itemId"),
    section = json.optString("section"),
    displayName = json.optString("displayName"),
    orderIndex = json.optInt("orderIndex"),
    durationSeconds = json.optDouble("durationSeconds"),
    // Device-local download state -- not synced. Restored to their entity defaults here; Task 3's
    // merge is what keeps the local device's own values in place instead of overwriting them.
    thumbPath = null,
    originalPath = null,
    originalFormat = null,
    originalSize = 0L,
    derivativePath = null,
    derivativeFormat = null,
    derivativeSize = 0L,
    season = json.optIntOrNull("season"),
    episode = json.optIntOrNull("episode"),
    torrentFileIndex = null,
    torrentData = json.optStringOrNull("torrentData"),
    updatedAt = json.optLong("updatedAt"),
    deleted = json.optBoolean("deleted"),
)

// ---- playback <-> PlaybackEntity ----
// All-shared: every field round-trips.

fun playbackToJson(entity: PlaybackEntity): JSONObject = JSONObject().apply {
    put("episodeId", entity.episodeId)
    put("positionMs", entity.positionMs)
    put("durationMs", entity.durationMs)
    put("watched", entity.watched)
    put("lastPlayedAt", entity.lastPlayedAt)
    put("updatedAt", entity.updatedAt)
    put("deleted", entity.deleted)
}

fun jsonToPlayback(json: JSONObject): PlaybackEntity = PlaybackEntity(
    episodeId = json.optString("episodeId"),
    positionMs = json.optLong("positionMs"),
    durationMs = json.optLong("durationMs"),
    watched = json.optBoolean("watched"),
    lastPlayedAt = json.optLong("lastPlayedAt"),
    updatedAt = json.optLong("updatedAt"),
    deleted = json.optBoolean("deleted"),
)

// ---- skip_markers <-> SkipMarkerEntity ----

fun markerToJson(entity: SkipMarkerEntity): JSONObject = JSONObject().apply {
    // `markerId` is the natural field sync looks the remote row up by: it has to match the local
    // PK, or every device would create a brand new row on each push.
    put("markerId", entity.id)
    put("itemId", entity.itemId)
    put("episodeId", entity.episodeId)
    put("openingStartMs", entity.openingStartMs)
    put("openingEndMs", entity.openingEndMs)
    put("endingStartMs", entity.endingStartMs)
    put("updatedAt", entity.updatedAt)
    put("deleted", entity.deleted)
    put("origen", entity.origen)
}

fun jsonToMarker(json: JSONObject): SkipMarkerEntity {
    val itemId = json.optString("itemId")
    val episodeId = json.optString("episodeId")
    return SkipMarkerEntity(
        // An old record may not carry `markerId`: recompute it the same way, so a row created
        // before this field existed doesn't come in with a blank key.
        id = json.optStringOrNull("markerId") ?: ChapterMarker.idFor(itemId, episodeId),
        itemId = itemId,
        episodeId = episodeId,
        openingStartMs = json.optLongOrNull("openingStartMs"),
        openingEndMs = json.optLongOrNull("openingEndMs"),
        endingStartMs = json.optLongOrNull("endingStartMs"),
        updatedAt = json.optLong("updatedAt"),
        deleted = json.optBoolean("deleted"),
        // An old record doesn't carry `origen`: it comes in as manual, the same default the
        // entity has -- there's no way to tell whether it was AniSkip or a person, and treating
        // it as manual is the safe side (a future automatic pass won't overwrite it).
        origen = json.optStringOrNull("origen") ?: ChapterMarker.SOURCE_MANUAL,
    )
}

// ---- live_favorites <-> LiveFavoriteEntity ----
// Same shape as markers: LWW by updatedAt + tombstone (deleted).

fun liveFavoriteToJson(entity: LiveFavoriteEntity): JSONObject = JSONObject().apply {
    put("code", entity.code)
    put("nombre", entity.nombre)
    put("numero", entity.numero)
    put("logo", entity.logo)
    put("updatedAt", entity.updatedAt)
    put("deleted", entity.deleted)
}

fun jsonToLiveFavorite(json: JSONObject): LiveFavoriteEntity = LiveFavoriteEntity(
    code = json.optString("code"),
    nombre = json.optString("nombre"),
    numero = json.optInt("numero"),
    logo = json.optStringOrNull("logo"),
    updatedAt = json.optLong("updatedAt"),
    deleted = json.optBoolean("deleted"),
)

// ---- live_recents <-> LiveRecentEntity ----
// No `deleted`: this table carries no tombstone (pruned by age, not deleted by hand) -- see
// LiveRecentEntity's KDoc in data/db/Entities.kt.

fun liveRecentToJson(entity: LiveRecentEntity): JSONObject = JSONObject().apply {
    put("code", entity.code)
    put("nombre", entity.nombre)
    put("vistoAt", entity.vistoAt)
    put("updatedAt", entity.updatedAt)
}

fun jsonToLiveRecent(json: JSONObject): LiveRecentEntity = LiveRecentEntity(
    code = json.optString("code"),
    nombre = json.optString("nombre"),
    vistoAt = json.optLong("vistoAt"),
    updatedAt = json.optLong("updatedAt"),
)
