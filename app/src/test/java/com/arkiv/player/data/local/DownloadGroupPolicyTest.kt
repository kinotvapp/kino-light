package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGroupPolicyTest {

    private fun episode(id: String, itemId: String, order: Int, name: String = id) = Episode(
        id = id,
        itemId = itemId,
        section = "",
        displayName = name,
        orderIndex = order,
        durationSeconds = 0.0,
        thumbPath = null,
    )

    private fun row(
        episodeId: String,
        itemId: String,
        state: String,
        displayName: String = episodeId,
    ) = DownloadRow(
        episodeId = episodeId,
        itemId = itemId,
        itemTitle = "Serie $itemId",
        displayName = displayName,
        thumbPath = null,
        itemThumbnailUrl = "",
        state = state,
        progress = 0f,
        localUri = null,
        bytes = 0,
        source = "archive",
        error = null,
        bytesDone = 0,
    )

    private fun meta(itemId: String) = mapOf(
        itemId to DownloadItemMeta(title = "Serie $itemId", thumbnailUrl = "https://x/$itemId.jpg", source = "archive"),
    )

    // --- buildGroups -----------------------------------------------------------------------

    @Test
    fun `the group carries ALL of the item's episodes, not just the queued ones`() {
        val episodes = listOf(episode("s::1", "s", 0), episode("s::2", "s", 1), episode("s::3", "s", 2))
        val downloads = listOf(row("s::2", "s", LocalDownloadState.COMPLETED))

        val groups = DownloadGroupPolicy.buildGroups(downloads, mapOf("s" to episodes), meta("s"))

        assertEquals(1, groups.size)
        assertEquals(3, groups[0].episodes.size)
        assertEquals(
            listOf(EpisodeDownloadStatus.NotDownloaded::class, EpisodeDownloadStatus.Tracked::class, EpisodeDownloadStatus.NotDownloaded::class),
            groups[0].episodes.map { it.status::class },
        )
    }

    @Test
    fun `episodes end up ordered by orderIndex regardless of input order`() {
        val episodes = listOf(episode("s::3", "s", 2), episode("s::1", "s", 0), episode("s::2", "s", 1))
        val downloads = listOf(row("s::1", "s", LocalDownloadState.QUEUED))

        val groups = DownloadGroupPolicy.buildGroups(downloads, mapOf("s" to episodes), meta("s"))

        assertEquals(listOf("s::1", "s::2", "s::3"), groups[0].episodes.map { it.episode.id })
    }

    @Test
    fun `an item with no metadata (deleted from the library) is excluded from the listing`() {
        val downloads = listOf(row("s::1", "s", LocalDownloadState.COMPLETED))
        val groups = DownloadGroupPolicy.buildGroups(downloads, mapOf("s" to listOf(episode("s::1", "s", 0))), itemMeta = emptyMap())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `an item with no episode cache yet still falls back to downloads' tracked rows`() {
        val downloads = listOf(
            row("s::1", "s", LocalDownloadState.DOWNLOADING),
            row("s::2", "s", LocalDownloadState.QUEUED),
        )
        val groups = DownloadGroupPolicy.buildGroups(downloads, episodesByItem = emptyMap(), itemMeta = meta("s"))

        assertEquals(1, groups.size)
        assertEquals(2, groups[0].episodes.size)
        assertTrue(groups[0].episodes.all { it.status is EpisodeDownloadStatus.Tracked })
    }

    @Test
    fun `the groups' order follows their order of appearance in downloads`() {
        val downloads = listOf(
            row("b::1", "b", LocalDownloadState.QUEUED),
            row("a::1", "a", LocalDownloadState.QUEUED),
            row("b::2", "b", LocalDownloadState.QUEUED),
        )
        val episodesByItem = mapOf(
            "a" to listOf(episode("a::1", "a", 0)),
            "b" to listOf(episode("b::1", "b", 0), episode("b::2", "b", 1)),
        )
        val itemMeta = meta("a") + meta("b")

        val groups = DownloadGroupPolicy.buildGroups(downloads, episodesByItem, itemMeta)

        assertEquals(listOf("b", "a"), groups.map { it.itemId })
    }

    @Test
    fun `a movie (a single episode) is a plain row`() {
        val downloads = listOf(row("m::1", "m", LocalDownloadState.COMPLETED))
        val groups = DownloadGroupPolicy.buildGroups(downloads, mapOf("m" to listOf(episode("m::1", "m", 0))), meta("m"))
        assertTrue(groups[0].isSingleEpisode)
    }

    @Test
    fun `a series with a single tracked episode but several total is NOT a plain row`() {
        val episodes = listOf(episode("s::1", "s", 0), episode("s::2", "s", 1))
        val downloads = listOf(row("s::1", "s", LocalDownloadState.COMPLETED))
        val groups = DownloadGroupPolicy.buildGroups(downloads, mapOf("s" to episodes), meta("s"))
        assertTrue(!groups[0].isSingleEpisode)
    }

    // --- summarize ---------------------------------------------------------------------------

    @Test
    fun `base summary with no extra activity`() {
        val episodes = (1..24).map {
            val state = if (it <= 3) LocalDownloadState.COMPLETED else null
            GroupedEpisode(
                episode("s::$it", "s", it),
                state?.let { st -> EpisodeDownloadStatus.Tracked(row("s::$it", "s", st)) } ?: EpisodeDownloadStatus.NotDownloaded,
            )
        }
        assertEquals("3 de 24 guardados", DownloadGroupPolicy.summarize(episodes))
    }

    @Test
    fun `summary with one download in progress, same as the request's example`() {
        val episodes = mutableListOf<GroupedEpisode>()
        repeat(3) { i -> episodes += GroupedEpisode(episode("s::c$i", "s", i), EpisodeDownloadStatus.Tracked(row("s::c$i", "s", LocalDownloadState.COMPLETED))) }
        episodes += GroupedEpisode(episode("s::d", "s", 3), EpisodeDownloadStatus.Tracked(row("s::d", "s", LocalDownloadState.DOWNLOADING)))
        repeat(20) { i -> episodes += GroupedEpisode(episode("s::n$i", "s", 4 + i), EpisodeDownloadStatus.NotDownloaded) }

        assertEquals("3 de 24 guardados · 1 bajando", DownloadGroupPolicy.summarize(episodes))
    }

    @Test
    fun `summary chains every non-empty clause in a fixed order`() {
        val episodes = listOf(
            GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.Tracked(row("s::1", "s", LocalDownloadState.COMPLETED))),
            GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.Tracked(row("s::2", "s", LocalDownloadState.DOWNLOADING))),
            GroupedEpisode(episode("s::3", "s", 2), EpisodeDownloadStatus.Tracked(row("s::3", "s", LocalDownloadState.STAGING))),
            GroupedEpisode(episode("s::4", "s", 3), EpisodeDownloadStatus.Tracked(row("s::4", "s", LocalDownloadState.QUEUED))),
            GroupedEpisode(episode("s::5", "s", 4), EpisodeDownloadStatus.Tracked(row("s::5", "s", LocalDownloadState.FAILED))),
            GroupedEpisode(episode("s::6", "s", 5), EpisodeDownloadStatus.Tracked(row("s::6", "s", LocalDownloadState.NEEDS_CONFIRMATION))),
        )

        assertEquals(
            "1 de 6 guardados · 1 bajando · 1 preparando · 1 en cola · 1 con error · 1 por confirmar",
            DownloadGroupPolicy.summarize(episodes),
        )
    }

    @Test
    fun `with no episodes the summary is 0 de 0`() {
        assertEquals("0 de 0 guardados", DownloadGroupPolicy.summarize(emptyList()))
    }

    // --- group action filters -------------------------------------------------------

    @Test
    fun `activeEpisodeIds takes queued, downloading and staging, and discards the rest`() {
        val group = DownloadGroup(
            itemId = "s", itemTitle = "Serie", itemThumbnailUrl = "", source = "archive",
            episodes = listOf(
                GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.Tracked(row("s::1", "s", LocalDownloadState.QUEUED))),
                GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.Tracked(row("s::2", "s", LocalDownloadState.DOWNLOADING))),
                GroupedEpisode(episode("s::3", "s", 2), EpisodeDownloadStatus.Tracked(row("s::3", "s", LocalDownloadState.STAGING))),
                GroupedEpisode(episode("s::4", "s", 3), EpisodeDownloadStatus.Tracked(row("s::4", "s", LocalDownloadState.COMPLETED))),
                GroupedEpisode(episode("s::5", "s", 4), EpisodeDownloadStatus.Tracked(row("s::5", "s", LocalDownloadState.FAILED))),
                GroupedEpisode(episode("s::6", "s", 5), EpisodeDownloadStatus.NotDownloaded),
            ),
        )
        assertEquals(listOf("s::1", "s::2", "s::3"), DownloadGroupPolicy.activeEpisodeIds(group))
    }

    @Test
    fun `failedEpisodeIds takes only failed, not needs_confirmation`() {
        val group = DownloadGroup(
            itemId = "s", itemTitle = "Serie", itemThumbnailUrl = "", source = "archive",
            episodes = listOf(
                GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.Tracked(row("s::1", "s", LocalDownloadState.FAILED))),
                GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.Tracked(row("s::2", "s", LocalDownloadState.NEEDS_CONFIRMATION))),
            ),
        )
        assertEquals(listOf("s::1"), DownloadGroupPolicy.failedEpisodeIds(group))
    }

    @Test
    fun `trackedEpisodeIds takes everything that has a row, regardless of state`() {
        val group = DownloadGroup(
            itemId = "s", itemTitle = "Serie", itemThumbnailUrl = "", source = "archive",
            episodes = listOf(
                GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.Tracked(row("s::1", "s", LocalDownloadState.COMPLETED))),
                GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.Tracked(row("s::2", "s", LocalDownloadState.FAILED))),
                GroupedEpisode(episode("s::3", "s", 2), EpisodeDownloadStatus.NotDownloaded),
            ),
        )
        assertEquals(listOf("s::1", "s::2"), DownloadGroupPolicy.trackedEpisodeIds(group))
    }

    // --- firstPlayableEpisodeId ---------------------------------------------------------------

    @Test
    fun `firstPlayableEpisodeId takes the first completed one in the group's order, not the first to finish`() {
        val group = DownloadGroup(
            itemId = "s", itemTitle = "Serie", itemThumbnailUrl = "", source = "archive",
            episodes = listOf(
                GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.NotDownloaded),
                GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.Tracked(row("s::2", "s", LocalDownloadState.COMPLETED))),
                GroupedEpisode(episode("s::3", "s", 2), EpisodeDownloadStatus.Tracked(row("s::3", "s", LocalDownloadState.COMPLETED))),
            ),
        )
        assertEquals("s::2", DownloadGroupPolicy.firstPlayableEpisodeId(group))
    }

    @Test
    fun `firstPlayableEpisodeId is null if no episode is completed`() {
        val group = DownloadGroup(
            itemId = "s", itemTitle = "Serie", itemThumbnailUrl = "", source = "archive",
            episodes = listOf(
                GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.Tracked(row("s::1", "s", LocalDownloadState.DOWNLOADING))),
                GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.NotDownloaded),
            ),
        )
        assertEquals(null, DownloadGroupPolicy.firstPlayableEpisodeId(group))
    }

    // --- rowThumbnail ------------------------------------------------------------------------

    @Test
    fun `rowThumbnail prefers the episode's own thumbnail over the item poster`() {
        assertEquals("episode.jpg", DownloadGroupPolicy.rowThumbnail("episode.jpg", "poster.jpg"))
    }

    @Test
    fun `rowThumbnail falls back to the item poster when the episode has none`() {
        assertEquals("poster.jpg", DownloadGroupPolicy.rowThumbnail(null, "poster.jpg"))
    }
}
