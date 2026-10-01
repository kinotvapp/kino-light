package com.arkiv.player.playback

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Rewrites an HLS playlist so that EVERY resource it names is fetched back through
 * [PluginCastProxy], under the same token: a master playlist's variants (`#EXT-X-STREAM-INF` lines),
 * `#EXT-X-MEDIA`/`#EXT-X-I-FRAME-STREAM-INF`/`#EXT-X-RENDITION-REPORT` playlists, and a media
 * playlist's segments, `#EXT-X-KEY`/`#EXT-X-SESSION-KEY` keys, `#EXT-X-MAP` init sections,
 * `#EXT-X-PART`s and `#EXT-X-PRELOAD-HINT`s.
 *
 * A receiver can't send the plugin's headers (Referer, cookies...), and most HLS a plugin returns
 * needs them on every segment, often on other hosts and with relative URIs. Proxying only the first
 * playlist would break every request after it; this hands the receiver a playlist that only names
 * the proxy.
 *
 * Pure: [route] turns an ABSOLUTE upstream url (resolved against [baseUrl], the url the playlist was
 * actually served from, after redirects) into what the receiver gets. It is also where the proxy
 * records which urls a token may fetch: a token reaches the stream and what its playlists name, and
 * nothing else. Only http(s) URIs are rewritten; a `data:`/`skd:` URI (an inline or FairPlay key)
 * is left as it is, and an unresolvable one too (nobody can fetch it).
 */
object HlsPlaylistRewriter {
    /** What a URI names: another playlist (rewritten in turn when fetched) or bytes (relayed as they are). */
    enum class Kind { PLAYLIST, MEDIA }

    private val URI_ATTRIBUTE = Regex("""URI="([^"]*)"""")

    /** Tags whose `URI` is another playlist; every other tag's `URI` is media (key, map, part...). */
    private val PLAYLIST_TAGS = listOf("#EXT-X-MEDIA:", "#EXT-X-I-FRAME-STREAM-INF:", "#EXT-X-RENDITION-REPORT:")

    /** Whether [body] looks like an HLS playlist at all. */
    fun isPlaylist(body: String): Boolean = body.trimStart('﻿', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")

    /**
     * [body] with every URI routed through [route], or null when [baseUrl] is not an http(s) url
     * (nothing could be resolved against it).
     */
    fun rewrite(body: String, baseUrl: String, route: (absoluteUrl: String, kind: Kind) -> String): String? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val lines = body.split('\n')
        val master = lines.any { it.trimStart().startsWith("#EXT-X-STREAM-INF") }
        fun routed(uri: String, kind: Kind): String? {
            val absolute = base.resolve(uri.trim()) ?: return null
            return route(absolute.toString(), kind)
        }
        return lines.joinToString("\n") { raw ->
            val cr = raw.endsWith('\r')
            val line = raw.removeSuffix("\r")
            val trimmed = line.trim()
            val out = when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> {
                    if (!trimmed.contains("URI=\"")) {
                        line
                    } else {
                        val kind = if (PLAYLIST_TAGS.any { trimmed.startsWith(it) }) Kind.PLAYLIST else Kind.MEDIA
                        URI_ATTRIBUTE.replace(line) { m ->
                            val target = routed(m.groupValues[1], kind) ?: return@replace m.value
                            "URI=\"$target\""
                        }
                    }
                }
                else -> routed(trimmed, if (master) Kind.PLAYLIST else Kind.MEDIA) ?: line
            }
            if (cr) "$out\r" else out
        }
    }
}
