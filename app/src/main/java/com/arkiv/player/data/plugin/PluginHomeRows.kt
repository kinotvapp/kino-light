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
     * `forgetPluginSession`/`afterSessionClosed`, bumped TWICE per settings save). A `home()` call
     * still in flight when that happens belongs to the OLD session: [refresh] compares the revision
     * before and after the call and discards (never shows, never caches) an answer whose revision
     * moved. That in-flight check alone isn't sufficient (fix round 2, finding 3) — see [Cached]'s
     * KDoc for why the value is also stamped into the cache file itself.
     *
     * IN-MEMORY, process-lifetime only — and that's exactly why [Cached] ALSO stamps
     * [InstalledPlugin.configRevision] (persisted): this counter resets to its default on every
     * process restart, so a stale write whose stamp happens to equal whatever a FRESH process
     * defaults to (e.g. 0) would otherwise read back as fresh after a restart, even though nothing
     * about the in-memory value ever really "matched" — there was no matching session, just two
     * unrelated zeroes (fix round 3, finding 3a: `home.json` is a FILE, it survives what this
     * counter does not). Constant by default: nothing is ever discarded.
     */
    private val sessionRevision: (pluginId: String) -> Int = { 0 },
    private val log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
) {
    /**
     * [sessionRevision] (the constructor param) is the in-memory, per-process signal — stamped and
     * re-checked at EVERY read exactly as before (fix round 2). [configRevision] is
     * [InstalledPlugin.configRevision] — PERSISTED in `config.json`, survives a restart — stamped
     * and re-checked the SAME way, against the value on the `InstalledPlugin` `refresh`/the instant
     * paint were handed for THIS pass (fix round 3, finding 3a). Both are compared on every read
     * (both the instant-paint pass and [refresh]'s own TTL check); a mismatch in EITHER means the
     * entry is not this session's and is never shown or trusted, only ever a fresh call is. Neither
     * one alone is enough: the in-memory value protects a live process against a runtime instance it
     * shouldn't trust (round 2, finding 3b's "window b"); the persisted value is what still catches
     * staleness after the process — and so the in-memory value — has restarted.
     */
    private data class Cached(val fetchedAt: Long, val sessionRevision: Int, val configRevision: Int, val version: String, val json: String)

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
        // The instant-paint pass shows even a stale-by-TTL cache, but never one stamped with EITHER
        // revision no longer matching: that isn't "old", it's a DIFFERENT session's data -- and
        // configRevision is what still catches that across a process restart (fix round 3).
        emit(
            assemble(targets) { p ->
                cached[p.id]?.takeIf { it.sessionRevision == sessionRevision(p.id) && it.configRevision == p.configRevision && it.version == p.record.version }
                    ?.let { parse(p, it.json) }.orEmpty()
            },
        )
        val fresh = coroutineScope {
            targets.map { p -> async { p.id to refresh(p, cached[p.id]) } }.awaitAll().toMap()
        }
        emit(assemble(targets) { fresh[it.id].orEmpty() })
    }

    private suspend fun refresh(p: InstalledPlugin, cached: Cached?): List<PluginRow> {
        // `version` too: rows cached by an older version of the plugin (e.g. without a browse `ref`)
        // must not outlive an update for the rest of the TTL.
        if (cached != null && cached.sessionRevision == sessionRevision(p.id) && cached.configRevision == p.configRevision &&
            cached.version == p.record.version && clock() - cached.fetchedAt < ttlMs
        ) {
            return parse(p, cached.json)
        }
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
            // Stamped with p.configRevision -- the snapshot this WHOLE pass was handed, taken before
            // the call, same as `revision` above: if a save landed during the call and bumped it,
            // ANY later read (this process or after a restart) compares against the NEW persisted
            // value and correctly refuses this entry, exactly like the in-memory revision already did.
            parse(p, json).also { rows -> if (rows.isNotEmpty()) writeCache(p.id, revision, p.configRevision, p.record.version, json) }
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
        // optInt(..., 0) on both: a pre-round-3 cache file (no "configRevision" field at all) reads
        // as 0, which -- unless a plugin's persisted revision genuinely IS still 0 -- simply fails
        // the comparison and is treated as not fresh: safe, self-healing, no migration needed.
        // A cache file without "version" (written before it was stamped) reads as "": never fresh.
        Cached(o.getLong("fetchedAt"), o.optInt("sessionRevision", 0), o.optInt("configRevision", 0), o.optString("version", ""), o.getString("json"))
    }.getOrNull()

    private fun writeCache(pluginId: String, sessionRevision: Int, configRevision: Int, version: String, json: String) {
        runCatching {
            val bytes = JSONObject().put("fetchedAt", clock()).put("sessionRevision", sessionRevision)
                .put("configRevision", configRevision).put("version", version).put("json", json).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_CACHE_BYTES) {
                log("[$pluginId] home answer too big to cache (${bytes.size} bytes)")
                return@runCatching
            }
            writeFileAtomically(cacheFileFor(pluginId), bytes)
        }.onFailure { log("[$pluginId] home cache not written: ${it.message}") }
    }
}
