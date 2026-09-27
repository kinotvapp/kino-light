package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DownloadRetryPolicyTest {

    @Test
    fun `a network cutoff is transient`() {
        assertTrue(DownloadRetryPolicy.isTransient(UnknownHostException("no DNS")))
        assertTrue(DownloadRetryPolicy.isTransient(SocketTimeoutException("timeout")))
        assertTrue(DownloadRetryPolicy.isTransient(IOException("connection reset")))
    }

    @Test
    fun `a download cut off halfway is transient`() {
        assertTrue(DownloadRetryPolicy.isTransient(IncompleteDownloadException(written = 400, total = 1000)))
    }

    @Test
    fun `a manifest answered where a file was expected is definitive`() {
        assertFalse(DownloadRetryPolicy.isTransient(ManifestResponseException()))
    }

    // ---- what the worker does with a failure ----

    @Test
    fun `a permanent refusal is a final state, whatever else the failure says`() {
        assertEquals(FailureResolution.REFUSE, DownloadRetryPolicy.resolve(transient = false, permanent = true, attempt = 0))
        assertEquals(FailureResolution.REFUSE, DownloadRetryPolicy.resolve(transient = true, permanent = true, attempt = 0))
        assertEquals(FailureResolution.REFUSE, DownloadRetryPolicy.resolve(transient = false, permanent = true, attempt = 3))
    }

    @Test
    fun `a transient failure retries while attempts remain, then fails`() {
        assertEquals(FailureResolution.RETRY, DownloadRetryPolicy.resolve(transient = true, permanent = false, attempt = 0))
        assertEquals(FailureResolution.RETRY, DownloadRetryPolicy.resolve(transient = true, permanent = false, attempt = DownloadRetryPolicy.MAX_ATTEMPTS - 2))
        assertEquals(FailureResolution.FAIL, DownloadRetryPolicy.resolve(transient = true, permanent = false, attempt = DownloadRetryPolicy.MAX_ATTEMPTS - 1))
    }

    @Test
    fun `a definitive failure fails at once and is the one worth reporting`() {
        assertEquals(FailureResolution.FAIL, DownloadRetryPolicy.resolve(transient = false, permanent = false, attempt = 0))
        assertTrue(DownloadRetryPolicy.reports(transient = false, permanent = false))
        // Network trouble and expected refusals are not bugs.
        assertFalse(DownloadRetryPolicy.reports(transient = true, permanent = false))
        assertFalse(DownloadRetryPolicy.reports(transient = false, permanent = true))
    }

    /** A plugin the person switched off with rows queued: retryable once it is back, never a bug. */
    @Test
    fun `an expected failure still fails and stays retryable, but is never reported`() {
        assertEquals(FailureResolution.FAIL, DownloadRetryPolicy.resolve(transient = false, permanent = false, attempt = 0))
        assertFalse(DownloadRetryPolicy.reports(transient = false, permanent = false, expected = true))
    }

    @Test
    fun `5xx and server throttling get retried`() {
        assertTrue(DownloadRetryPolicy.isTransient(HttpStatusException(500)))
        assertTrue(DownloadRetryPolicy.isTransient(HttpStatusException(503)))
        assertTrue(DownloadRetryPolicy.isTransient(HttpStatusException(429)))
        assertTrue(DownloadRetryPolicy.isTransient(HttpStatusException(408)))
    }

    @Test
    fun `an expired or nonexistent link doesn't get retried`() {
        assertFalse(DownloadRetryPolicy.isTransient(HttpStatusException(403)))
        assertFalse(DownloadRetryPolicy.isTransient(HttpStatusException(404)))
        assertFalse(DownloadRetryPolicy.isTransient(HttpStatusException(410)))
    }

    @Test
    fun `whatever isn't network-related is definitive`() {
        // "Fuente no soportada", "sin espacio", "película web no soportada": logic and environment
        // errors that are going to fail exactly the same way 30 seconds from now.
        assertFalse(DownloadRetryPolicy.isTransient(IllegalStateException("Fuente no soportada")))
        assertFalse(DownloadRetryPolicy.isTransient(IllegalArgumentException("sin espacio")))
    }

    @Test
    fun `a full disk is definitive, retrying only writes more onto it`() {
        // Our own guard's typed exception...
        assertFalse(DownloadRetryPolicy.isTransient(InsufficientSpaceException(availableBytes = 100L * 1024 * 1024)))
        // ...and the raw OS error, which is an IOException and used to be retried as "network".
        assertFalse(DownloadRetryPolicy.isTransient(IOException("write failed: ENOSPC (No space left on device)")))
        assertFalse(DownloadRetryPolicy.isTransient(IOException("No space left on device")))
        // An ordinary IOException stays transient: only the full-disk ones changed.
        assertTrue(DownloadRetryPolicy.isTransient(IOException("connection reset")))
    }

    @Test
    fun `the full-disk message tells the user what to do`() {
        val message = InsufficientSpaceException(availableBytes = 380L * 1024 * 1024).message.orEmpty()
        assertTrue(message, message.contains("380 MB"))
        assertTrue(message, message.contains("Reintentar"))
    }

    @Test
    fun `what's definitive never gets retried even with attempts to spare`() {
        assertFalse(DownloadRetryPolicy.shouldRetry(transient = false, attempt = 0))
    }

    @Test
    fun `what's transient gets retried up to the cap and no more`() {
        assertTrue(DownloadRetryPolicy.shouldRetry(transient = true, attempt = 0))
        assertTrue(DownloadRetryPolicy.shouldRetry(transient = true, attempt = DownloadRetryPolicy.MAX_ATTEMPTS - 2))
        // With MAX_ATTEMPTS attempts already made the row is given up as lost: it stays `failed`
        // with its reason and the screen's "Reintentar" button is still available.
        assertFalse(DownloadRetryPolicy.shouldRetry(transient = true, attempt = DownloadRetryPolicy.MAX_ATTEMPTS - 1))
        assertFalse(DownloadRetryPolicy.shouldRetry(transient = true, attempt = DownloadRetryPolicy.MAX_ATTEMPTS))
    }
}
