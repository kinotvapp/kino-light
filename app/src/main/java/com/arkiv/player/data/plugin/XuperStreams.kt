package com.arkiv.player.data.plugin

/**
 * The streams the native Magis bridge resolved for `kino.xuper.resolve`, keyed by their exact URL,
 * with the request headers the CDN needs to serve each one. One per process (`AppGraph`), shared by
 * the bridge (the only writer) and [PluginContentSource] (the only reader).
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
        if (url.isNotEmpty()) entries[url] = headers.toMap()
        // Only if absent: a subtitle must never overwrite a stream entry's headers.
        for (s in subtitleUrls) if (s.isNotEmpty() && s !in entries) entries[s] = emptyMap()
    }

    /** The headers for exactly [url] (empty for a subtitle), or null when the bridge never resolved it. */
    @Synchronized
    fun headersFor(url: String): Map<String, String>? = entries[url]

    companion object {
        const val DEFAULT_CAP = 64
    }
}
