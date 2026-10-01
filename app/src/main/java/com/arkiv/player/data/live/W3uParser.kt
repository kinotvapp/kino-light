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
 * Pure and bounded, read with [LenientJson] (the files are often hand-edited). Only http(s)
 * stations are kept; `embed` (a web page) and `isHost`/`host`/`hostParser` (needs a site scraper)
 * stations are skipped like broken M3U entries. Headers go through the same allowlist and control
 * character rule as M3U ([M3uParser.keepHeader]). Following [W3uList.links] is the caller's job
 * ([W3uExpander]), with its own caps.
 */
object W3uParser {
    /** Groups inside groups walked at most this deep. */
    const val MAX_GROUP_DEPTH = 8
    private const val MAX_NAME = 200
    private const val MAX_GROUP_NAME = 80

    /** Null = not JSON, broken JSON, or JSON that is not a W3U list (no `groups` or `stations`). */
    fun parse(text: String, maxEntries: Int = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER): W3uList? {
        val root = try {
            LenientJson.parse(text) as? Map<*, *>
        } catch (e: LenientJson.Invalid) {
            null
        } ?: return null
        if (root["groups"] !is List<*> && root["stations"] !is List<*>) return null
        val walk = Walk(maxEntries)
        walk.group(root, "", 0)
        return W3uList(
            name = clean(root["name"] as? String, MAX_NAME),
            entries = walk.entries, links = walk.links,
            epgUrls = epgsOf(root["epg"]), skipped = walk.skipped,
        )
    }

    private class Walk(val maxEntries: Int) {
        val entries = ArrayList<M3uEntry>()
        val links = ArrayList<W3uLink>()
        var skipped = 0

        fun group(g: Map<*, *>, path: String, depth: Int) {
            (g["url"] as? String)?.trim()?.takeIf { path.isNotEmpty() && isHttp(it) && !it.any(Char::isWhitespace) }?.let { links += W3uLink(path, it) }
            (g["stations"] as? List<*>)?.forEach { s -> (s as? Map<*, *>)?.let { station(it, path) } ?: skipped++ }
            if (depth >= MAX_GROUP_DEPTH) return
            (g["groups"] as? List<*>)?.forEach { child ->
                val c = child as? Map<*, *> ?: return@forEach
                val name = clean(c["name"] as? String, MAX_GROUP_NAME).ifEmpty { "Sin nombre" }
                group(c, if (path.isEmpty()) name else "$path · $name".take(MAX_NAME), depth + 1)
            }
        }

        fun station(s: Map<*, *>, path: String) {
            val url = (s["url"] as? String)?.trim().orEmpty()
            val name = clean(s["name"] as? String, MAX_NAME)
            val webOnly = s["embed"] == true || s["isHost"] == true || s["host"] != null || s["hostParser"] != null
            if (name.isEmpty() || !isHttp(url) || url.any(Char::isWhitespace) || webOnly || entries.size >= maxEntries) {
                skipped++
                return
            }
            val headers = LinkedHashMap<String, String>()
            (s["headers"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) M3uParser.keepHeader(headers, k, v.trim()) }
            listOf("userAgent", "user-agent", "referer", "referrer", "origin").forEach { k ->
                (s[k] as? String)?.trim()?.let { M3uParser.keepHeader(headers, if (k == "userAgent") "user-agent" else k, it) }
            }
            entries += M3uEntry(
                name = name, url = url,
                tvgId = clean(s["epgId"] as? String, MAX_NAME),
                logo = (s["image"] as? String)?.trim()?.takeIf { isHttp(it) }.orEmpty(),
                group = path, headers = headers,
            )
        }
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
