package com.arkiv.player.data.plugin

import com.arkiv.player.data.net.DohDns
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * The HTTP client a plugin stream plays through (the player's `OkHttpDataSource`).
 *
 * Checking only the URL `resolve()` returned isn't enough: an HLS/DASH manifest names its own
 * variants, segments, `#EXT-X-KEY` URIs and `BaseURL`s, and any request can be redirected. All of
 * them go through this one client -- and so does a Widevine license request (`PluginWidevine`) --
 * so all of them meet [PluginHostGate] (a host the person approved, https unless they approved it
 * as `insecureHttp`, never an IP literal or a local name) BEFORE the request leaves the device,
 * with the plugin's headers on them. [PluginDns] additionally refuses a declared name that
 * resolves into the home network.
 *
 * OkHttp's own redirect following is off: it would connect to the target before any interceptor
 * saw it. [PluginStreamGate], an APPLICATION interceptor, follows redirects by hand instead —
 * application interceptors may call `proceed` more than once — gating every hop first.
 */
object PluginStreamHttp {
    /**
     * [hosts] must come from the INSTALLED record and the person's own settings (what the person
     * approved or typed), never from plugin output. `allowInsecureLocalhost` and `delegateDns`
     * exist for MockWebServer tests only. [xuper] is non-null only for a stream of the one plugin
     * [XuperPrivilege.grants] (see [PluginHostGate.check]); null leaves the gate exactly as it was.
     * [strictOrigins]: the side-loaded subtitle and audio URLs of a stream whose [hosts] are relaxed
     * for a live channel (`anyPublicLiveHost`): a request that starts at one of them is gated on EVERY
     * hop with [EffectiveHosts.strict], since live "any" never covers a subtitle or an audio track.
     * (The broad video permission does cover them: its caller passes none.)
     */
    fun client(
        base: OkHttpClient,
        hosts: EffectiveHosts,
        allowInsecureLocalhost: Boolean = false,
        delegateDns: Dns = DohDns,
        xuper: XuperStreams? = null,
        strictOrigins: Collection<String> = emptySet(),
        /**
         * The plugin whose undeclared-but-askable hosts this client reports as
         * [UndeclaredPlaybackHostException], so the player can ask the person
         * ([PluginHostGate.playbackRefusal]). The player's stream client sets it, and so does a
         * plugin's download client, which asks nobody but words its refusal from it
         * (`PluginHostRefusal`); null (a license client, every other caller) refuses exactly as before.
         */
        askAboutFor: String? = null,
        /**
         * Told each host (only the host: never a path or query) this client reaches only because of
         * the broad video permission ([EffectiveHosts.anyPublicVideoHost]) -- once per host, for the
         * log. Nothing else changes with it.
         */
        onAnyVideoHost: ((String) -> Unit)? = null,
    ): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(PluginDns(allowLoopback = allowInsecureLocalhost, delegate = delegateDns, userHostNames = hosts.userHostNames))
        .addInterceptor(PluginStreamGate(hosts, allowInsecureLocalhost, xuper, strictOrigins.mapNotNull { it.toHttpUrlOrNull()?.toString() }.toSet(), askAboutFor, onAnyVideoHost))
        .build()
}

/** Gates every request and every redirect hop of a plugin stream; see [PluginStreamHttp]. */
class PluginStreamGate(
    private val hosts: EffectiveHosts,
    private val allowInsecureLocalhost: Boolean = false,
    private val xuper: XuperStreams? = null,
    /** Canonical URLs (`HttpUrl.toString()`) whose whole redirect chain is gated with [EffectiveHosts.strict]. */
    private val strictOrigins: Set<String> = emptySet(),
    /** See [PluginStreamHttp.client]. Never used under live "any" or broad video: those paths ask nothing. */
    private val askAboutFor: String? = null,
    /** See [PluginStreamHttp.client]. */
    private val onAnyVideoHost: ((String) -> Unit)? = null,
) : Interceptor {
    /** Hosts already reported to [onAnyVideoHost]: once each for this client's life. */
    private val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val gate = if (hosts.anyPublicStreamHost && request.url.toString() in strictOrigins) hosts.strict else hosts
        val askable = askAboutFor.takeUnless { hosts.anyPublicStreamHost }
        var previous: okhttp3.HttpUrl? = null
        repeat(MAX_REDIRECTS + 1) {
            val from = previous
            try {
                if (from == null) PluginHostGate.check(request.url, gate, allowInsecureLocalhost, xuper)
                else PluginHostGate.checkRedirect(from, request.url, gate, allowInsecureLocalhost, xuper)
            } catch (e: HostNotAllowedException) {
                throw PluginHostGate.playbackRefusal(e, request.url, gate, askable)
            }
            reportAnyVideoHost(request.url, gate)
            val response = chain.proceed(request)
            val location = response.header("Location")
            if (response.code !in REDIRECTS || location == null) return response
            val next = request.url.resolve(location)
            val code = response.code
            response.close()
            if (next == null) throw IOException("redirección inválida")
            previous = request.url
            request = request.newBuilder().url(next).apply {
                if (code == 303 || (code in 301..302 && request.method == "POST")) get()
            }.build()
        }
        throw IOException("demasiadas redirecciones")
    }

    /** [url] passed [gate] only through the broad video permission: tell [onAnyVideoHost], once per host. */
    private fun reportAnyVideoHost(url: okhttp3.HttpUrl, gate: EffectiveHosts) {
        val report = onAnyVideoHost ?: return
        if (!gate.anyPublicVideoHost || gate.userHostFor(url) != null) return
        if (HostRules.matches(url.host, gate.declared) && gate.allowsScheme(url)) return
        if (reported.add(url.host)) report(url.host)
    }

    private companion object {
        const val MAX_REDIRECTS = 10
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
