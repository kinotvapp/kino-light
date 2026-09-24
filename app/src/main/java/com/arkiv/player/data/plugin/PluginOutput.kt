package com.arkiv.player.data.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class PluginItem(
    val id: String, val ref: String, val title: String, val kind: String,
    val year: String = "", val poster: String = "", val backdrop: String = "",
    val overview: String = "", val lang: String = "", val quality: String = "",
)

data class PluginRow(val id: String, val title: String, val items: List<PluginItem>)

data class PluginSeriesInfo(
    val title: String = "", val poster: String = "", val backdrop: String = "",
    val overview: String = "", val tmdbId: Int = 0, val imdbId: String = "",
)

data class PluginEpisode(
    val season: Int, val number: Int, val ref: String,
    val title: String = "", val still: String = "", val overview: String = "",
)

data class PluginEpisodes(val series: PluginSeriesInfo?, val episodes: List<PluginEpisode>)

data class PluginSubtitle(val lang: String, val url: String, val format: String = "")

data class PluginStream(
    val url: String, val mime: String = "", val headers: Map<String, String> = emptyMap(),
    val subtitles: List<PluginSubtitle> = emptyList(), val durationMs: Long = 0,
)

/** A plugin answered something the contract doesn't allow; [message] is Spanish, shown to the person. */
class PluginContractException(message: String) : Exception(message)

/**
 * Strict reader of what a plugin returns (apiVersion 1). Lists are forgiving — a bad entry is
 * dropped with a [log] line, the rest survive — while a stream is all-or-nothing: an `http` URL, a
 * host the plugin didn't declare, or any DRM field refuses the whole thing. Images must be https
 * and are the one thing NOT host-gated (display-only, loaded by Coil without plugin headers), but
 * an image on an IP literal or a local name is dropped, so a poster can't probe the home network.
 */
object PluginOutput {
    const val MAX_ITEMS = 50
    const val MAX_ROWS = 10
    const val MAX_ROW_ITEMS = 40
    const val MAX_EPISODES = 2000
    const val MAX_REF_CHARS = 4096
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_TEXT_CHARS = 2000
    private const val MAX_HEADERS = 20
    private const val MAX_SUBTITLES = 30

    private val ID = Regex("^[A-Za-z0-9._~-]{1,128}$")
    private val MIME = Regex("^[a-z]+/[A-Za-z0-9.+-]{1,100}$")
    private val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
    private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection")
    private val DRM_KEYS = setOf("drm", "license", "licenseUrl", "drmLicenseUrl", "keySystem", "widevine")

    fun items(json: String, allowSeries: Boolean, log: (String) -> Unit = {}): List<PluginItem> {
        val array = runCatching { JSONArray(json) }.getOrNull()
            ?: return emptyList<PluginItem>().also { log("search: the answer is not a JSON array") }
        return itemsOf(array, MAX_ITEMS, allowSeries, log)
    }

    fun rows(json: String, allowSeries: Boolean, log: (String) -> Unit = {}): List<PluginRow> {
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
            val items = itemsOf(o.optJSONArray("items") ?: JSONArray(), MAX_ROW_ITEMS, allowSeries, log)
            if (items.isEmpty()) continue
            // Home keys its Lazy rows by this id: a repeat would crash the whole screen.
            if (!seen.add(id)) { log("home: duplicate row $id dropped"); continue }
            out += PluginRow(id, title, items)
        }
        return out
    }

    fun episodes(json: String, log: (String) -> Unit = {}): PluginEpisodes {
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
            eps += PluginEpisode(season, number, ref, text(e, "title", MAX_TITLE_CHARS), image(e, "still"), text(e, "overview", MAX_TEXT_CHARS))
        }
        val series = o.optJSONObject("series")?.let {
            PluginSeriesInfo(
                title = text(it, "title", MAX_TITLE_CHARS), poster = image(it, "poster"),
                backdrop = image(it, "backdrop"), overview = text(it, "overview", MAX_TEXT_CHARS),
                tmdbId = it.optInt("tmdbId", 0).coerceAtLeast(0), imdbId = text(it, "imdbId", 20),
            )
        }
        return PluginEpisodes(series, eps)
    }

    fun stream(json: String, hosts: List<String>): PluginStream {
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: throw PluginContractException("El plugin no devolvió un video")
        if (DRM_KEYS.any { o.has(it) }) throw PluginContractException("El video tiene DRM y los plugins no lo soportan")
        val url = o.optString("url")
        checkUrl(url, hosts, "El video")
        val mime = o.optString("mime").trim()
        if (mime.isNotEmpty() && !MIME.matches(mime)) throw PluginContractException("El tipo de video \"$mime\" no es válido")
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
        return PluginStream(url, mime, headers, subtitles, o.optLong("durationMs", 0L).coerceAtLeast(0L))
    }

    private fun checkUrl(url: String, hosts: List<String>, what: String) {
        val u = url.toHttpUrlOrNull() ?: throw PluginContractException("$what tiene una dirección inválida")
        if (u.scheme != "https") throw PluginContractException("$what debe usar https")
        if (!HostRules.matches(u.host, hosts)) throw PluginContractException("$what apunta a ${u.host}, que el plugin no declaró")
    }

    private fun itemsOf(array: JSONArray, max: Int, allowSeries: Boolean, log: (String) -> Unit): List<PluginItem> {
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
            if (kind != "movie" && kind != "series") { log("item $id: invalid kind '$kind'"); continue }
            if (kind == "series" && !allowSeries) { log("item $id: series without the episodes capability"); continue }
            if (!seen.add(id)) continue
            out += PluginItem(
                id = id, ref = ref, title = title, kind = kind, year = text(o, "year", 10),
                poster = image(o, "poster"), backdrop = image(o, "backdrop"),
                overview = text(o, "overview", MAX_TEXT_CHARS), lang = text(o, "lang", 20),
                quality = text(o, "quality", 20),
            )
        }
        return out
    }

    private fun text(o: JSONObject, key: String, max: Int): String =
        when (val v = o.opt(key)) {
            is String -> v
            is Number -> v.toString()
            else -> ""
        }.trim().take(max)

    /** https, ≤ 2048 chars, and never an IP literal or a local name: a poster must not be a LAN probe. */
    private fun image(o: JSONObject, key: String): String {
        val v = (o.opt(key) as? String).orEmpty().trim()
        if (!v.startsWith("https://") || v.length > 2048) return ""
        val host = v.toHttpUrlOrNull()?.host ?: return ""
        return if (HostRules.isLocalAddress(host)) "" else v
    }
}
