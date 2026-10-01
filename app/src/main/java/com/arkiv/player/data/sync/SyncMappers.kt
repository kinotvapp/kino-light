package com.arkiv.player.data.sync

import com.arkiv.player.data.db.OwnListPartEntity
import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.live.OwnFormResult
import com.arkiv.player.data.live.OwnKind
import com.arkiv.player.data.live.OwnPastedList
import com.arkiv.player.data.live.OwnSourceForm
import com.arkiv.player.data.live.validate
import com.arkiv.player.data.ChapterMarker
import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.db.SkipMarkerEntity
import com.arkiv.player.data.gateway.LiveChannelKeys
import org.json.JSONObject

/**
 * Entity (Room) <-> JSON mapping for the companion LAN sync. Pure (no Android, no coroutines):
 * only the six user-data tables that travel between paired devices. Natural keys per table:
 * items=identifier, episodes=epId(=EpisodeEntity.id), playback=episodeId,
 * skip_markers=markerId(=SkipMarkerEntity.id), live_favorites/live_recents=(provider, code), sent as one live code in `code`.
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
//
// Identity travels as the channel's live code in `code` (`LiveChannelKeys.liveCode`): a Xuper row
// keeps its bare portal code, exactly as before v33, and a plugin row is `plugin:<pluginId>:<code>`.
// There is deliberately no separate `provider` field. A pre-v33 peer ignores unknown fields and
// keys its tables by `code` alone, so with a separate field it would store a plugin's `c1` over
// its own Xuper `c1` and echo that back as Xuper's. With the live code in `code`, it stores a
// harmless row under a code no portal channel has, and its echo (no provider, v32 keys only)
// decodes back to the same plugin row. Decoding keys on the parsed code only.

fun liveFavoriteToJson(entity: LiveFavoriteEntity): JSONObject = JSONObject().apply {
    put("code", LiveChannelKeys.liveCode(entity.provider, entity.code))
    put("nombre", entity.nombre)
    put("numero", entity.numero)
    put("logo", entity.logo)
    put("updatedAt", entity.updatedAt)
    put("deleted", entity.deleted)
}

/** Null when `code` is missing, empty or a malformed plugin live code: the row is skipped, never stored as Xuper's. */
fun jsonToLiveFavorite(json: JSONObject): LiveFavoriteEntity? {
    val (provider, code) = LiveChannelKeys.parse(json.optString("code")) ?: return null
    return LiveFavoriteEntity(
        code = code,
        nombre = json.optString("nombre"),
        numero = json.optInt("numero"),
        logo = json.optStringOrNull("logo"),
        updatedAt = json.optLong("updatedAt"),
        deleted = json.optBoolean("deleted"),
        provider = provider,
    )
}

// ---- live_recents <-> LiveRecentEntity ----
// No `deleted`: this table carries no tombstone (pruned by age, not deleted by hand) -- see
// LiveRecentEntity's KDoc in data/db/Entities.kt. Identity on the wire: same as live_favorites.

fun liveRecentToJson(entity: LiveRecentEntity): JSONObject = JSONObject().apply {
    put("code", LiveChannelKeys.liveCode(entity.provider, entity.code))
    put("nombre", entity.nombre)
    put("vistoAt", entity.vistoAt)
    put("updatedAt", entity.updatedAt)
}

/** Null when `code` is missing, empty or a malformed plugin live code (see [jsonToLiveFavorite]). */
fun jsonToLiveRecent(json: JSONObject): LiveRecentEntity? {
    val (provider, code) = LiveChannelKeys.parse(json.optString("code")) ?: return null
    return LiveRecentEntity(
        code = code,
        nombre = json.optString("nombre"),
        vistoAt = json.optLong("vistoAt"),
        updatedAt = json.optLong("updatedAt"),
        provider = provider,
    )
}

fun ownLiveSourceToJson(e: OwnLiveSourceEntity): JSONObject = JSONObject().apply {
    put("id", e.id)
    put("kind", e.kind)
    put("name", e.name)
    put("url", e.url)
    put("groupName", e.groupName)
    put("logo", e.logo)
    put("epgUrl", e.epgUrl)
    put("userAgent", e.userAgent)
    put("referer", e.referer)
    put("refreshHours", e.refreshHours)
    put("updatedAt", e.updatedAt)
    put("deleted", e.deleted)
    // Pasted lists only (an optional field: an older build ignores it, and refuses the row anyway for its url).
    e.contentDigest?.let { put("contentDigest", it) }
}

/**
 * Null for a row this build must not store: a peer's garbled or hostile data. Every field goes through
 * the same rules as the form ([OwnSourceForm.validate]: private hosts, header injection, logo, EPG,
 * lengths), plus an id that is one plain path segment and never a playlist code (`~...`). The urls come
 * back canonical so both devices hold the same text.
 */
fun jsonToOwnLiveSource(json: JSONObject): OwnLiveSourceEntity? {
    val id = json.optString("id").takeIf { it.isNotEmpty() && it.length <= 64 && ':' !in it && !it.startsWith("~") } ?: return null
    val kind = json.optString("kind").let { k -> OwnKind.entries.firstOrNull { it.name == k } } ?: return null
    val form = OwnSourceForm(
        kind = kind,
        name = json.optString("name"),
        url = json.optString("url"),
        groupName = json.optString("groupName"),
        logo = json.optString("logo"),
        epgUrl = json.optString("epgUrl"),
        userAgent = json.optString("userAgent"),
        referer = json.optString("referer"),
        refreshHours = json.optInt("refreshHours"),
    )
    val valid = form.validate(id, emptyList()) as? OwnFormResult.Valid ?: return null
    val deleted = json.optBoolean("deleted")
    // A pasted list (its url is `kino-list:<this id>`, checked by validate) names the text it uses; a tombstone may not.
    val pasted = OwnPastedList.isPasted(valid.source.url)
    val digest = json.optString("contentDigest").takeIf { pasted && OwnPastedList.isDigest(it) }
    if (pasted && digest == null && !deleted) return null
    return valid.source.copy(updatedAt = json.optLong("updatedAt"), deleted = deleted, contentDigest = digest)
}

fun ownListPartToJson(p: OwnListPartEntity): JSONObject = JSONObject().apply {
    put("sourceId", p.sourceId)
    put("part", p.part)
    put("parts", p.parts)
    put("digest", p.digest)
    put("data", p.data)
    put("updatedAt", p.updatedAt)
}

/** Null for a part this build must not store (bad id, counts, digest or data: [OwnPastedList.validPart]). */
fun jsonToOwnListPart(json: JSONObject): OwnListPartEntity? = OwnListPartEntity(
    sourceId = json.optString("sourceId"),
    part = json.optInt("part", -1),
    parts = json.optInt("parts", 0),
    digest = json.optString("digest"),
    data = json.optString("data"),
    updatedAt = json.optLong("updatedAt"),
).takeIf(OwnPastedList::validPart)
