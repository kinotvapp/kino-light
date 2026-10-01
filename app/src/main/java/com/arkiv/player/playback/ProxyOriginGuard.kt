package com.arkiv.player.playback

import com.arkiv.player.data.plugin.PluginDns
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Which upstream addresses [ArchiveCacheProxy] may fetch from: public internet hosts only.
 *
 * The proxy listens on the LAN (a Chromecast or a DLNA TV pulls through it), so whatever it fetches
 * is something a LAN client can cause the phone to fetch. Without this, an origin -- or any redirect
 * hop it answers with -- that pointed into the home network (the router's admin page, a NAS) would
 * be reached from inside it. Every hop is checked, the first one and each `Location`, against the
 * same ranges [PluginDns] refuses for plugins ([PluginDns.isLocalAddress]): loopback, RFC 1918,
 * link-local, CGNAT, ULA, multicast, the reserved ranges and the IPv6 forms that embed an IPv4.
 *
 * IP literals are NOT refused for being literals, only for being private: Magis's CDN may well be
 * addressed by a public IP.
 *
 * The check resolves the name itself and `HttpURLConnection` resolves it again on connect, so a
 * DNS answer that changes in between (rebinding) is not fully closed by this; both go through the
 * same system resolver and its cache, which keeps that window to the cache's lifetime.
 *
 * [allowLoopback] exists for tests, whose origin is a MockWebServer on 127.0.0.1.
 */
class ProxyOriginGuard(
    private val allowLoopback: Boolean = false,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    /** Null when [url] may be fetched, otherwise a short reason for the log (never the URL itself). */
    fun refusal(url: URL): String? {
        val scheme = url.protocol.orEmpty().lowercase()
        if (scheme != "http" && scheme != "https") return "scheme '$scheme' is not http(s)"
        val host = url.host.orEmpty().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return "no host"
        val addresses = runCatching { resolve(host) }.getOrNull()
        if (addresses.isNullOrEmpty()) return "host does not resolve"
        val local = addresses.firstOrNull { PluginDns.isLocalAddress(it) && !(allowLoopback && it.isLoopbackAddress) }
        return if (local != null) "resolves to a non-public address" else null
    }

    /** The outcome of [open]: either the final (non-redirect) response, or why it was refused. */
    sealed class Hop {
        class Response(val connection: HttpURLConnection, val code: Int) : Hop()
        class Refused(val reason: String) : Hop()
    }

    /**
     * Opens [origin] following redirects BY HAND, [refusal]-checking every hop before connecting
     * to it. `HttpURLConnection` with `instanceFollowRedirects = true` would connect to a redirect
     * target on its own, before anything here could look at it.
     *
     * [configure] runs on every hop's connection (headers, timeouts) -- the stream's headers are
     * re-sent to each hop, exactly as `instanceFollowRedirects` did. [onConnection] sees each
     * connection before its response is awaited, so a caller can register it for cancellation.
     * [responseCode] is how the code is read (with a caller-side deadline, for instance).
     *
     * Throws whatever opening or reading the code throws, like the plain connection did.
     */
    fun open(
        origin: String,
        configure: HttpURLConnection.() -> Unit,
        onConnection: (HttpURLConnection) -> Unit = {},
        responseCode: (HttpURLConnection) -> Int = { it.responseCode },
    ): Hop {
        var url = URL(origin)
        repeat(MAX_HOPS + 1) { hop ->
            refusal(url)?.let { return Hop.Refused(if (hop == 0) it else "redirect hop $hop: $it") }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                configure()
            }
            onConnection(conn)
            val code = responseCode(conn)
            if (code !in REDIRECTS) return Hop.Response(conn, code)
            val location = conn.getHeaderField("Location")
            runCatching { conn.disconnect() }
            url = location?.let { runCatching { URL(url, it) }.getOrNull() }
                ?: return Hop.Refused("redirect $code without a usable Location")
        }
        return Hop.Refused("more than $MAX_HOPS redirects")
    }

    companion object {
        /** Same budget `HttpURLConnection`'s own redirect following had (it gives up past 20; this is stricter). */
        const val MAX_HOPS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
