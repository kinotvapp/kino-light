package com.arkiv.player.data.ditu

import org.json.JSONObject

/** A Caracol chapter. The [contentId] is all that's needed to resolve it. */
internal data class DituEpisode(
    val number: Int,
    val season: Int,
    val title: String,
    val contentId: String,
) {
    fun ref(): String = DituRef(contentId, "VOD").encode()
}

/** A season with what's needed to paint its screen. */
internal data class DituSeason(
    val episodes: List<DituEpisode>,
    val seriesTitle: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val season: Int = 1,
)

/**
 * A Caracol series' chapters.
 *
 * There are two shapes and they're not alike. A `BUNDLE` is ONE season and its chapters come
 * inside the detail. A `GROUP_OF_BUNDLES` is a series with several seasons: its child bundles have
 * to be requested and flattened.
 *
 * In that second case, **the season number is the bundle's position in the children's list**, not
 * the chapter's own `season`: a group's bundles usually all come with `season: 1`, and trusting
 * that would make the seasons overwrite each other.
 */
internal class DituEpisodes(private val client: DituClientLike) {

    suspend fun forRef(ref: DituRef): DituSeason =
        if (ref.contentType == "GROUP_OF_BUNDLES") fromGroup(ref.contentId) else fromBundle(ref.contentId, 0)

    private suspend fun fromGroup(groupId: String): DituSeason {
        val children = client.get(
            DituCatalog.TRAY,
            mapOf("filter_parentId" to groupId, "filter_contentType" to "BUNDLE"),
        )
        val ids = DituCatalog.containersFrom(children).mapNotNull { it.optString("id").takeIf { s -> s.isNotBlank() } }
        val all = mutableListOf<DituEpisode>()
        var first: DituSeason? = null
        ids.forEachIndexed { index, bundleId ->
            val t = fromBundle(bundleId, forcedSeason = index + 1)
            if (first == null) first = t
            all += t.episodes
        }
        val head = first
        return DituSeason(
            episodes = all,
            seriesTitle = head?.seriesTitle.orEmpty(),
            posterUrl = head?.posterUrl.orEmpty(),
            backdropUrl = head?.backdropUrl.orEmpty(),
            season = 1,
        )
    }

    /** [forcedSeason] at 0 means "use whatever the episode says". */
    private suspend fun fromBundle(bundleId: String, forcedSeason: Int): DituSeason {
        val detail = client.get("CONTENT/DETAIL/BUNDLE/$bundleId")
        val outer = DituCatalog.containersFrom(detail).firstOrNull()
            ?: return DituSeason(emptyList())
        val raw = outer.optJSONArray("containers")
        val episodes = buildList {
            for (i in 0 until (raw?.length() ?: 0)) {
                val ep = raw!!.optJSONObject(i) ?: continue
                add(episodeFrom(ep, forcedSeason, position = i + 1) ?: continue)
            }
        }
        val meta = outer.optJSONObject("metadata") ?: JSONObject()
        return DituSeason(
            episodes = episodes,
            seriesTitle = meta.optString("title").trim(),
            posterUrl = DituCatalog.posterFrom(outer),
            backdropUrl = DituCatalog.backdropFrom(outer),
            season = forcedSeason.takeIf { it > 0 } ?: (episodes.firstOrNull()?.season ?: 1),
        )
    }

    /**
     * [position] is the chapter's place within its bundle, starting at 1, and is the fallback
     * number when Caracol doesn't send `episodeNumber` or sends it as 0. With 0 the chapter
     * doesn't get saved as a chapter: `DituEntities.build` takes `episode = 0` as a movie, and
     * `ArkivRepository.addDituSource` leaves it as a standalone item with the chapter's id, outside
     * its series. The list would also show several "Episodio 0"s.
     */
    private fun episodeFrom(ep: JSONObject, forcedSeason: Int, position: Int): DituEpisode? {
        val id = ep.optString("id").takeIf { it.isNotBlank() } ?: return null
        // With no assetId there's nothing to play: offering it would promise something that fails on tap.
        DituCatalog.assetMaster(ep) ?: return null
        val m = ep.optJSONObject("metadata") ?: JSONObject()
        val number = m.optInt("episodeNumber", 0).takeIf { it > 0 } ?: position
        return DituEpisode(
            number = number,
            season = forcedSeason.takeIf { it > 0 } ?: m.optInt("season", 1).takeIf { it > 0 } ?: 1,
            title = m.optString("episodeTitle").trim().ifBlank { "Episodio $number" },
            contentId = id,
        )
    }
}
