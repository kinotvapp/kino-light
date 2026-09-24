package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAvailabilityTest {

    @Test
    fun `downloads are allowed on a phone or tablet and never on a TV`() {
        assertTrue(DownloadAvailability.allowed(isTelevision = false))
        assertFalse(DownloadAvailability.allowed(isTelevision = true))
    }

    private fun row(id: String, state: String) = QueueRow(id, state, createdAt = 0L)

    @Test
    fun `on a TV every download that never finished is discarded, whatever state it was left in`() {
        val rows = listOf(
            row("queued", LocalDownloadState.QUEUED),
            row("downloading", LocalDownloadState.DOWNLOADING),
            row("failed", LocalDownloadState.FAILED),
            row("staging", LocalDownloadState.STAGING),
            row("needs", LocalDownloadState.NEEDS_CONFIRMATION),
        )
        assertEquals(
            listOf("queued", "downloading", "failed", "staging", "needs"),
            DownloadAvailability.unfinishedOnTv(rows),
        )
    }

    @Test
    fun `finished downloads are kept since the person saved them on purpose and can still play or delete them`() {
        val rows = listOf(
            row("done-1", LocalDownloadState.COMPLETED),
            row("half", LocalDownloadState.DOWNLOADING),
            row("done-2", LocalDownloadState.COMPLETED),
        )
        assertEquals(listOf("half"), DownloadAvailability.unfinishedOnTv(rows))
    }

    @Test
    fun `nothing to discard when there are no downloads or all are finished`() {
        assertEquals(emptyList<String>(), DownloadAvailability.unfinishedOnTv(emptyList()))
        assertEquals(
            emptyList<String>(),
            DownloadAvailability.unfinishedOnTv(listOf(row("a", LocalDownloadState.COMPLETED))),
        )
    }
}
