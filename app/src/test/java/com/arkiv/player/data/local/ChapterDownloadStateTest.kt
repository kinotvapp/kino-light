package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterDownloadStateTest {

    private fun row(
        state: String,
        progress: Float = 0f,
        bytes: Long = 0,
        error: String? = null,
    ) = DownloadRow(
        episodeId = "magis:abc::e1",
        itemId = "magis:abc",
        itemTitle = "Daima",
        displayName = "E1",
        thumbPath = null,
        itemThumbnailUrl = "",
        state = state,
        progress = progress,
        localUri = null,
        bytes = bytes,
        source = "magis",
        error = error,
        bytesDone = 0,
    )

    @Test
    fun `with no row in the table there is no download`() {
        assertEquals(DownloadDisplayState.NotDownloaded, ChapterDownloadState.of(null))
    }

    @Test
    fun `queued waits its turn`() {
        assertEquals(DownloadDisplayState.Queued, ChapterDownloadState.of(row(LocalDownloadState.QUEUED)))
    }

    @Test
    fun `downloading with known size reports the fraction`() {
        val state = ChapterDownloadState.of(
            row(LocalDownloadState.DOWNLOADING, progress = 0.42f, bytes = 1_000L),
        )
        assertEquals(DownloadDisplayState.Downloading(0.42f), state)
    }

    @Test
    fun `downloading with no known size does not make up a percentage`() {
        val state = ChapterDownloadState.of(row(LocalDownloadState.DOWNLOADING, bytes = 0))
        assertEquals(DownloadDisplayState.Downloading(null), state)
    }

    @Test
    fun `the server's staging goes with no percentage`() {
        assertEquals(
            DownloadDisplayState.Downloading(null),
            ChapterDownloadState.of(row(LocalDownloadState.STAGING, progress = 0.5f, bytes = 10)),
        )
    }

    @Test
    fun `completed is done`() {
        assertEquals(DownloadDisplayState.Done, ChapterDownloadState.of(row(LocalDownloadState.COMPLETED)))
    }

    @Test
    fun `a completed row with a reason is still done`() {
        // The "error" of a completed row is the reason nothing had to be downloaded
        // (DuplicateDownloadPolicy.ADOPTED_REASON), not a failure.
        val state = ChapterDownloadState.of(
            row(LocalDownloadState.COMPLETED, error = DuplicateDownloadPolicy.ADOPTED_REASON),
        )
        assertEquals(DownloadDisplayState.Done, state)
    }

    @Test
    fun `failed keeps the reason`() {
        val state = ChapterDownloadState.of(
            row(LocalDownloadState.FAILED, error = "Este episodio no tiene un archivo descargable"),
        )
        assertEquals(DownloadDisplayState.Failed("Este episodio no tiene un archivo descargable"), state)
    }

    @Test
    fun `a heavy torrent asks for confirmation and does not show as if it were downloading`() {
        assertEquals(
            DownloadDisplayState.NeedsConfirmation,
            ChapterDownloadState.of(row(LocalDownloadState.NEEDS_CONFIRMATION, bytes = 9_000_000_000L)),
        )
    }
}
