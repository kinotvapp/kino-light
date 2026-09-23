package com.arkiv.player.data.markers

import com.arkiv.player.data.db.SkipMarkerDao
import com.arkiv.player.data.db.SkipMarkerEntity
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSkipMarkerDao : SkipMarkerDao {
    val rows: MutableMap<String, SkipMarkerEntity> = linkedMapOf()

    override suspend fun upsert(marker: SkipMarkerEntity) {
        rows[marker.id] = marker
    }

    override suspend fun get(itemId: String): SkipMarkerEntity? =
        rows.values.firstOrNull { it.itemId == itemId && it.episodeId == "" }

    override fun observe(itemId: String) =
        MutableStateFlow(rows.values.firstOrNull { it.itemId == itemId && it.episodeId == "" })

    override fun observeForChapter(itemId: String, episodeId: String) =
        MutableStateFlow(
            rows.values.filter {
                it.itemId == itemId && (it.episodeId == episodeId || it.episodeId == "") && !it.deleted
            },
        )

    override suspend fun getById(id: String): SkipMarkerEntity? = rows[id]

    override suspend fun delete(itemId: String) {
        rows.entries.removeAll { it.value.itemId == itemId && it.value.episodeId == "" }
    }

    override suspend fun getAll(): List<SkipMarkerEntity> = rows.values.toList()

    // Task 2 (companion sync push side) added this. No test in this file exercises it; minimal
    // implementation to satisfy the interface.
    override suspend fun getMarkersSince(cursor: Long): List<SkipMarkerEntity> =
        rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
}
