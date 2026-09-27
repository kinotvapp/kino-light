package com.arkiv.player.data.plugin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The streams the native Magis bridge resolved for `kino.xuper.resolve`, keyed by their exact URL,
 * with the request headers the CDN needs to serve each one. One per process (`AppGraph`), shared by
 * the bridge (the only writer), [PluginContentSource] and the player's [PluginStreamGate] (the
 * readers, and only for the Xuper plugin's streams).
 *
 * Two jobs, both for the one plugin [XuperPrivilege.grants] and nobody else:
 *  - **The headers never reach the plugin's JS.** They are live session credentials. The bridge
 *    keeps them here instead of in the envelope it hands the script, and [PluginOutput.stream]
 *    attaches them to the stream the script returns, looked up by that stream's URL. The script
 *    only ever sees the URL, never a header value.
 *  - **The narrow https/host carve-out.** Magis's CDN and subtitle hosts are plain `http://` and
 *    vary per session, so no manifest can declare them. [PluginOutput.stream] waives the https and
 *    declared-host checks only for a URL found here: one the bridge itself resolved, byte for byte.
 *    Any other URL the script returns, including an `http://` one, is checked like any plugin's.
 *    The player's own per-request gate ([PluginHostGate.check]) waives the same two checks for the
 *    same URLs ([resolved]), or it would refuse at playback what [PluginOutput.stream] accepted.
 *
 * Bounded: the oldest entries go first. A re-resolve of the same URL replaces its headers with the
 * fresh ones.
 */
class XuperStreams(private val cap: Int = DEFAULT_CAP) {
    private val entries = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>): Boolean = size > cap
    }

    /** Records a resolved stream: [url] with the [headers] it plays with, and its subtitles' URLs (no headers). */
    @Synchronized
    fun remember(url: String, headers: Map<String, String>, subtitleUrls: List<String>) {
        // Only as many subtitles as PluginOutput.stream ever reads, and only if absent: a subtitle
        // must never overwrite a stream entry's headers.
        for (s in subtitleUrls.take(PluginOutput.MAX_SUBTITLES)) if (s.isNotEmpty() && s !in entries) entries[s] = emptyMap()
        // The stream last: the most recent entry, so its own subtitles can never evict it before
        // the plugin's answer is read.
        if (url.isNotEmpty()) entries[url] = headers.toMap()
    }

    /** The headers for exactly [url] (empty for a subtitle), or null when the bridge never resolved it. */
    @Synchronized
    fun headersFor(url: String): Map<String, String>? = entries[url]

    /**
     * Whether the bridge resolved exactly [url], as the player's HTTP stack will request it: the
     * playback-time gate ([PluginHostGate.check]) only has OkHttp's canonical form of the string
     * [PluginOutput.stream] let through (host lowercased, unsafe characters percent-encoded), so an
     * entry matches when it IS that string or parses to that same URL. Never a prefix or host match.
     * A read-only lookup: it does not refresh an entry's age.
     */
    @Synchronized
    fun resolved(url: HttpUrl): Boolean {
        val canonical = url.toString()
        return entries.keys.any { it == canonical || it.toHttpUrlOrNull() == url }
    }

    companion object {
        const val DEFAULT_CAP = 64
    }
}
