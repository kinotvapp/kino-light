package com.arkiv.player.playback

import java.net.URI

/**
 * Which redirect of a CDN response the live proxy follows.
 *
 * Some channels answer the playlist with `301 Moved Permanently` (5 of the ~140 live "Source error" reports that
 * ended in a 502 to the player), and `HttpURLConnection` does not follow a redirect that changes the protocol, so the
 * channel was declared not served. Only a redirect to the SAME host is followed (typically http to https): the request
 * carries the channel's credentials and they must not be sent anywhere else.
 */
internal object OriginRedirect {

    val CODES = setOf(301, 302, 307, 308)

    /** The absolute URL to retry, or null when [location] is missing, malformed, not http(s) or points to another host. */
    fun sameHost(current: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        return runCatching {
            val from = URI(current)
            val to = from.resolve(location.trim())
            val scheme = to.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (!to.host.equals(from.host, ignoreCase = true)) return null
            to.toString()
        }.getOrNull()
    }
}
