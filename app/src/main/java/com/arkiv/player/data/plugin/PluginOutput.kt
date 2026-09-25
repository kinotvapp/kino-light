package com.arkiv.player.data.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class PluginItem(
    val id: String, val ref: String, val title: String, val kind: String,
    val year: String = "", val poster: String = "", val backdrop: String = "",
    val overview: String = "", val lang: String = "", val quality: String = "",
    val originalTitle: String = "",
    val genres: List<String> = emptyList(),
    /** 0..10, or null when the plugin gave none. */
    val rating: Double? = null,
    /** 1..1000, or 0 when unknown. */
    val runtimeMinutes: Int = 0,
    val tmdbId: Int = 0,
    /** `tt` + 5..10 digits, or "". */
    val imdbId: String = "",
    val badges: List<String> = emptyList(),
)

/** [ref] non-null only when the plugin declares `browse`: the row gets "Ver más". */
data class PluginRow(val id: String, val title: String, val items: List<PluginItem>, val ref: String? = null)

/** A page of items; [next] is the opaque cursor the app passes back, or null at the end. */
data class PluginPage(val items: List<PluginItem>, val next: String?)

data class PluginSeriesInfo(
    val title: String = "", val poster: String = "", val backdrop: String = "",
    val overview: String = "", val tmdbId: Int = 0, val imdbId: String = "",
    val genres: List<String> = emptyList(), val year: String = "",
)

data class PluginEpisode(
    val season: Int, val number: Int, val ref: String,
    val title: String = "", val still: String = "", val overview: String = "",
    /** `YYYY-MM-DD`, or "". */
    val airDate: String = "",
    val runtimeMinutes: Int = 0,
)

data class PluginEpisodes(val series: PluginSeriesInfo?, val episodes: List<PluginEpisode>)

data class PluginSubtitle(val lang: String, val url: String, val format: String = "")

data class PluginStream(
    val url: String, val mime: String = "", val headers: Map<String, String> = emptyMap(),
    val subtitles: List<PluginSubtitle> = emptyList(), val durationMs: Long = 0,
    /** 30..86 400, or 0: after that long a failed playback resolves again once. */
    val expiresInSeconds: Int = 0,
)

/** A plugin answered something the contract doesn't allow; [message] is Spanish, shown to the person. */
class PluginContractException(message: String) : Exception(message)

/**
 * Strict reader of what a plugin returns (apiVersion 1). Lists are forgiving — a bad entry is
 * dropped with a [log] line, the rest survive — while a stream is all-or-nothing: an `http` URL, a
 * host the plugin may not reach, or any DRM field refuses the whole thing. Unknown fields are
 * ignored (forward compatibility). Images must be https and are the one thing NOT host-gated
 * (display-only, loaded by Coil without plugin headers), but an image on an IP literal or a local
 * name is dropped, so a poster can't probe the home network — unless it is exactly one of the
 * servers the person typed in the plugin's settings (see [EffectiveHosts]).
 */
object PluginOutput {
    const val MAX_SEARCH_ITEMS = 100
    const val MAX_BROWSE_ITEMS = 100
    const val MAX_ROWS = 20
    const val MAX_ROW_ITEMS = 60
    const val MAX_EPISODES = 5000
    const val MAX_REF_CHARS = 4096
    const val MAX_CURSOR_CHARS = 2048
    const val MAX_IMAGE_URL_CHARS = 2048
    const val MAX_GENRES = 5
    const val MAX_GENRE_CHARS = 30
    const val MAX_BADGES = 3
    const val MAX_BADGE_CHARS = 20
    const val MIN_EXPIRES_IN_SECONDS = 30
    const val MAX_EXPIRES_IN_SECONDS = 86_400
    const val MAX_RUNTIME_MINUTES = 1000
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_TEXT_CHARS = 2000
    private const val MAX_HEADERS = 20
    private const val MAX_SUBTITLES = 30

    val ID = Regex("^[A-Za-z0-9._~-]{1,128}$")
    val IMDB = Regex("^tt\\d{5,10}$")
    val AIR_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val MIME = Regex("^[a-z]+/[A-Za-z0-9.+-]{1,100}$")
    private val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
    private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection")
    private val DRM_KEYS = setOf("drm", "license", "licenseUrl", "drmLicenseUrl", "keySystem", "widevine")

    /**
     * `search` (≤ [MAX_SEARCH_ITEMS]) or `browse` (≤ [MAX_BROWSE_ITEMS]): an `Item[]` or a
     * `{ items, next }` page. [allowNext] is whether the plugin declares `browse`: without it a
     * `next` is dropped (logged), since nothing could ask for it.
     */
    fun page(
        json: String,
        max: Int,
        allowSeries: Boolean,
        allowNext: Boolean,
        hosts: EffectiveHosts = EffectiveHosts(emptyList()),
        log: (String) -> Unit = {},
    ): PluginPage {
        val value = runCatching { org.json.JSONTokener(json).nextValue() }.getOrNull()
        val array: JSONArray
        var next: String? = null
        when (value) {
            is JSONArray -> array = value
            is JSONObject -> {
                array = value.optJSONArray("items") ?: return PluginPage(emptyList(), null).also { log("page: no items") }
                next = cursor(value.opt("next"), allowNext, log)
            }
            else -> return PluginPage(emptyList(), null).also { log("the answer is not a list or a page") }
        }
        return PluginPage(itemsOf(array, max, allowSeries, hosts, log), next)
    }

    fun rows(
        json: String,
        allowSeries: Boolean,
        allowBrowse: Boolean,
        hosts: EffectiveHosts = EffectiveHosts(emptyList()),
        log: (String) -> Unit = {},
    ): List<PluginRow> {
        val array = runCatching { JSONArray(json) }.getOrNull()
            ?: return emptyList<PluginRow>().also { log("home: the answer is not a JSON array") }
        val seen = HashSet<String>()
        val out = ArrayList<PluginRow>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_ROWS) { log("home: rows beyond $MAX_ROWS dropped"); break }
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!ID.matches(id)) { log("home: row $i has an invalid id"); continue }
            val title = text(o, "title", MAX_TITLE_CHARS)
            if (title.isBlank()) { log("home: row $id has no title"); continue }
            val items = itemsOf(o.optJSONArray("items") ?: JSONArray(), MAX_ROW_ITEMS, allowSeries, hosts, log)
            if (items.isEmpty()) continue
            // Home keys its Lazy rows by this id: a repeat would crash the whole screen.
            if (!seen.add(id)) { log("home: duplicate row $id dropped"); continue }
            val ref = (o.opt("ref") as? String)?.takeIf { it.isNotEmpty() }
            val kept = when {
                ref == null -> null
                !allowBrowse -> null.also { log("home: row $id has a ref but the plugin doesn't declare browse") }
                ref.length > MAX_REF_CHARS -> null.also { log("home: row $id ref too long") }
                else -> ref
            }
            out += PluginRow(id, title, items, kept)
        }
        return out
    }

    fun episodes(json: String, log: (String) -> Unit = {}, hosts: EffectiveHosts = EffectiveHosts(emptyList())): PluginEpisodes {
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: throw PluginContractException("La lista de capítulos no es válida")
        val list = o.optJSONArray("episodes") ?: throw PluginContractException("El plugin no devolvió capítulos")
        val seen = HashSet<Pair<Int, Int>>()
        val eps = ArrayList<PluginEpisode>()
        for (i in 0 until list.length()) {
            if (eps.size >= MAX_EPISODES) { log("episodes: beyond $MAX_EPISODES dropped"); break }
            val e = list.optJSONObject(i) ?: continue
            val season = e.optInt("season", 1).takeIf { it in 1..999 } ?: 1
            val number = e.optInt("number", -1)
            if (number !in 1..99_999) { log("episodes: #$i has no valid number"); continue }
            val ref = e.optString("ref")
            if (ref.isEmpty() || ref.length > MAX_REF_CHARS) { log("episodes: #$i has no valid ref"); continue }
            if (!seen.add(season to number)) { log("episodes: duplicate S${season}E$number dropped"); continue }
            eps += PluginEpisode(
                season, number, ref, text(e, "title", MAX_TITLE_CHARS), image(e, "still", hosts), text(e, "overview", MAX_TEXT_CHARS),
                airDate = text(e, "airDate", 10).takeIf { AIR_DATE.matches(it) }.orEmpty(),
                runtimeMinutes = runtime(e),
            )
        }
        val series = o.optJSONObject("series")?.let {
            val ids = it.optJSONObject("ids")
            PluginSeriesInfo(
                title = text(it, "title", MAX_TITLE_CHARS), poster = image(it, "poster", hosts),
                backdrop = image(it, "backdrop", hosts), overview = text(it, "overview", MAX_TEXT_CHARS),
                // `ids` is the SDK v1 shape; the flat tmdbId/imdbId of the first contract still work.
                tmdbId = (ids?.let(::tmdb) ?: 0).takeIf { t -> t > 0 } ?: it.optInt("tmdbId", 0).coerceAtLeast(0),
                imdbId = ids?.let(::imdb)?.takeIf { s -> s.isNotEmpty() } ?: text(it, "imdbId", 20),
                genres = strings(it, "genres", MAX_GENRES, MAX_GENRE_CHARS),
                year = text(it, "year", 10),
            )
        }
        return PluginEpisodes(series, eps)
    }

    fun stream(json: String, hosts: EffectiveHosts): PluginStream {
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: throw PluginContractException("El plugin no devolvió un video")
        if (DRM_KEYS.any { o.has(it) }) throw PluginContractException("El video tiene DRM y los plugins no lo soportan")
        val url = o.optString("url")
        checkUrl(url, hosts, "El video")
        val mime = o.optString("mime").trim()
        if (mime.isNotEmpty() && !MIME.matches(mime)) throw PluginContractException("El tipo de video \"${mime.take(100)}\" no es válido")
        val headers = LinkedHashMap<String, String>()
        o.optJSONObject("headers")?.let { h ->
            for (k in h.keys()) {
                if (headers.size >= MAX_HEADERS) break
                val v = h.opt(k) as? String ?: continue
                if (!HEADER_NAME.matches(k) || k.lowercase() in FORBIDDEN_HEADERS) continue
                if (v.length > 4096 || '\n' in v || '\r' in v) continue
                headers[k] = v
            }
        }
        val subtitles = ArrayList<PluginSubtitle>()
        o.optJSONArray("subtitles")?.let { arr ->
            for (i in 0 until minOf(arr.length(), MAX_SUBTITLES)) {
                val s = arr.optJSONObject(i) ?: continue
                val su = s.optString("url")
                if (runCatching { checkUrl(su, hosts, "El subtítulo") }.isFailure) continue
                val format = s.optString("format").takeIf { it == "vtt" || it == "srt" }.orEmpty()
                subtitles += PluginSubtitle(text(s, "lang", 20).ifBlank { "und" }, su, format)
            }
        }
        val expires = o.optInt("expiresInSeconds", 0).takeIf { it in MIN_EXPIRES_IN_SECONDS..MAX_EXPIRES_IN_SECONDS } ?: 0
        return PluginStream(url, mime, headers, subtitles, o.optLong("durationMs", 0L).coerceAtLeast(0L), expires)
    }

    /** A stream or subtitle URL: https on a declared host, or exactly a server the person typed. */
    private fun checkUrl(url: String, hosts: EffectiveHosts, what: String) {
        val u = url.toHttpUrlOrNull() ?: throw PluginContractException("$what tiene una dirección inválida")
        if (hosts.userHostFor(u) != null) return
        if (u.scheme != "https") throw PluginContractException("$what debe usar https")
        if (!HostRules.matches(u.host, hosts.declared)) throw PluginContractException("$what apunta a ${u.host.take(100)}, que el plugin no declaró")
    }

    private fun cursor(raw: Any?, allowNext: Boolean, log: (String) -> Unit): String? {
        val next = (raw as? String)?.takeIf { it.isNotEmpty() } ?: return null
        if (!allowNext) { log("page: next dropped, the plugin doesn't declare browse"); return null }
        if (next.length > MAX_CURSOR_CHARS) { log("page: next longer than $MAX_CURSOR_CHARS dropped"); return null }
        return next
    }

    private fun itemsOf(array: JSONArray, max: Int, allowSeries: Boolean, hosts: EffectiveHosts, log: (String) -> Unit): List<PluginItem> {
        val seen = HashSet<String>()
        val out = ArrayList<PluginItem>()
        for (i in 0 until array.length()) {
            if (out.size >= max) { log("items beyond $max dropped"); break }
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!ID.matches(id)) { log("item #$i: invalid id"); continue }
            val ref = o.optString("ref")
            if (ref.isEmpty() || ref.length > MAX_REF_CHARS) { log("item $id: invalid ref"); continue }
            val title = text(o, "title", MAX_TITLE_CHARS)
            if (title.isBlank()) { log("item $id: no title"); continue }
            val kind = o.optString("kind")
            if (kind != "movie" && kind != "series") { log("item $id: invalid kind '${kind.take(20)}'"); continue }
            if (kind == "series" && !allowSeries) { log("item $id: series without the episodes capability"); continue }
            // Adult titles never reach a screen: no plugin section exists behind the 18+ lock yet.
            if (o.opt("adult") == true) { log("item $id: adult, dropped"); continue }
            if (!seen.add(id)) continue
            val ids = o.optJSONObject("ids")
            out += PluginItem(
                id = id, ref = ref, title = title, kind = kind, year = text(o, "year", 10),
                poster = image(o, "poster", hosts), backdrop = image(o, "backdrop", hosts),
                overview = text(o, "overview", MAX_TEXT_CHARS), lang = text(o, "lang", 20),
                quality = text(o, "quality", 20),
                originalTitle = text(o, "originalTitle", MAX_TITLE_CHARS),
                genres = strings(o, "genres", MAX_GENRES, MAX_GENRE_CHARS),
                rating = (o.opt("rating") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..10.0 },
                runtimeMinutes = runtime(o),
                tmdbId = ids?.let(::tmdb) ?: 0,
                imdbId = ids?.let(::imdb).orEmpty(),
                badges = strings(o, "badges", MAX_BADGES, MAX_BADGE_CHARS),
            )
        }
        return out
    }

    private fun tmdb(ids: JSONObject): Int = (ids.opt("tmdb") as? Number)?.toLong()?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: 0

    private fun imdb(ids: JSONObject): String = (ids.opt("imdb") as? String)?.takeIf { IMDB.matches(it) }.orEmpty()

    private fun runtime(o: JSONObject): Int = (o.opt("runtimeMinutes") as? Number)?.toInt()?.takeIf { it in 1..MAX_RUNTIME_MINUTES } ?: 0

    /** Up to [max] non-blank strings, each cut to [maxChars]; anything else in the list is skipped. */
    private fun strings(o: JSONObject, key: String, max: Int, maxChars: Int): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            if (out.size >= max) break
            val s = (a.opt(i) as? String)?.trim()?.take(maxChars)?.takeIf { it.isNotEmpty() } ?: continue
            if (s !in out) out += s
        }
        return out
    }

    private fun text(o: JSONObject, key: String, max: Int): String =
        when (val v = o.opt(key)) {
            is String -> v
            is Number -> v.toString()
            else -> ""
        }.trim().take(max)

    /**
     * https, ≤ [MAX_IMAGE_URL_CHARS] chars, and never an IP literal or a local name: a poster must
     * not be a LAN probe. The exception is a URL on a server the person typed (scheme, host and
     * port exactly): their own Jellyfin's posters are that server's.
     */
    private fun image(o: JSONObject, key: String, hosts: EffectiveHosts): String {
        val v = (o.opt(key) as? String).orEmpty().trim()
        if (v.length > MAX_IMAGE_URL_CHARS) return ""
        val url = v.toHttpUrlOrNull() ?: return ""
        if (hosts.userHostFor(url) != null) return v
        if (url.scheme != "https" || !v.startsWith("https://")) return ""
        return if (HostRules.isLocalAddress(url.host)) "" else v
    }
}
