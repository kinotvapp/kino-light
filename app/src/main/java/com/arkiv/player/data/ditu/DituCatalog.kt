package com.arkiv.player.data.ditu

import org.json.JSONArray
import org.json.JSONObject

/** A Caracol catalog title: a series (`BUNDLE`/`GROUP_OF_BUNDLES`) or a movie (`VOD`). */
internal data class DituItem(
    val contentId: String,
    val title: String,
    val contentType: String,
    val posterUrl: String = "",
    val year: String = "",
) {
    val isMovie: Boolean get() = contentType == "VOD"
    fun ref(): String = DituRef(contentId, contentType).encode()
}

/** A Caracol live channel, with the `assetId` needed to resolve it. */
internal data class DituChannel(
    val channelId: Int,
    val name: String,
    val logoUrl: String,
    val assetId: Int,
    val order: Int = 0,
)

/**
 * What's there to watch on Caracol.
 *
 * The same endpoint serves both: `TRAY/SEARCH/VOD` with an empty `query` returns the whole catalog
 * (some 330 titles in a single call) and with `query` filled in, the search.
 */
internal class DituCatalog(private val client: DituClientLike) {

    suspend fun catalog(): List<DituItem> = itemsFrom(client.get(TRAY, mapOf("query" to "")))

    suspend fun search(q: String): List<DituItem> = itemsFrom(client.get(TRAY, mapOf("query" to q)))

    suspend fun channels(): List<DituChannel> {
        val json = client.get(LIVECHANNELS, mapOf("orderBy" to "orderId", "sortOrder" to "asc"))
        return containersFrom(json).mapNotNull { channelFrom(it) }
    }

    private fun itemsFrom(json: JSONObject): List<DituItem> =
        containersFrom(json).mapNotNull { itemFrom(it) }

    private fun itemFrom(c: JSONObject): DituItem? {
        val m = c.optJSONObject("metadata") ?: JSONObject()
        val type = m.optString("contentType").uppercase()
        val subtype = (m.optString("contentSubtype").ifBlank { m.optString("contentSubType") }).uppercase()
        val refType = when {
            type == "BUNDLE" || type == "GROUP_OF_BUNDLES" -> type
            type == "VOD" && subtype == "MOVIE" -> "VOD"
            // Everything else (clips, LIVE, promos) isn't something that can be opened as a title.
            else -> return null
        }
        val id = c.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = m.optString("title").trim().takeIf { it.isNotBlank() } ?: return null
        return DituItem(
            contentId = id,
            title = title,
            contentType = refType,
            posterUrl = posterFrom(c),
            year = yearFrom(m),
        )
    }

    private fun channelFrom(c: JSONObject): DituChannel? {
        val m = c.optJSONObject("metadata") ?: return null
        if (m.opt("isActive") != true) return null
        val id = m.optInt("channelId", 0).takeIf { it != 0 } ?: return null
        val name = m.optString("channelName").trim().takeIf { it.isNotBlank() } ?: return null
        // The assetId comes from HERE and not the EPG: the EPG returns an empty `assets` for the
        // current program, so that trip comes back with nothing.
        val assetId = assetMaster(c) ?: return null
        val assets = c.optJSONArray("assets") ?: JSONArray()
        val list = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val logo = list.firstNotNullOfOrNull { it.optString("logoMedium").takeIf { s -> s.isNotBlank() } }
            ?: list.firstNotNullOfOrNull { it.optString("logoBig").takeIf { s -> s.isNotBlank() } }
            ?: list.firstNotNullOfOrNull { it.optString("logoSmall").takeIf { s -> s.isNotBlank() } }
            ?: ""
        return DituChannel(
            channelId = id,
            name = name,
            logoUrl = logo,
            assetId = assetId,
            order = m.optInt("orderId", 0),
        )
    }

    internal companion object {
        const val TRAY = "TRAY/SEARCH/VOD"
        const val LIVECHANNELS = "TRAY/LIVECHANNELS"
        const val CDN_IMAGES = "https://image-registry.ditu.caracoltv.com/"
        const val POSTER = "portrait-thin-promotional-tablet.jpg"
        const val BACKDROP = "landscape-regular-clean-tablet.jpg"

        fun containersFrom(json: JSONObject): List<JSONObject> {
            val arr = json.optJSONObject("resultObj")?.optJSONArray("containers") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }

        /** Vertical poster from Caracol's own CDN; if there's no `pictureUrl`, the `posterList`'s `icon`. */
        fun posterFrom(c: JSONObject): String {
            val pic = (c.optJSONObject("metadata") ?: JSONObject()).optString("pictureUrl").trim()
            if (pic.isNotBlank()) return "$CDN_IMAGES$pic/$POSTER"
            val arr = c.optJSONArray("posterList") ?: return ""
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("fileType") == "icon" }
                ?.optString("fileUrl").orEmpty()
        }

        /** Landscape backdrop from Caracol's own CDN; "" if there's no `pictureUrl`. */
        fun backdropFrom(c: JSONObject): String {
            val pic = (c.optJSONObject("metadata") ?: JSONObject()).optString("pictureUrl").trim()
            return if (pic.isBlank()) "" else "$CDN_IMAGES$pic/$BACKDROP"
        }

        fun yearFrom(m: JSONObject): String {
            for (field in listOf("releaseDate", "releaseYear", "year")) {
                val v = m.optString(field)
                if (v.length >= 4 && v.take(4).all { it.isDigit() }) return v.take(4)
            }
            return ""
        }

        /** The MASTER asset's `assetId`, or the first one that has one. `null` if none. */
        fun assetMaster(c: JSONObject): Int? {
            val arr = c.optJSONArray("assets") ?: return null
            val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            return list.firstOrNull { it.optString("assetType") == "MASTER" && it.optInt("assetId", 0) != 0 }
                ?.optInt("assetId")
                ?: list.firstOrNull { it.optInt("assetId", 0) != 0 }?.optInt("assetId")
        }
    }
}
