package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueuePolicyTest {

    @Test
    fun `picks the oldest queued row`() {
        val rows = listOf(
            QueueRow("b", LocalDownloadState.QUEUED, createdAt = 200),
            QueueRow("a", LocalDownloadState.QUEUED, createdAt = 100),
            QueueRow("c", LocalDownloadState.QUEUED, createdAt = 300),
        )
        assertEquals("a", DownloadQueuePolicy.nextToProcess(rows)?.episodeId)
    }

    @Test
    fun `a row half-downloaded takes priority over queued ones`() {
        val rows = listOf(
            QueueRow("a", LocalDownloadState.QUEUED, createdAt = 100),
            QueueRow("b", LocalDownloadState.DOWNLOADING, createdAt = 900),
        )
        assertEquals("b", DownloadQueuePolicy.nextToProcess(rows)?.episodeId)
    }

    @Test
    fun `staging also gets picked back up before what's queued`() {
        val rows = listOf(
            QueueRow("a", LocalDownloadState.QUEUED, createdAt = 100),
            QueueRow("b", LocalDownloadState.STAGING, createdAt = 900),
        )
        assertEquals("b", DownloadQueuePolicy.nextToProcess(rows)?.episodeId)
    }

    @Test
    fun `doesn't pick completed, failed, or awaiting-confirmation rows`() {
        val rows = listOf(
            QueueRow("a", LocalDownloadState.COMPLETED, createdAt = 100),
            QueueRow("b", LocalDownloadState.FAILED, createdAt = 200),
            QueueRow("c", LocalDownloadState.NEEDS_CONFIRMATION, createdAt = 300),
        )
        assertNull(DownloadQueuePolicy.nextToProcess(rows))
    }

    @Test
    fun `an empty queue gives nothing`() {
        assertNull(DownloadQueuePolicy.nextToProcess(emptyList()))
    }

    @Test
    fun `terminal states`() {
        assertTrue(DownloadQueuePolicy.isTerminal(LocalDownloadState.COMPLETED))
        assertTrue(DownloadQueuePolicy.isTerminal(LocalDownloadState.FAILED))
        assertFalse(DownloadQueuePolicy.isTerminal(LocalDownloadState.QUEUED))
        assertFalse(DownloadQueuePolicy.isTerminal(LocalDownloadState.DOWNLOADING))
    }

    @Test
    fun `only failed and awaiting-confirmation can be retried`() {
        assertTrue(DownloadQueuePolicy.isRetryable(LocalDownloadState.FAILED))
        assertTrue(DownloadQueuePolicy.isRetryable(LocalDownloadState.NEEDS_CONFIRMATION))
        assertFalse(DownloadQueuePolicy.isRetryable(LocalDownloadState.COMPLETED))
        assertFalse(DownloadQueuePolicy.isRetryable(LocalDownloadState.QUEUED))
    }
}
