package com.arkiv.player.data.library

import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.EpisodeDownloadStatus
import com.arkiv.player.data.local.GroupedEpisode
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

class DiskSpaceTest {

    private val GB = 1L shl 30

    // Heads up: this is `data.model.Episode` (the domain one), NOT `db.EpisodeEntity`.
    private fun episode(id: String) = Episode(
        id = id,
        itemId = "item",
        section = "",
        displayName = id,
        orderIndex = 0,
        durationSeconds = 0.0,
        thumbPath = null,
    )

    private fun downloaded(id: String, bytesDone: Long) = GroupedEpisode(
        episode = episode(id),
        status = EpisodeDownloadStatus.Tracked(
            DownloadRow(
                episodeId = id,
                itemId = "item",
                itemTitle = "item",
                displayName = id,
                thumbPath = null,
                itemThumbnailUrl = "",
                state = LocalDownloadState.COMPLETED,
                progress = 1f,
                localUri = null,
                bytes = bytesDone,
                source = "web",
                error = null,
                bytesDone = bytesDone,
            ),
        ),
    )

    private fun notDownloaded(id: String) =
        GroupedEpisode(episode = episode(id), status = EpisodeDownloadStatus.NotDownloaded)

    private fun group(vararg eps: GroupedEpisode) =
        DownloadGroup("item", "Serie", "", "web", eps.toList())

    @Test
    fun `sums the bytes of every group`() {
        val groups = listOf(group(downloaded("a", 2 * GB)), group(downloaded("b", 1 * GB)))
        assertEquals(3 * GB, DiskSpace.usedByDownloads(groups))
    }

    @Test
    fun `episodes not downloaded do not add up`() {
        assertEquals(GB, DiskSpace.usedByDownloads(listOf(group(downloaded("a", GB), notDownloaded("b")))))
    }

    @Test
    fun `with no downloads the used amount is zero`() {
        assertEquals(0L, DiskSpace.usedByDownloads(emptyList()))
    }

    @Test
    fun `the summary shows free and used`() {
        assertEquals("12.0 GB libres  ·  3.0 GB en descargas", DiskSpace.summary(12 * GB, 3 * GB))
    }

    @Test
    fun `with nothing used the summary only says free`() {
        assertEquals("12.0 GB libres", DiskSpace.summary(12 * GB, 0L))
    }
}
