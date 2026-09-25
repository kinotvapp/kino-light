package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.io.File

data class PluginHomeRow(
    val pluginId: String,
    val pluginName: String,
    val color: Long,
    val id: String,
    val title: String,
    val items: List<GatewayResult>,
    /** The plugin's own ref for "Ver más" (it declares `browse`), or null: no "Ver más" card. */
    val ref: String? = null,
)

/**
 * Home rows from every usable plugin with the `home` capability, asked in parallel. Each plugin's
 * raw answer is cached in its data dir for [ttlMs] (6 h) once it parsed into at least one row and
 * fits in [MAX_CACHE_BYTES]: the cached rows are emitted first, even stale, then the fresh ones. A plugin whose `home()` fails contributes nothing and never blocks
 * the rest. Items are built with [PluginContentSource.resultFrom], so a Home card reaches
 * `SearchPlayback` exactly like a search result.
 */
class PluginHomeRows(
    private val plugins: () -> List<InstalledPlugin>,
    private val caller: PluginCaller,
    private val cacheFileFor: (pluginId: String) -> File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 6 * 60 * 60 * 1000L,
    /**
     * Bumped whenever a plugin's session is forgotten (a settings change — see AppGraph's
     * `forgetPluginSession`). A `home()` call still in flight when that happens belongs to the OLD
     * session: [refresh] compares the revision before and after the call and discards (never shows,
     * never caches) an answer whose revision moved — otherwise the old account's rows could be
     * written to `home.json` right after it was deleted, and served as "fresh" for up to [ttlMs]
     * (fix round 1, finding 3). Constant by default: nothing is ever discarded.
     */
    private val sessionRevision: (pluginId: String) -> Int = { 0 },
    private val log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
) {
    private data class Cached(val fetchedAt: Long, val json: String)

    companion object {
        /** A cache file bigger than this is neither written nor read (it's deleted instead). */
        const val MAX_CACHE_BYTES = 2 * 1024 * 1024
    }

    fun rows(): Flow<List<PluginHomeRow>> = flow {
        // A plugin that still needs setup isn't asked: its calls would only fail with auth_required.
        val targets = plugins().filter { "home" in it.manifest.capabilities && !it.needsSetup }
        if (targets.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val cached = targets.associate { it.id to readCache(it.id) }
        emit(assemble(targets) { p -> cached[p.id]?.let { parse(p, it.json) }.orEmpty() })
        val fresh = coroutineScope {
            targets.map { p -> async { p.id to refresh(p, cached[p.id]) } }.awaitAll().toMap()
        }
        emit(assemble(targets) { fresh[it.id].orEmpty() })
    }

    private suspend fun refresh(p: InstalledPlugin, cached: Cached?): List<PluginRow> {
        if (cached != null && clock() - cached.fetchedAt < ttlMs) return parse(p, cached.json)
        val revision = sessionRevision(p.id)
        return try {
            val json = caller.call(p.id, "home", "null", PluginContentSource.HOME_TIMEOUT_MS)
            if (sessionRevision(p.id) != revision) {
                // The session this answer belongs to was forgotten (a settings change) while the
                // call was in flight: it's the OLD account's, never shown, never cached.
                log("[${p.id}] home answer discarded: the session changed while it was in flight")
                return emptyList()
            }
            // Parsed BEFORE it's cached: an answer that can't be read must never be persisted and
            // re-read on every Home open. Nothing usable, nothing cached: the next Home asks again.
            parse(p, json).also { rows -> if (rows.isNotEmpty()) writeCache(p.id, json) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("[${p.id}] home failed: ${e.message}")
            emptyList()
        }
    }

    private fun parse(p: InstalledPlugin, json: String): List<PluginRow> =
        PluginOutput.rows(
            json,
            allowSeries = "episodes" in p.manifest.capabilities,
            allowBrowse = "browse" in p.manifest.capabilities,
            hosts = p.hosts,
        ) { log("[${p.id}] $it") }

    private fun assemble(targets: List<InstalledPlugin>, rowsOf: (InstalledPlugin) -> List<PluginRow>): List<PluginHomeRow> =
        targets.flatMap { p ->
            rowsOf(p).map { r ->
                PluginHomeRow(
                    pluginId = p.id, pluginName = p.manifest.name, color = PluginColors.parse(p.manifest.color),
                    id = r.id, title = r.title, items = r.items.map { PluginContentSource.resultFrom(p, it) }, ref = r.ref,
                )
            }
        }

    /** A file over [MAX_CACHE_BYTES] is deleted unread: reading it on every Home open is the crash. */
    private fun readCache(pluginId: String): Cached? = runCatching {
        val file = cacheFileFor(pluginId)
        if (file.length() > MAX_CACHE_BYTES) {
            log("[$pluginId] home cache over $MAX_CACHE_BYTES bytes, deleted")
            file.delete()
            return@runCatching null
        }
        val o = JSONObject(file.readText())
        Cached(o.getLong("fetchedAt"), o.getString("json"))
    }.getOrNull()

    private fun writeCache(pluginId: String, json: String) {
        runCatching {
            val bytes = JSONObject().put("fetchedAt", clock()).put("json", json).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_CACHE_BYTES) {
                log("[$pluginId] home answer too big to cache (${bytes.size} bytes)")
                return@runCatching
            }
            writeFileAtomically(cacheFileFor(pluginId), bytes)
        }.onFailure { log("[$pluginId] home cache not written: ${it.message}") }
    }
}
