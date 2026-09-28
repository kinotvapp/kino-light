package com.arkiv.player.data.plugin.discovery

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLHandshakeException

class DiscoveryFailureTest {
    @Test fun `exceptions map to a stable class, looking through causes`() {
        assertEquals("dns", DiscoveryFailure.of(UnknownHostException("api.github.com")))
        assertEquals("dns", DiscoveryFailure.of(IOException("wrapped", UnknownHostException("x"))))
        assertEquals("tls_or_clock", DiscoveryFailure.of(SSLHandshakeException("handshake")))
        assertEquals("tls_or_clock", DiscoveryFailure.of(IOException(CertificateException("bad"))))
        assertEquals("tls_or_clock", DiscoveryFailure.of(SSLHandshakeException("x").apply { initCause(CertificateExpiredException()) }))
        assertEquals("timeout", DiscoveryFailure.of(SocketTimeoutException("read timed out")))
        assertEquals("timeout", DiscoveryFailure.of(InterruptedIOException("timeout")))
        assertEquals("offline", DiscoveryFailure.of(ConnectException("refused")))
        assertEquals("offline", DiscoveryFailure.of(NoRouteToHostException()))
        assertEquals("offline", DiscoveryFailure.of(SocketException("Network is unreachable")))
        assertEquals("io", DiscoveryFailure.of(IOException("something else")))
        assertEquals("error", DiscoveryFailure.of(IllegalStateException("boom")))
    }

    @Test fun `HTTP answers map to rate_limited or http_code`() {
        assertEquals("rate_limited", DiscoveryFailure.ofStatus(403))
        assertEquals("rate_limited", DiscoveryFailure.ofStatus(429))
        assertEquals("http_500", DiscoveryFailure.ofStatus(500))
        assertEquals("http_301", DiscoveryFailure.ofStatus(301))
        assertEquals("parse", DiscoveryFailure.PARSE)
    }

    @Test fun `a certificate rejected for its dates is flagged`() {
        assertEquals(true, DiscoveryFailure.certTimeRejected(SSLHandshakeException("x").apply { initCause(CertificateNotYetValidException()) }))
        assertEquals(true, DiscoveryFailure.certTimeRejected(SSLHandshakeException("x").apply { initCause(CertPathValidatorException("timestamp check failed")) }))
        assertEquals(false, DiscoveryFailure.certTimeRejected(SSLHandshakeException("Trust anchor for certification path not found")))
    }

    @Test fun `the device clock is judged against a server Date when there is one`() {
        val t = 1_790_596_800_000L
        assertEquals("unknown", DiscoveryFailure.clock(t, null))
        assertEquals("ok", DiscoveryFailure.clock(t + 5 * 60_000, t))
        assertEquals("off", DiscoveryFailure.clock(t + 2 * 60 * 60_000, t))
        assertEquals("off", DiscoveryFailure.clock(t - 400L * 24 * 60 * 60_000, t))
    }
}
