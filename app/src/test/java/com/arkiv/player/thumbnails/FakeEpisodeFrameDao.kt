package com.arkiv.player.thumbnails

import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory fake of [EpisodeFrameDao], shared by the frame tests.
 *
 * Each method copies the semantics of its real `@Query`.
 */
class FakeEpisodeFrameDao : EpisodeFrameDao {
    val rows: MutableMap<String, EpisodeFrameEntity> = java.util.concurrent.ConcurrentHashMap()

    override suspend fun upsert(frame: EpisodeFrameEntity) {
        rows[frame.episodeId] = frame
    }

    override suspend fun get(episodeId: String): EpisodeFrameEntity? =
        rows[episodeId]?.takeIf { it.deleted == 0 }

    override suspend fun getIncludingDeleted(episodeId: String): EpisodeFrameEntity? = rows[episodeId]

    override fun observeForItem(itemId: String) = MutableStateFlow(emptyList<EpisodeFrameEntity>())

    override fun observeAll() = MutableStateFlow(emptyList<EpisodeFrameEntity>())

    override suspend fun deleteAll() {
        rows.clear()
    }
}
