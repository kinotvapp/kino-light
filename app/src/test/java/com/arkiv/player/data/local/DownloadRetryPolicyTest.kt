package com.arkiv.player.data.local

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
