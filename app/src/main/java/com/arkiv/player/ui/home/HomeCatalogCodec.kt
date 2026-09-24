package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogItem
import org.json.JSONArray
import org.json.JSONObject

/** A persisted home snapshot: the classified [rows] and the epoch-ms the portal fetch happened. */
data class CachedRows(val rows: List<MagisHomeRow>, val fetchedAt: Long)

/**
 * Turns the home's rows into a JSON string and back, so they survive process death in one Room TEXT
 * column (`home_catalog_cache.rowsJson`). Hand-rolled over bundled `org.json` -- the project has no
 * kotlinx.serialization/Gson and already serializes this way (see `ArtworkEntity.backdropsJson`).
 *
 * Pure and total: [decode] never throws on malformed input, it returns what it could read (an empty
 * list for junk). Nullable fields (`poster`, `score`, `backdrop`) are OMITTED when null and read
 * back as null; the rest carry [CatalogItem]'s own defaults when a key is missing, so an older
 * cache written before a field existed still decodes.
 */
object HomeCatalogCodec {

    fun encode(rows: List<MagisHomeRow>): String {
        val arr = JSONArray()
        for (row in rows) {
            arr.put(
                JSONObject()
                    .put("id", row.id)
                    .put("title", row.title)
                    .put("shown", encodeItems(row.shown))
                    .put("all", encodeItems(row.all)),
            )
        }
        return arr.toString()
    }

    fun decode(json: String): List<MagisHomeRow> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MagisHomeRow(
                id = o.optString("id"),
                title = o.optString("title"),
                shown = decodeItems(o.optJSONArray("shown")),
                all = decodeItems(o.optJSONArray("all")),
            )
        }
    }.getOrDefault(emptyList())

    private fun encodeItems(items: List<CatalogItem>): JSONArray {
        val arr = JSONArray()
        for (it in items) {
            val o = JSONObject()
                .put("id", it.id)
                .put("title", it.title)
                .put("durationS", it.durationS)
                .put("adult", it.adult)
                .put("ref", it.ref)
                .put("type", it.type)
                .put("genres", JSONArray(it.genres))
                .put("description", it.description)
                .put("episodeCount", it.episodeCount)
            if (it.poster != null) o.put("poster", it.poster)
            if (it.score != null) o.put("score", it.score)
            if (it.backdrop != null) o.put("backdrop", it.backdrop)
            arr.put(o)
        }
        return arr
    }

    private fun decodeItems(arr: JSONArray?): List<CatalogItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            CatalogItem(
                id = o.optString("id"),
                title = o.optString("title"),
                poster = if (o.has("poster") && !o.isNull("poster")) o.optString("poster") else null,
                durationS = o.optInt("durationS"),
                adult = o.optBoolean("adult"),
                ref = o.optString("ref"),
                type = o.optString("type", "movie"),
                genres = o.optJSONArray("genres").toStringList(),
                score = if (o.has("score") && !o.isNull("score")) o.optDouble("score") else null,
                backdrop = if (o.has("backdrop") && !o.isNull("backdrop")) o.optString("backdrop") else null,
                description = o.optString("description"),
                episodeCount = o.optInt("episodeCount"),
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }
}
