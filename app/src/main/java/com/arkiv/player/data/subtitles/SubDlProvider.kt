package com.arkiv.player.data.subtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * SubDL's API v1: `GET /api/v1/subtitles` with the `api_key` in the query (how SubDL takes it), the
 * title by `imdb_id`/`tmdb_id` or `film_name`, `type` movie/tv with `season_number`/`episode_number`,
 * and `languages` as SubDL's uppercase codes. Each result's `url` is a path on `dl.subdl.com` that
 * serves a ZIP; [SubtitleZip] takes the subtitle (the episode's, from a season pack) out of it.
 *
 * SubDL answers a bad key with 401/403 or with `status: false` and an `error` naming the key; a title
 * it does not know is also `status: false`, read as no results.
 */
class SubDlProvider(
    private val client: OkHttpClient = SubtitleHttp.client(),
    private val baseUrl: String = BASE_URL,
    private val downloadBase: String = DOWNLOAD_BASE,
) : SubtitleProvider {

    override val id = SubtitleProviderId.SUBDL

    override suspend fun search(query: SubtitleQuery, auth: ProviderAuth): SubtitleResult<List<OnlineSubtitle>> = withContext(Dispatchers.IO) {
        if (query.isEmpty) return@withContext SubtitleResult.Ok(emptyList())
        call(searchUrl(baseUrl, query, auth.apiKey)) { parseSearch(it, query) }
    }

    override suspend fun download(subtitle: OnlineSubtitle, auth: ProviderAuth, query: SubtitleQuery): SubtitleResult<ByteArray> = withContext(Dispatchers.IO) {
        val url = downloadUrl(downloadBase, subtitle.ref) ?: return@withContext SubtitleResult.Failed(SubtitleFailure.NO_FILE)
        when (val r = SubtitleHttp.execute(client, Request.Builder().url(url).build())) {
            is SubtitleResult.Failed -> r
            is SubtitleResult.Ok -> when {
                r.value.code !in 200..299 -> SubtitleResult.Failed(SubtitleHttp.failureOf(r.value.code))
                else -> SubtitleZip.extract(r.value.body, query.season, query.episode)
                    ?.let { SubtitleResult.Ok(it) } ?: SubtitleResult.Failed(SubtitleFailure.NO_FILE)
            }
        }
    }

    /** A search for a title SubDL surely has: a bad key fails, a good one answers `status: true`. */
    override suspend fun test(auth: ProviderAuth): SubtitleResult<Unit> = withContext(Dispatchers.IO) {
        val probe = SubtitleQuery(isEpisode = false, imdbId = "tt0133093", languages = listOf("en"))
        when (val r = call(searchUrl(baseUrl, probe, auth.apiKey, perPage = 1)) { it }) {
            is SubtitleResult.Failed -> r
            is SubtitleResult.Ok -> SubtitleResult.Ok(Unit)
        }
    }

    /** GET [url], read the JSON envelope, and [parse] its body when `status` is true. */
    private fun <T> call(url: String, parse: (String) -> T): SubtitleResult<T> {
        val answer = when (val r = SubtitleHttp.execute(client, Request.Builder().url(url).header("Accept", "application/json").build())) {
            is SubtitleResult.Failed -> return r
            is SubtitleResult.Ok -> r.value
        }
        if (answer.code !in 200..299) return SubtitleResult.Failed(SubtitleHttp.failureOf(answer.code))
        return when (val env = envelope(answer.text)) {
            null -> SubtitleResult.Ok(parse(answer.text))
            else -> SubtitleResult.Failed(env)
        }
    }

    companion object {
        const val BASE_URL = "https://api.subdl.com/api/v1"
        const val DOWNLOAD_BASE = "https://dl.subdl.com"

        /** Results per page asked for (SubDL's own cap is 30). */
        private const val PER_PAGE = 30

        /** The search URL for [q] with [apiKey]. Never logged: the key travels in it. */
        fun searchUrl(base: String, q: SubtitleQuery, apiKey: String, perPage: Int = PER_PAGE): String {
            val p = linkedMapOf<String, String>()
            p["api_key"] = apiKey.trim()
            val imdb = q.imdbId?.trim()?.takeIf { q.imdbNumber != null }
            val tmdb = q.tmdbId?.takeIf { it > 0 }
            when {
                imdb != null -> p["imdb_id"] = imdb
                tmdb != null -> p["tmdb_id"] = tmdb.toString()
                else -> {
                    p["film_name"] = q.title.trim()
                    q.year?.let { p["year"] = it.toString() }
                }
            }
            p["type"] = if (q.isEpisode) "tv" else "movie"
            if (q.isEpisode) {
                q.season?.takeIf { it > 0 }?.let { p["season_number"] = it.toString() }
                q.episode?.takeIf { it > 0 }?.let { p["episode_number"] = it.toString() }
            }
            p["languages"] = q.languages.map { toSubDlLanguage(it) }.distinct().joinToString(",")
            p["subs_per_page"] = perPage.toString()
            return base + "/subtitles?" + p.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        }

        /**
         * A `status: false` answer as a failure: one naming the key is a bad key, one about limits
         * is the quota; any other (the title is unknown) is no failure at all -- null, no results.
         * Null too when the answer is fine.
         */
        internal fun envelope(json: String): SubtitleFailure? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return SubtitleFailure.UNAVAILABLE
            if (o.optBoolean("status", true) && o.optBoolean("success", true)) return null
            val error = o.optString("error").lowercase()
            return when {
                "key" in error || "unauthor" in error || "invalid token" in error -> SubtitleFailure.BAD_KEY
                "limit" in error || "quota" in error -> SubtitleFailure.QUOTA
                else -> null
            }
        }

        /** The subtitles of a search answer, an episode's only (or season packs) when [q] is one. */
        fun parseSearch(json: String, q: SubtitleQuery): List<OnlineSubtitle> = runCatching {
            val subs = JSONObject(json).optJSONArray("subtitles") ?: return emptyList()
            (0 until subs.length()).mapNotNull { i ->
                val s = subs.optJSONObject(i) ?: return@mapNotNull null
                val url = s.optString("url").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
                if (q.isEpisode && (q.episode ?: 0) > 0) {
                    val ep = s.optInt("episode", 0)
                    val pack = s.optBoolean("full_season", false)
                    if (ep > 0 && ep != q.episode && !pack) return@mapNotNull null
                }
                OnlineSubtitle(
                    provider = SubtitleProviderId.SUBDL,
                    ref = url,
                    language = fromSubDlLanguage(s.optString("language"), s.optString("lang")),
                    release = s.optString("release_name").takeIf { it.isNotBlank() && it != "null" }
                        ?: s.optString("name").takeIf { it != "null" }.orEmpty(),
                    downloads = s.optInt("downloads", 0),
                    hearingImpaired = s.optBoolean("hi", false),
                )
            }
        }.getOrDefault(emptyList())

        /** `dl.subdl.com` + the result's path; a full URL on another host is refused (null). */
        fun downloadUrl(base: String, ref: String): String? = when {
            ref.startsWith("/") -> base + ref
            ref.startsWith(base) -> ref
            ref.startsWith("http") -> null
            else -> "$base/$ref"
        }

        /** ISO 639-1 → SubDL's code: uppercase, Brazilian Portuguese is "BR_PT". */
        internal fun toSubDlLanguage(iso: String): String = when (iso.lowercase()) {
            "pt-br", "pb" -> "BR_PT"
            else -> iso.uppercase()
        }

        /** SubDL's `language` code (or its `lang` name) → ISO 639-1 lowercase. */
        internal fun fromSubDlLanguage(code: String, name: String): String {
            val c = code.trim().lowercase().takeIf { it.isNotEmpty() && it != "null" }
            if (c != null) return if (c == "br_pt") "pt-br" else c
            return when (name.trim().lowercase()) {
                "spanish" -> "es"
                "english" -> "en"
                "japanese" -> "ja"
                "portuguese" -> "pt"
                "brazillian portuguese", "brazilian portuguese" -> "pt-br"
                "french" -> "fr"
                else -> ""
            }
        }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%2C", ",")
    }
}
