package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class TmdbCategory { POPULAR, TRENDING, TOP_RATED, NOW_PLAYING, UPCOMING }
data class TmdbGenre(val id: Int, val name: String)

/** Relative path (no auth) of a category's endpoint. Pure/testable. */
fun tmdbCategoryPath(type: String, category: TmdbCategory): String = when (category) {
    TmdbCategory.TRENDING -> "/trending/$type/week"
    TmdbCategory.POPULAR -> "/$type/popular"
    TmdbCategory.TOP_RATED -> "/$type/top_rated"
    TmdbCategory.NOW_PLAYING -> if (type == "tv") "/tv/on_the_air" else "/movie/now_playing"
    TmdbCategory.UPCOMING -> if (type == "tv") "/tv/airing_today" else "/movie/upcoming"
}

/** A catalog title (TMDB) — movie or series — with its Spanish title and the original. */
data class TmdbItem(
    val id: Int,
    val type: String,        // "movie" | "tv"
    val title: String,       // Spanish title (es-MX)
    val originalTitle: String,
    val posterUrl: String,
    val year: String,
    /** Landscape image (16:9). On TV the rows are painted with this, not the poster. */
    val backdropUrl: String = "",
    /** Synopsis in Spanish; TMDB sends it in the same list response. Empty if missing. */
    val overview: String = "",
) {
    val isSeries: Boolean get() = type == "tv"
}

/** A season (metadata; episodes are loaded separately by [TmdbApi.seasonEpisodes]). */
data class TmdbSeason(val seasonNumber: Int, val episodeCount: Int, val name: String)

/** A series identified by its IMDb id (the only thing the Magis portal publishes). */
data class TmdbSeriesByImdb(
    val tmdbId: Int,
    val title: String,
    val posterUrl: String,
    val backdropUrl: String,
)

/** An episode of a season. */
data class TmdbEpisode(
    val season: Int,
    val episode: Int,
    val name: String,
    val overview: String,
    val air: String,
    val stillUrl: String,
)

/** Base of TMDB's images. Outside the class so [parseSeasonEpisodes] stays pure. */
private const val TMDB_IMG = "https://image.tmdb.org/t/p"

// "null" is discarded just like empty: Android's `optString` returns that string when the JSON
// carries a real null, and TMDB sends `poster_path: null` often. Without this the URL
// ".../w500null" was built, which is a broken image instead of the card with no poster.
private fun tmdbImgUrl(path: String?, size: String): String =
    if (path.isNullOrBlank() || path == "null") "" else "$TMDB_IMG/$size$path"

/**
 * Parses `/tv/{id}/season/{n}`'s response. Pure/testable (no network).
 *
 * **`null` is not the same as an empty list**, and that's the whole reason this function exists
 * separately: `null` = the query couldn't be made (timeout, 429, 5xx, unreadable response), empty
 * list = TMDB answered and that season had no episodes. They used to both come out as
 * `emptyList()` and were indistinguishable, so `ArkivRepository.ensureEpisodeStills` marked as
 * "already asked" what was never actually asked: a single detail opening with no network wrote
 * every row as null and that series was left with no images or names forever.
 *
 * [seasonNumber] is the one requested, and is used as a fallback when the JSON doesn't carry
 * `season_number`.
 */
internal fun parseSeasonEpisodes(json: String?, seasonNumber: Int): List<TmdbEpisode>? {
    if (json == null) return null
    return runCatching {
        // `optJSONArray ?: JSONArray()` (empty list, not null) on purpose: a valid JSON with NO
        // episodes IS an answer, and has to be written as "asked and there was none".
        val eps = JSONObject(json).optJSONArray("episodes") ?: JSONArray()
        (0 until eps.length()).mapNotNull { i ->
            val e = eps.optJSONObject(i) ?: return@mapNotNull null
            TmdbEpisode(
                season = e.optInt("season_number", seasonNumber),
                episode = e.optInt("episode_number"),
                name = e.optString("name").ifBlank { "Episodio ${e.optInt("episode_number")}" },
                overview = e.optString("overview"),
                air = e.optString("air_date").take(10),
                stillUrl = tmdbImgUrl(e.optString("still_path"), "w300"),
            )
        }
        // A body that isn't even JSON (a gateway error, a wifi's captive portal) counts as
        // "couldn't be queried": retrying on the next opening is preferred over sealing the whole
        // series into null over a broken response.
    }.getOrNull()
}

/** Detail of a title: Spanish metadata + list of seasons (episodes separate). */
data class TmdbDetail(
    val id: Int,
    val type: String,
    val title: String,
    val originalTitle: String,
    val posterUrl: String,
    val backdropUrl: String,
    val overview: String,
    val year: String,
    val imdbId: String,
    val seasons: List<TmdbSeason>,
) {
    val isSeries: Boolean get() = type == "tv"
}

/**
 * Movie and series metadata via TMDB, in **Latin American Spanish (es-MX)**: gives titles,
 * synopses, seasons and episodes in Spanish, plus the original title.
 *
 * Talks DIRECTLY to `api.themoviedb.org` (sub-project 2A): it used to be a passthrough through the
 * gateway's `/v1/catalog/tmdb`, which was what set the key.
 *
 * The key is NOT compiled into the APK any more: `AppGraph.tmdbApi` reads it from
 * `RemoteCredentialsStore`, which only holds a value after the person activates the app (see
 * `docs/superpowers/specs/2026-09-15-split-credential-activation-design.md`).
 */
class TmdbApi(
    /** TMDB v3 key. The real value comes from `RemoteCredentialsStore` via `AppGraph.tmdbApi`;
     *  this default is the empty string so the class stays constructible in tests. v3 is used and
     *  not the v4 bearer, to avoid depending on a second secret. */
    private val apiKey: String = "",
    private val language: String = "es-MX",
    /** Parameterizable only for tests: production talks to TMDB. */
    private val baseUrl: String = BASE_TMDB,
    /**
     * A 45 s `callTimeout` ON TOP OF the loose timeouts: those reset with every byte that
     * arrives, so a response that trickles in would never time out without a cap on the whole
     * call (same reason as `AppGraph.portalHttp`'s `callTimeout`).
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    private val base: String get() = baseUrl

    /** Searches titles. type: "movie" | "tv". */
    suspend fun search(type: String, query: String, page: Int = 1): List<TmdbItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return list("$base/search/$type?$auth&query=${enc(q)}&page=$page&include_adult=false", type)
    }

    /** Mixed search (movies + series) for the search wizard. Ignores 'person' and collections. */
    suspend fun searchMulti(query: String, page: Int = 1): List<TmdbItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val json = get("$base/search/multi?$auth&query=${enc(q)}&page=$page&include_adult=false")
            ?: return@withContext emptyList()
        runCatching {
            val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
            (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { parseMultiItem(it) } }
        }.getOrDefault(emptyList())
    }

    /** Maps a /search/multi item by its media_type. Returns null for person/others. */
    internal fun parseMultiItem(o: JSONObject): TmdbItem? {
        val type = when (o.optString("media_type")) {
            "movie" -> "movie"
            "tv" -> "tv"
            else -> return null
        }
        return parseItem(o, type)
    }

    /** Popular titles (paginated). type: "movie" | "tv". */
    suspend fun browse(type: String, page: Int): List<TmdbItem> =
        list("$base/$type/popular?$auth&page=${page.coerceAtLeast(1)}", type)

    /** Curated list by category (popular/trending/top rated/now playing/upcoming). */
    suspend fun curated(type: String, category: TmdbCategory, page: Int): List<TmdbItem> =
        list("$base${tmdbCategoryPath(type, category)}?$auth&page=${page.coerceAtLeast(1)}", type)

    private val genreCache = java.util.concurrent.ConcurrentHashMap<String, List<TmdbGenre>>()

    /** Genres available for the type (cached in memory). */
    suspend fun genres(type: String): List<TmdbGenre> {
        genreCache[type]?.let { return it }
        return withContext(Dispatchers.IO) {
            val json = get("$base/genre/$type/list?$auth") ?: return@withContext emptyList()
            runCatching {
                val arr = JSONObject(json).optJSONArray("genres") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optInt("id", 0); val name = o.optString("name")
                    if (id == 0 || name.isBlank()) null else TmdbGenre(id, name)
                }
            }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) genreCache[type] = it }
        }
    }

    /** Discover by genre. */
    suspend fun discover(type: String, genreId: Int, page: Int): List<TmdbItem> =
        list("$base/discover/$type?$auth&with_genres=$genreId&sort_by=popularity.desc&page=${page.coerceAtLeast(1)}", type)

    private suspend fun list(url: String, type: String): List<TmdbItem> = withContext(Dispatchers.IO) {
        val json = get(url) ?: return@withContext emptyList()
        runCatching {
            val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
            (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { parseItem(it, type) } }
        }.getOrDefault(emptyList())
    }

    /** Detail with seasons. type: "movie" | "tv". */
    suspend fun detail(type: String, id: Int): TmdbDetail? = withContext(Dispatchers.IO) {
        val json = get("$base/$type/$id?$auth&append_to_response=external_ids,translations") ?: return@withContext null
        runCatching {
            val o = JSONObject(json)
            val isTv = type == "tv"
            val seasons = o.optJSONArray("seasons")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    TmdbSeason(
                        seasonNumber = s.optInt("season_number"),
                        episodeCount = s.optInt("episode_count"),
                        name = s.optString("name"),
                    )
                }
            } ?: emptyList()
            val imdb = o.optString("imdb_id").ifBlank {
                o.optJSONObject("external_ids")?.optString("imdb_id").orEmpty()
            }
            val localized = if (isTv) o.optString("name") else o.optString("title")
            val original = if (isTv) o.optString("original_name") else o.optString("original_title")
            TmdbDetail(
                id = id,
                type = type,
                title = localized,
                originalTitle = original,
                posterUrl = imgUrl(o.optString("poster_path"), "w500"),
                backdropUrl = imgUrl(o.optString("backdrop_path"), "w1280"),
                overview = o.optString("overview"),
                year = (if (isTv) o.optString("first_air_date") else o.optString("release_date")).take(4),
                imdbId = imdb,
                seasons = seasons,
            )
        }.getOrNull()
    }

    /**
     * Episodes of a series' season.
     *
     * Returns **`null` if the query couldn't be made** and an empty list if TMDB answered with no
     * episodes: see [parseSeasonEpisodes] for why the difference matters. Callers that only draw
     * a list can treat them the same (`.orEmpty()`); whoever caches to the database can't.
     */
    suspend fun seasonEpisodes(
        tvId: Int,
        seasonNumber: Int,
        /** To request the same season in another language (the English-synopsis fallback: TMDB
         *  returns an empty `overview` in es-MX quite often). `null` = the class' own. */
        languageOverride: String? = null,
    ): List<TmdbEpisode>? = withContext(Dispatchers.IO) {
        val auth = "api_key=$apiKey&language=${languageOverride ?: language}"
        parseSeasonEpisodes(get("$base/tv/$tvId/season/$seasonNumber?$auth"), seasonNumber)
    }

    /**
     * A raw call to a TMDB route with no method of its own in this class: the JSON exactly as the
     * server sends it, or `null` if the call fails. On IO, like the rest of this class.
     *
     * Used by the trivia fact ([com.arkiv.player.data.ArkivRepository.workSheetFor]) to request
     * `movie/{id}` and `tv/{id}` with their credits, and a series' specific episode.
     */
    internal suspend fun raw(path: String, append: String? = null): String? = withContext(Dispatchers.IO) {
        val appendQuery = append?.let { "&append_to_response=$it" }.orEmpty()
        get("$base/$path?$auth$appendQuery")
    }

    /**
     * The series TMDB knows with that IMDb id. It's the only EXACT match available for Magis's
     * episodes: the portal publishes the series' imdb in `keyWords`, and searching by title would
     * cross numbering schemes that don't correspond.
     */
    suspend fun seriesByImdb(imdbId: String): TmdbSeriesByImdb? = withContext(Dispatchers.IO) {
        if (!Regex("""^tt\d{7,}$""").matches(imdbId)) return@withContext null
        val json = get("$base/find/$imdbId?$auth&external_source=imdb_id") ?: return@withContext null
        runCatching {
            val tv = JSONObject(json).optJSONArray("tv_results")?.optJSONObject(0)
                ?: return@runCatching null
            val id = tv.optInt("id").takeIf { it > 0 } ?: return@runCatching null
            TmdbSeriesByImdb(
                tmdbId = id,
                title = tv.optString("name"),
                posterUrl = imgUrl(tv.optString("poster_path").takeIf { it.isNotBlank() }, "w500"),
                backdropUrl = imgUrl(tv.optString("backdrop_path").takeIf { it.isNotBlank() }, "w1280"),
            )
        }.getOrNull()
    }

    /**
     * All the backdrops (landscape) of a title, in w1280 URLs, from most voted to least.
     * No language filter (`include_image_language`) to bring in the widest variety of backgrounds.
     * type: "movie" | "tv".
     */
    suspend fun images(type: String, id: Int, limit: Int = 8): List<String> = withContext(Dispatchers.IO) {
        val json = get("$base/$type/$id/images?include_image_language=es,en,null")
            ?: return@withContext emptyList()
        runCatching {
            val arr = JSONObject(json).optJSONArray("backdrops") ?: JSONArray()
            (0 until arr.length())
                .mapNotNull { i -> arr.optJSONObject(i)?.optString("file_path")?.takeIf { it.isNotBlank() } }
                .map { imgUrl(it, "w1280") }
                .take(limit)
        }.getOrDefault(emptyList())
    }

    /** JSON text with a real null converted to empty: Android's `optString` returns the
     *  STRING "null" in that case (the JVM's org.json returns "", so a unit test does NOT
     *  reproduce it). Without this, the synopsis of whatever TMDB has no translation for was the
     *  word "null" — and that's how it got saved in the library. */
    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)

    private fun parseItem(o: JSONObject, type: String): TmdbItem? {
        val id = o.optInt("id", 0)
        if (id == 0) return null
        val isTv = type == "tv"
        val title = if (isTv) o.text("name") else o.text("title")
        if (title.isBlank()) return null
        return TmdbItem(
            id = id,
            type = type,
            title = title,
            originalTitle = if (isTv) o.text("original_name") else o.text("original_title"),
            posterUrl = imgUrl(o.text("poster_path"), "w500"),
            year = (if (isTv) o.text("first_air_date") else o.text("release_date")).take(4),
            backdropUrl = imgUrl(o.text("backdrop_path"), "w780"),
            overview = o.text("overview"),
        )
    }

    /** The key goes as a query parameter, which is how TMDB's v3 API authenticates. It used to
     *  carry only the language because the gateway set the key; this branch has no gateway. */
    private val auth get() = "api_key=$apiKey&language=$language"
    // Delegates to the helper above: a single definition of the image base for the class and
    // for the pure parsing.
    private fun imgUrl(path: String?, size: String): String = tmdbImgUrl(path, size)
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        /** Not configurable from Settings: the base is fixed at build time, like the key. */
        const val BASE_TMDB = "https://api.themoviedb.org/3"
    }

    private fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()
}
