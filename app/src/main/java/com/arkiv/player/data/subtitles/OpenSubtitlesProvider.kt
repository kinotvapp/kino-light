package com.arkiv.player.data.subtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * OpenSubtitles.com REST API v1, called from the device with an `Api-Key` (Kino's shared one or the
 * person's own) and a `User-Agent` naming the app, both required by the API.
 *
 * Search: `GET /subtitles`, parameters lowercase and in alphabetical order with ids as bare numbers
 * (the API answers anything else with a redirect to that canonical form). Download: `POST /download`
 * with the `file_id` gives a temporary link to the file itself. A download without an account
 * counts against the key's anonymous quota (few a day); with [ProviderAuth.username] set, it logs in
 * (`POST /login`) and the JWT's bigger per-user quota applies. 406 on `/download` is that quota.
 */
class OpenSubtitlesProvider(
    private val userAgent: String,
    private val client: OkHttpClient = SubtitleHttp.client(),
    private val baseUrl: String = BASE_URL,
) : SubtitleProvider {

    override val id = SubtitleProviderId.OPENSUBTITLES

    /** The last login: (username, password hash) → token and the API host it named. Process-only. */
    @Volatile private var session: Session? = null

    private class Session(val who: String, val token: String, val base: String)

    override suspend fun search(query: SubtitleQuery, auth: ProviderAuth): SubtitleResult<List<OnlineSubtitle>> = withContext(Dispatchers.IO) {
        if (query.isEmpty) return@withContext SubtitleResult.Ok(emptyList())
        val request = authed(Request.Builder().url(searchUrl(baseUrl, query)), auth.apiKey).get().build()
        when (val r = SubtitleHttp.execute(client, request)) {
            is SubtitleResult.Failed -> r
            is SubtitleResult.Ok -> if (r.value.code in 200..299) {
                SubtitleResult.Ok(parseSearch(r.value.text))
            } else {
                SubtitleResult.Failed(SubtitleHttp.failureOf(r.value.code))
            }
        }
    }

    override suspend fun download(subtitle: OnlineSubtitle, auth: ProviderAuth, query: SubtitleQuery): SubtitleResult<ByteArray> = withContext(Dispatchers.IO) {
        val fileId = subtitle.ref.toLongOrNull() ?: return@withContext SubtitleResult.Failed(SubtitleFailure.NO_FILE)
        val login = if (auth.username.isNotBlank() && auth.password.isNotBlank()) {
            when (val s = login(auth)) {
                is SubtitleResult.Failed -> return@withContext s
                is SubtitleResult.Ok -> s.value
            }
        } else {
            null
        }
        val body = JSONObject().put("file_id", fileId).toString().toRequestBody(JSON)
        val request = authed(Request.Builder().url("${login?.base ?: baseUrl}/download"), auth.apiKey)
            .apply { login?.let { header("Authorization", "Bearer ${it.token}") } }
            .post(body).build()
        val answer = when (val r = SubtitleHttp.execute(client, request)) {
            is SubtitleResult.Failed -> return@withContext r
            is SubtitleResult.Ok -> r.value
        }
        if (answer.code !in 200..299) {
            // An expired JWT reads as 401: log in again on the next try.
            if (answer.code == 401) session = null
            return@withContext SubtitleResult.Failed(SubtitleHttp.failureOf(answer.code))
        }
        val link = parseDownloadLink(answer.text) ?: return@withContext SubtitleResult.Failed(SubtitleFailure.UNAVAILABLE)
        // The link is a CDN's, not the API's: no key and no token go there.
        when (val file = SubtitleHttp.execute(client, Request.Builder().url(link).header("User-Agent", userAgent).build())) {
            is SubtitleResult.Failed -> file
            is SubtitleResult.Ok -> when {
                file.value.code !in 200..299 -> SubtitleResult.Failed(SubtitleHttp.failureOf(file.value.code))
                // Asked for no format: what comes is the original upload, nearly always SRT, maybe zipped.
                else -> SubtitleZip.extract(file.value.body, query.season, query.episode)
                    ?.let { SubtitleResult.Ok(it) } ?: SubtitleResult.Failed(SubtitleFailure.NO_FILE)
            }
        }
    }

    override suspend fun test(auth: ProviderAuth): SubtitleResult<Unit> = withContext(Dispatchers.IO) {
        if (auth.username.isNotBlank() && auth.password.isNotBlank()) {
            session = null
            return@withContext when (val s = login(auth)) {
                is SubtitleResult.Failed -> s
                is SubtitleResult.Ok -> SubtitleResult.Ok(Unit)
            }
        }
        // Any endpoint checks the key; this one is tiny.
        val request = authed(Request.Builder().url("$baseUrl/infos/formats"), auth.apiKey).get().build()
        when (val r = SubtitleHttp.execute(client, request)) {
            is SubtitleResult.Failed -> r
            is SubtitleResult.Ok -> if (r.value.code in 200..299) SubtitleResult.Ok(Unit) else SubtitleResult.Failed(SubtitleHttp.failureOf(r.value.code))
        }
    }

    /** The JWT for [auth]'s account, reused while the same account is set. */
    private fun login(auth: ProviderAuth): SubtitleResult<Session> {
        val who = auth.username.trim() + "\u0000" + auth.password.hashCode()
        session?.takeIf { it.who == who }?.let { return SubtitleResult.Ok(it) }
        val body = JSONObject().put("username", auth.username.trim()).put("password", auth.password).toString().toRequestBody(JSON)
        val request = authed(Request.Builder().url("$baseUrl/login"), auth.apiKey).post(body).build()
        val answer = when (val r = SubtitleHttp.execute(client, request)) {
            is SubtitleResult.Failed -> return r
            is SubtitleResult.Ok -> r.value
        }
        if (answer.code !in 200..299) return SubtitleResult.Failed(SubtitleHttp.failureOf(answer.code))
        val (token, host) = parseLogin(answer.text) ?: return SubtitleResult.Failed(SubtitleFailure.UNAVAILABLE)
        val base = host?.let { "https://$it/api/v1" } ?: baseUrl
        return SubtitleResult.Ok(Session(who, token, base).also { session = it })
    }

    private fun authed(b: Request.Builder, apiKey: String): Request.Builder = b
        .header("Api-Key", apiKey)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")

    companion object {
        const val BASE_URL = "https://api.opensubtitles.com/api/v1"
        private val JSON = "application/json".toMediaType()

        /**
         * The search URL for [q]: the strongest id (IMDb, then TMDB, then the title as `query`), the
         * series' (`parent_…`) plus season and episode for an episode; every parameter lowercase and
         * sorted by name, the canonical form the API does not redirect. A file hash adds `moviehash` and
         * `moviehash_match=include` (matches first, the rest still come).
         */
        fun searchUrl(base: String, q: SubtitleQuery): String {
            val p = sortedMapOf<String, String>()
            p["languages"] = q.languages.map { it.lowercase() }.distinct().sorted().joinToString(",")
            q.movieHash?.lowercase()?.takeIf { Regex("[0-9a-f]{16}").matches(it) }?.let {
                p["moviehash"] = it
                p["moviehash_match"] = "include"
            }
            val imdb = q.imdbNumber
            val tmdb = q.tmdbId?.takeIf { it > 0 }
            val prefix = if (q.isEpisode) "parent_" else ""
            when {
                imdb != null -> p["${prefix}imdb_id"] = imdb.toString()
                tmdb != null -> p["${prefix}tmdb_id"] = tmdb.toString()
                else -> {
                    p["query"] = q.title.trim().lowercase()
                    if (!q.isEpisode) q.year?.let { p["year"] = it.toString() }
                }
            }
            if (q.isEpisode) {
                q.season?.takeIf { it > 0 }?.let { p["season_number"] = it.toString() }
                q.episode?.takeIf { it > 0 }?.let { p["episode_number"] = it.toString() }
                p["type"] = "episode"
            } else {
                p["type"] = "movie"
            }
            return base + "/subtitles?" + p.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        }

        /** The results of a search answer; an unreadable one is an empty list. */
        fun parseSearch(json: String): List<OnlineSubtitle> = runCatching {
            val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { i ->
                val attr = data.optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
                val file = attr.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
                val fileId = file.optLong("file_id", -1L).takeIf { it > 0 } ?: return@mapNotNull null
                val release = attr.optString("release").takeIf { it.isNotBlank() && it != "null" }
                    ?: file.optString("file_name").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                OnlineSubtitle(
                    provider = SubtitleProviderId.OPENSUBTITLES,
                    ref = fileId.toString(),
                    language = attr.optString("language").lowercase().let { if (it == "null") "" else it },
                    release = release,
                    downloads = attr.optInt("download_count", 0),
                    hearingImpaired = attr.optBoolean("hearing_impaired", false),
                    hashMatch = attr.optBoolean("moviehash_match", false),
                )
            }
        }.getOrDefault(emptyList())

        /** The temporary file link of a `/download` answer, or null. */
        fun parseDownloadLink(json: String): String? = runCatching {
            JSONObject(json).optString("link").takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }.getOrNull()

        /** The JWT and the API host (`base_url`, a VIP account's own) of a `/login` answer, or null. */
        fun parseLogin(json: String): Pair<String, String?>? = runCatching {
            val o = JSONObject(json)
            val token = o.optString("token").takeIf { it.isNotBlank() && it != "null" } ?: return null
            val host = o.optString("base_url").trim().removePrefix("https://").removeSuffix("/")
                .takeIf { it.endsWith("opensubtitles.com") && !it.contains('/') }
            token to host
        }.getOrNull()

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%2C", ",")
    }
}
