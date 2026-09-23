package com.arkiv.player.data.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-mapping of an anime (Fribb/anime-lists dataset). Gives the IDs in other databases plus
 * the TVDB season. `episodeOffset` almost never comes in for TV (only OVAs/specials); the real
 * absolute offset is computed by AniList's traversal (see [AniListApi.absoluteOffset]).
 */
data class AnimeMapping(
    val anilistId: Long,
    val malId: Long? = null,
    val tvdbId: Long? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val simklId: Long? = null,
    val tvdbSeason: Int? = null,
    val episodeOffset: Int? = null,
)

/** Parser for Fribb's `anime-list-full.json` → map indexed by `anilist_id`. */
object FribbAnimeListParser {
    fun parse(json: String): Map<Long, AnimeMapping> = try {
        val arr = JSONArray(json)
        val result = mutableMapOf<Long, AnimeMapping>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val anilist = o.optLong("anilist_id", 0L)
            if (anilist <= 0L) continue
            result[anilist] = AnimeMapping(
                anilistId = anilist,
                malId = o.optLong("mal_id", 0L).takeIf { it > 0 },
                tvdbId = o.optLong("tvdb_id", 0L).takeIf { it > 0 },
                imdbId = imdbOf(o),
                tmdbId = tmdbOf(o),
                simklId = o.optLong("simkl_id", 0L).takeIf { it > 0 },
                tvdbSeason = intInObj(o.opt("season")),
                episodeOffset = intInObj(o.opt("episode_offset")),
            )
        }
        result
    } catch (e: Exception) {
        emptyMap()
    }

    // imdb_id can be an array (["tt..."]) or a bare string.
    private fun imdbOf(o: JSONObject): String? = when (val v = o.opt("imdb_id")) {
        is JSONArray -> v.optString(0).ifBlank { null }
        is String -> v.ifBlank { null }
        else -> null
    }

    // themoviedb_id can be {"tv":id} / {"movie":id} or a bare int.
    private fun tmdbOf(o: JSONObject): Int? = when (val v = o.opt("themoviedb_id")) {
        is JSONObject -> (v.optInt("tv", 0).takeIf { it > 0 } ?: v.optInt("movie", 0)).takeIf { it > 0 }
        is Number -> v.toInt().takeIf { it > 0 }
        else -> null
    }

    // season / episode_offset come as {"tvdb":n,"tmdb":n}; we take tvdb's.
    private fun intInObj(v: Any?): Int? = when (v) {
        is JSONObject -> if (v.has("tvdb")) v.optInt("tvdb") else null
        is Number -> v.toInt()
        else -> null
    }
}
