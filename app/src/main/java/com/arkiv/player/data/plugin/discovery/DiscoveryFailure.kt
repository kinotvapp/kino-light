package com.arkiv.player.data.plugin.discovery

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import kotlin.math.abs

/**
 * Why the GitHub search failed, as ONE stable word for telemetry (never a URL, a message or anything
 * about the person): `rate_limited` (403/429), `dns`, `tls_or_clock`, `timeout`, `offline`, `http_<code>`,
 * `parse`, `io` (another I/O failure) or `error`.
 */
object DiscoveryFailure {
    const val RATE_LIMITED = "rate_limited"
    const val PARSE = "parse"
    const val TLS = "tls_or_clock"

    /** A clock further than this from the server's is called off: TLS only fails on far bigger gaps, but a TV box that booted in 1970 or 2000 is way past it. */
    private const val CLOCK_TOLERANCE_MS = 60 * 60 * 1000L

    fun ofStatus(code: Int): String = if (code == 403 || code == 429) RATE_LIMITED else "http_$code"

    /** Looks through the whole cause chain: OkHttp and Conscrypt wrap the real reason. */
    fun of(e: Throwable): String {
        val chain = causes(e)
        return when {
            chain.any { it is UnknownHostException } -> "dns"
            chain.any { it is SSLException || it is CertificateException } -> TLS
            chain.any { it is SocketTimeoutException } -> "timeout"
            chain.any { it is ConnectException || it is NoRouteToHostException || it is SocketException } -> "offline"
            chain.any { it is InterruptedIOException } -> "timeout"
            e is IOException -> "io"
            else -> "error"
        }
    }

    /** True when the certificate was refused for its dates, which on a device almost always means a wrong clock. */
    fun certTimeRejected(e: Throwable): Boolean = causes(e).any {
        it is CertificateExpiredException || it is CertificateNotYetValidException ||
            it.message.orEmpty().let { m -> m.contains("timestamp check failed", true) || m.contains("expired", true) || m.contains("not yet valid", true) }
    }

    /** `off`, `ok` or `unknown` (no server `Date` to compare with). */
    fun clock(deviceNowMs: Long, serverDateMs: Long?): String = when {
        serverDateMs == null -> "unknown"
        abs(deviceNowMs - serverDateMs) > CLOCK_TOLERANCE_MS -> "off"
        else -> "ok"
    }

    private fun causes(e: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var t: Throwable? = e
        while (t != null && out.size < 10 && out.none { it === t }) {
            out += t
            t = t.cause
        }
        return out
    }
}
