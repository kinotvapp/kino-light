package com.arkiv.player.data.plugin

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * The HTTP client a plugin stream plays through (the player's `OkHttpDataSource`).
 *
 * Checking only the URL `resolve()` returned isn't enough: an HLS/DASH manifest names its own
 * variants, segments, `#EXT-X-KEY` URIs and `BaseURL`s, and any request can be redirected. All of
 * them go through this one client, so all of them meet [PluginHostGate] (https + a host the person
 * approved, never an IP literal or a local name) BEFORE the request leaves the device, with the
 * plugin's headers on them. [PluginDns] additionally refuses a declared name that resolves into
 * the home network.
 *
 * OkHttp's own redirect following is off: it would connect to the target before any interceptor
 * saw it. [PluginStreamGate], an APPLICATION interceptor, follows redirects by hand instead —
 * application interceptors may call `proceed` more than once — gating every hop first.
 */
object PluginStreamHttp {
    /**
     * [hosts] must come from the INSTALLED record and the person's own settings (what the person
     * approved or typed), never from plugin output. `allowInsecureLocalhost` and `delegateDns`
     * exist for MockWebServer tests only.
     */
    fun client(
        base: OkHttpClient,
        hosts: EffectiveHosts,
        allowInsecureLocalhost: Boolean = false,
        delegateDns: Dns = Dns.SYSTEM,
    ): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(PluginDns(allowLoopback = allowInsecureLocalhost, delegate = delegateDns, userHostNames = hosts.userHostNames))
        .addInterceptor(PluginStreamGate(hosts, allowInsecureLocalhost))
        .build()
}

/** Gates every request and every redirect hop of a plugin stream; see [PluginStreamHttp]. */
class PluginStreamGate(
    private val hosts: EffectiveHosts,
    private val allowInsecureLocalhost: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var previous: okhttp3.HttpUrl? = null
        repeat(MAX_REDIRECTS + 1) {
            val from = previous
            if (from == null) PluginHostGate.check(request.url, hosts, allowInsecureLocalhost)
            else PluginHostGate.checkRedirect(from, request.url, hosts, allowInsecureLocalhost)
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

    private companion object {
        const val MAX_REDIRECTS = 10
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
