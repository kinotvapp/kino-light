package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginLiveContract

/** A W3U group whose `url` points to another list (W3U or M3U); [group] is its group path. */
data class W3uLink(val group: String, val url: String)

/**
 * One W3U document: [entries] in the same shape an M3U list gives (so the rest of the En vivo
 * pipeline is shared), the [links] its groups point to, its guides, and how many stations were
 * [skipped] (web pages, host-resolved links, non-http schemes, nameless, past the cap).
 */
data class W3uList(
    val name: String,
    val entries: List<M3uEntry>,
    val links: List<W3uLink>,
    val epgUrls: List<String>,
    val skipped: Int,
)

/**
 * Wiseplay "W3U" lists (docs.dimplay.app/playlists/w3u), the JSON playlists that circulate in
 * Spanish-speaking countries: a root with `name`, `epg`, `groups[]` and `stations[]`; a group has
 * `name`, `image`, nested `groups[]` / `stations[]`, or a `url` pointing to another list; a station
 * has `name`, `url`, `image`, `epgId`, `headers`, `referer`, `userAgent`.
 *
 * The files in the wild are looser than the spec, so the common spellings are read too: stations may
 * sit under `stations`, `samples` or `channels` (root or any group); a group links other lists with
 * `url` or with `sublists` / `lists` (each an address or an object with `url`); a station's address
 * may be `url`, `link` or `stream` and carry Kodi's `|User-Agent=...&Referer=...` suffix; its logo is
 * `image`, `logo` or `icon`; its guide id `epgId`, `epg_id`, `tvgId` or `tvg-id`; its agent `userAgent`,
 * `user_agent` or `user-agent`; a ClearKey pair is read from `key` / `clearKey` / `license_key` (as
 * `kid:key` hex, or an object with `kid` and `key`) unless `drm` / `license_type` names another system.
 * Only data is read: no script, no executable content, no field that makes the app run anything.
 *
 * Pure and bounded, read with [LenientJson] (the files are often hand-edited). Only http(s)
 * stations are kept; `embed` (a web page) and `isHost`/`host`/`hostParser` (needs a site scraper)
 * stations are skipped like broken M3U entries. Headers go through the same allowlist and control
 * character rule as M3U ([M3uParser.keepHeader]). Following [W3uList.links] is the caller's job
 * ([W3uExpander]), with its own caps.
 */
object W3uParser {
    /** Groups inside groups walked at most this deep. */
    const val MAX_GROUP_DEPTH = 8
    private val STATION_KEYS = listOf("stations", "samples", "channels")
    private val LINK_KEYS = listOf("sublists", "lists")
    private const val MAX_NAME = 200
    private const val MAX_GROUP_NAME = 80

    /** Null = not JSON, broken JSON, or JSON that is not a W3U list (no `groups` or `stations`). */
    fun parse(text: String, maxEntries: Int = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER): W3uList? {
        val root = try {
            LenientJson.parse(text) as? Map<*, *>
        } catch (e: LenientJson.Invalid) {
            null
        } ?: return null
        if (STATION_KEYS.none { root[it] is List<*> } && root["groups"] !is List<*> && LINK_KEYS.none { root[it] is List<*> }) return null
        val walk = Walk(maxEntries)
        walk.group(root, "", 0)
        return W3uList(
            name = clean(root["name"] as? String, MAX_NAME),
            entries = walk.entries, links = walk.links,
            epgUrls = epgsOf(listOf("epg", "epgUrl", "epg_url", "xmltv").firstNotNullOfOrNull { root[it] }), skipped = walk.skipped,
        )
    }

    private class Walk(val maxEntries: Int) {
        val entries = ArrayList<M3uEntry>()
        val links = ArrayList<W3uLink>()
        var skipped = 0

        fun group(g: Map<*, *>, path: String, depth: Int) {
            (g["url"] as? String)?.trim()?.takeIf { path.isNotEmpty() && isHttp(it) && !it.any(Char::isWhitespace) }?.let { links += W3uLink(path, it) }
            STATION_KEYS.forEach { key -> (g[key] as? List<*>)?.forEach { s -> (s as? Map<*, *>)?.let { station(it, path) } ?: skipped++ } }
            LINK_KEYS.forEach { key ->
                (g[key] as? List<*>)?.forEach { l ->
                    val m = l as? Map<*, *>
                    val addr = ((m?.get("url") ?: m?.get("link")) as? String ?: l as? String)?.trim().orEmpty()
                    if (isHttp(addr) && !addr.any(Char::isWhitespace)) {
                        val name = clean(m?.get("name") as? String, MAX_GROUP_NAME)
                        links += W3uLink(listOf(path, name).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Lista" }, addr)
                    } else skipped++
                }
            }
            if (depth >= MAX_GROUP_DEPTH) return
            (g["groups"] as? List<*>)?.forEach { child ->
                val c = child as? Map<*, *> ?: return@forEach
                val name = clean(c["name"] as? String, MAX_GROUP_NAME).ifEmpty { "Sin nombre" }
                group(c, if (path.isEmpty()) name else "$path · $name".take(MAX_NAME), depth + 1)
            }
        }

        fun station(s: Map<*, *>, path: String) {
            val raw = first(s, "url", "link", "stream")?.trim().orEmpty()
            val url = raw.substringBefore('|').trim()
            val name = clean(first(s, "name", "title"), MAX_NAME)
            val webOnly = s["embed"] == true || s["isHost"] == true || s["host"] != null || s["hostParser"] != null
            if (name.isEmpty() || !isHttp(url) || url.any(Char::isWhitespace) || webOnly || entries.size >= maxEntries) {
                skipped++
                return
            }
            val headers = LinkedHashMap<String, String>()
            (s["headers"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) M3uParser.keepHeader(headers, k, v.trim()) }
            // Kodi style `url|User-Agent=...&Referer=...`: the same rules as an M3U line's suffix.
            if (raw.contains('|')) M3uParser.pairs(raw.substringAfter('|'), headers)
            listOf("userAgent", "user_agent", "user-agent", "useragent", "referer", "referrer", "origin").forEach { k ->
                (s[k] as? String)?.trim()?.let { M3uParser.keepHeader(headers, if (k.startsWith("user")) "user-agent" else k, it) }
            }
            val drm = drmOf(s)
            entries += M3uEntry(
                name = name, url = url,
                tvgId = clean(first(s, "epgId", "epg_id", "tvgId", "tvg-id", "tvg_id"), MAX_NAME),
                logo = first(s, "image", "logo", "icon")?.trim()?.takeIf { isHttp(it) }.orEmpty(),
                group = path.ifEmpty { clean(first(s, "group", "category"), MAX_GROUP_NAME) }, headers = headers,
                drmKeyId = drm?.first.orEmpty(), drmKey = drm?.second.orEmpty(),
            )
        }
    }

    private fun first(s: Map<*, *>, vararg keys: String): String? = keys.firstNotNullOfOrNull { s[it] as? String }

    /** A ClearKey pair (kid, key as 32 hex each) from `key` / `clearKey` / `license_key`, unless the station names another DRM system. */
    private fun drmOf(s: Map<*, *>): Pair<String, String>? {
        val system = first(s, "drm", "license_type", "licenseType", "drm_type")?.trim()?.lowercase().orEmpty()
        if (system.isNotEmpty() && system != "clearkey" && system != "org.w3.clearkey") return null
        val v = listOf("key", "clearKey", "clearkey", "license_key", "licenseKey", "drmKey", "keys").firstNotNullOfOrNull { s[it] } ?: return null
        val text = when (v) {
            is String -> v
            is Map<*, *> -> if (v["keys"] is List<*>) org.json.JSONObject(v as Map<*, *>).toString() else {
                val kid = (v["kid"] ?: v["keyId"] ?: v["key_id"]) as? String
                val k = (v["key"] ?: v["k"]) as? String
                if (kid == null || k == null) return null
                if (kid.length == 32 && k.length == 32) "$kid:$k"
                else org.json.JSONObject().put("keys", org.json.JSONArray().put(org.json.JSONObject().put("kid", kid).put("k", k))).toString()
            }
            else -> return null
        }
        return M3uParser.clearKeyPair(text)
    }

    private fun isHttp(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** Control characters become spaces, runs of spaces one, trimmed and cut to [max]. */
    private fun clean(s: String?, max: Int): String =
        s.orEmpty().map { if (Character.isISOControl(it)) ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim().take(max).trim()

    private fun epgsOf(v: Any?): List<String> {
        val raw = when (v) {
            is String -> v.split(',')
            is List<*> -> v.filterIsInstance<String>()
            else -> emptyList()
        }
        return raw.map { it.trim() }.filter { isHttp(it) && !it.any(Char::isWhitespace) }.distinct().take(M3uParser.MAX_LIST_EPGS)
    }
}
