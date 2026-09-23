package com.arkiv.player.data

import com.arkiv.player.data.catalog.AnimeMappingRepository
import kotlinx.coroutines.CancellationException

/**
 * Translation between a saved series' LOCAL `identifier` and the "bare" `seriesId` it's known by
 * outside the item table, and the **single source of the canonical seriesId**.
 *
 * The now-removed torrent and web sources used to prefix the seriesId (`web:series:`/
 * `torrent:series:`) so that the same show saved from two different sources wouldn't collide in
 * the same `items` row; [seriesIdOrNull] reads that prefix back out. Those add paths are gone, so
 * no new item gets one of these prefixes, but the ones already in someone's library still do, and
 * [com.arkiv.player.data.LibraryGrouping] still needs to recognize them to group a legacy item
 * with its TMDB-matched counterpart.
 *
 * The SAME thing happened one level up, with the seriesId itself: the anime screen built it as
 * `"anilist$anilistId"` and the movie/series one as `imdbId ?: "tmdb$id"`, so the SAME series
 * entered the library under two different items depending on which screen the person came in
 * through (verified with DAN DA DAN: `web:series:tt30217403` with 24 chapters and
 * `web:series:anilist171018` with 1, the same chapter on both). With local downloads that means
 * downloading the same GB twice. That's why the criterion lives here, in
 * [canonicalSeriesId]/[animeSeriesId], and both screens use it.
 */
object SeriesItemIds {

    /** Prefix of a web series' local identifier (legacy: the web source was removed). */
    const val WEB_SERIES_PREFIX = "web:series:"

    /** Prefix of a torrent series' local identifier (legacy: the torrent source was removed). */
    const val TORRENT_SERIES_PREFIX = "torrent:series:"

    /** Prefix of a torrent anime's local identifier (legacy: the torrent source was removed). */
    const val TORRENT_ANIME_PREFIX = "torrent:anime:"

    /** A well-formed IMDb id: the only thing accepted as first preference. */
    private val IMDB_SHAPE = Regex("""^tt\d+$""")

    /**
     * External seriesId of a saved series' local identifier, or `null` if the identifier isn't
     * one of a saved series (a standalone item, a movie, …). Returning `null` instead of the raw
     * identifier is on purpose: [com.arkiv.player.data.LibraryGrouping] can then skip trying to
     * match a seriesId that doesn't exist.
     */
    fun seriesIdOrNull(identifier: String): String? = when {
        identifier.startsWith(WEB_SERIES_PREFIX) -> identifier.removePrefix(WEB_SERIES_PREFIX)
        identifier.startsWith(TORRENT_SERIES_PREFIX) -> identifier.removePrefix(TORRENT_SERIES_PREFIX)
        else -> null
    }

    /**
     * A series' canonical seriesId: **IMDb if there is one, otherwise `"tmdb$id"`, otherwise
     * `"anilist$id"`**.
     *
     * LITERALLY the preference the non-anime path already used (`d.imdbId.ifBlank {
     * "tmdb${d.id}" }`), with the same lax "not blank" criterion: anilist is left as the last
     * resort, for anime whose cross-mapping isn't known yet. Purely deliberate (the same
     * convention the download policies use): whoever needs the mapping looks it up outside and
     * passes in the ids already resolved.
     *
     * **Careful about tightening this up.** ANDROID's `org.json` returns the string `"null"` (not
     * `""`) when `optString` lands on a JSON `null`, and TMDB sends `"imdb_id": null` for series
     * with no IMDb: today those series are saved as `web:series:null`. Rejecting malformed ids
     * here would move them to `web:series:tmdb<id>` -- an identity change with NO mapping in
     * between, i.e. exactly the bug this file exists to close, but backward. (The JVM tests'
     * `org.json` DOES filter out the null, so no test would catch it.) Whoever needs to validate
     * the id's shape does it BEFORE calling here: see [normalizeImdbId], which [animeSeriesId]
     * uses for the Fribb dataset.
     */
    fun canonicalSeriesId(imdbId: String?, tmdbId: Int?, anilistId: Long? = null): String = when {
        !imdbId.isNullOrBlank() -> imdbId
        tmdbId != null -> "tmdb$tmdbId"
        else -> anilistSeriesId(anilistId)
    }

    /** `"anilist$id"`: anime's historical fallback, the one id that can always be built. */
    fun anilistSeriesId(anilistId: Long?): String = "anilist$anilistId"

    /**
     * `"tt123"` / already-unpacked `["tt123"]` / `"tt123,tt456"` → `"tt123"`; anything else → null.
     *
     * Only for the anime dataset's (Fribb) `imdb_id`, which brings the field sometimes as a list
     * and sometimes as several ids glued together with a comma. There it IS worth being strict:
     * that id is NEW to the app (anime didn't even look at the mapping before), so discarding it
     * doesn't move anything already saved, and a malformed id would be a different library item
     * than the one TMDB's path builds. Does NOT apply to the imdb that comes from TMDB -- see
     * [canonicalSeriesId].
     */
    fun normalizeImdbId(raw: String?): String? =
        raw?.substringBefore(',')?.trim()?.takeIf { IMDB_SHAPE.matches(it) }

    /**
     * An AniList anime's canonical seriesId, resolving the cross-mapping (imdb/tmdb) through
     * [mappings]. **The only point** through which anime should build its seriesId.
     *
     * It's `suspend` because the mapping can touch disk or network
     * ([AnimeMappingRepository.mappingFor]), so callers invoke it inside the coroutine they
     * already use to save; whatever needs the id in COMPOSITION resolves it in a `LaunchedEffect`
     * and shows the fallback meanwhile.
     *
     * A missing mapping or a failure resolving it does NOT break the save: it falls back to
     * `"anilist$id"`, exactly the usual behavior. The coroutine's cancellation DOES propagate (if
     * it didn't, a `runCatching` would swallow it and return a stale id).
     */
    suspend fun animeSeriesId(mappings: AnimeMappingRepository?, anilistId: Long?): String {
        if (mappings == null || anilistId == null) return anilistSeriesId(anilistId)
        val mapping = try {
            mappings.mappingFor(anilistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // normalizeImdbId only here: Fribb's imdb is the one that can come with several ids glued together.
        return canonicalSeriesId(normalizeImdbId(mapping?.imdbId), mapping?.tmdbId, anilistId)
    }
}
