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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val sources: Flow<List<OwnLiveSourceEntity>> = dao.flowAll()

    /** [editingId] null = a new source. */
    suspend fun save(editingId: String?, form: OwnSourceForm): OwnSaveResult {
        val all = dao.all()
        if (editingId == null && all.size >= OwnLive.MAX_SOURCES) return OwnSaveResult.TooMany
        val id = editingId ?: newId()
        val others = all.filter { it.id != id }
        return when (val r = form.validate(id, others.map { it.url })) {
            is OwnFormResult.Invalid -> OwnSaveResult.Invalid(r.errors)
            is OwnFormResult.Valid -> {
                // Two lists on one server path share a cache file, their channel codes and their category ids
                // (the key ignores the query), so the second would overwrite the first: one list per path.
                if (r.source.kind == "PLAYLIST") {
                    val key = PlaylistSource.cacheKey(r.source.url)
                    if (others.any { it.kind == "PLAYLIST" && PlaylistSource.cacheKey(it.url) == key }) {
                        return OwnSaveResult.Invalid(mapOf(OwnField.URL to "Ya tienes una lista en ese mismo servidor y ruta: cada lista necesita una dirección distinta (no basta otro usuario o clave)"))
                    }
                }
                // A new row is sealed by the DB trigger. An EDIT replaces a row that may carry a clock from the other
                // device (TVs often run ahead): it must be newer than that, or it is never pushed and loses the merge.
                val previous = if (editingId != null) dao.get(id) else null
                dao.save(if (previous != null) r.source.copy(updatedAt = maxOf(clock(), previous.updatedAt + 1)) else r.source)
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
