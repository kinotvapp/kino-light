package com.arkiv.player.data.live

import com.arkiv.player.data.db.OwnLiveSourceDao
import com.arkiv.player.data.db.OwnLiveSourceEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

sealed interface OwnSaveResult {
    object Saved : OwnSaveResult
    data class Invalid(val errors: Map<OwnField, String>) : OwnSaveResult
    object TooMany : OwnSaveResult
}

/** The person's own live sources: validation, the per-device limit and tombstones, over the DAO. */
class OwnLiveStore(
    private val dao: OwnLiveSourceDao,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val sources: Flow<List<OwnLiveSourceEntity>> = dao.flowAll()

    /** [editingId] null = a new source. */
    suspend fun save(editingId: String?, form: OwnSourceForm): OwnSaveResult {
        val all = dao.all()
        if (editingId == null && all.size >= OwnLive.MAX_SOURCES) return OwnSaveResult.TooMany
        val id = editingId ?: newId()
        return when (val r = form.validate(id, all.filter { it.id != id }.map { it.url })) {
            is OwnFormResult.Invalid -> OwnSaveResult.Invalid(r.errors)
            is OwnFormResult.Valid -> {
                dao.save(r.source)
                OwnSaveResult.Saved
            }
        }
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** The source behind a channel code of this provider: a single channel's code IS its id, a playlist entry's is `~<list key>.<entry>`. */
    suspend fun sourceFor(code: String): OwnLiveSourceEntity? {
        if (!code.startsWith("~")) return dao.get(code)?.takeIf { !it.deleted }
        val key = code.substring(1).substringBefore('.')
        return dao.all().firstOrNull { it.kind == "PLAYLIST" && PlaylistSource.cacheKey(it.url) == key }
    }
}
