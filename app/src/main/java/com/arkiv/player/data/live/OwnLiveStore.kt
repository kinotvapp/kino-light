package com.arkiv.player.data.live

import com.arkiv.player.data.db.OwnLiveSourceDao
import com.arkiv.player.data.db.OwnLiveSourceEntity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

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
        // Pasted text (up to 2 MB) is parsed to be checked: off the main thread.
        val checked = if (form.pastedText != null) withContext(Dispatchers.Default) { form.validate(id, others.map { it.url }) }
        else form.validate(id, others.map { it.url })
        return when (val r = checked) {
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
                val row = if (previous != null) r.source.copy(updatedAt = maxOf(clock(), previous.updatedAt + 1)) else r.source
                val content = r.content
                when {
                    // New text: the row and its parts carry the same explicit clock, so a peer can tell an older text's parts.
                    content != null -> {
                        val now = maxOf(clock(), (previous?.updatedAt ?: 0L) + 1)
                        dao.saveWithParts(row.copy(updatedAt = now), OwnPastedList.split(id, content.text, content.digest, now))
                    }
                    // An edit of a pasted list that keeps its text (name, headers...).
                    OwnPastedList.isPasted(row.url) -> {
                        val digest = previous?.contentDigest?.takeIf { OwnPastedList.isPasted(previous.url) }
                            ?: return OwnSaveResult.Invalid(mapOf(OwnField.URL to "Pega la lista o abre un archivo"))
                        dao.save(row.copy(contentDigest = digest))
                    }
                    // Was a pasted list, is now an address: its text goes.
                    previous?.contentDigest != null -> dao.saveWithParts(row, emptyList())
                    else -> dao.save(row)
                }
                OwnSaveResult.Saved
            }
        }
    }

    suspend fun delete(id: String) = dao.deleteWithParts(id)

    /** The text of a pasted list, once every part of it is here and checks out; null otherwise. */
    suspend fun contentOf(id: String): String? {
        val row = dao.get(id)?.takeIf { !it.deleted && OwnPastedList.isPasted(it.url) } ?: return null
        val digest = row.contentDigest ?: return null
        val parts = dao.parts(id)
        return withContext(Dispatchers.Default) { OwnPastedList.join(parts, digest) }
    }

    /** The source behind a channel code of this provider: a single channel's code IS its id, a playlist entry's is `~<list key>.<entry>`. */
    suspend fun sourceFor(code: String): OwnLiveSourceEntity? {
        if (!code.startsWith("~")) return dao.get(code)?.takeIf { !it.deleted }
        val key = code.substring(1).substringBefore('.')
        return dao.all().firstOrNull { it.kind == "PLAYLIST" && PlaylistSource.cacheKey(it.url) == key }
    }
}
