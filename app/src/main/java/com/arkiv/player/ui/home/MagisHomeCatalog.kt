package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogSection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One pass over the VOD roots: the classified [rows], and the roots that contributed nothing to
 * them -- they failed, or came back with no sections, which `tree` also answers when the portal
 * errors. Either way it's worth asking again later: a later [MagisHomeCatalog.load] retries them.
 */
data class MagisHome(val rows: List<MagisHomeRow>, val missing: Set<MagisKind>)

/**
 * The home's rows, straight from the Magis catalog: the four VOD roots requested in parallel and
 * classified on the device ([MagisHomeClassifier]). The adults root is never asked for.
 *
 * [tree] is `MagisLiveCatalog.tree` in the app (2 h in-memory cache, so the home and "Ver todo"
 * share one fetch); a lambda here so the JVM tests don't need the portal. A root that fails (portal
 * error, timeout) just contributes nothing to [load] -- it doesn't take the other three down with
 * it. Failures aren't cached, so another [load] asks again only for the roots that are missing.
 *
 * Freshness (see [HomeFreshness]): [invalidate] ("Recargar") marks the persisted snapshot stale
 * WITHOUT deleting it and makes the next pass read every root through [freshTree] (past the
 * in-memory cache), each root only until it answers once. A partial pass is reused for
 * [HomeFreshness.PARTIAL_PASS_TTL_MS] only; a pass where every root failed falls back to the last
 * good snapshot. Passes are serialized, so concurrent readers (Home, Categorías, "Ver más") never
 * ask the portal twice for the same thing.
 */
class MagisHomeCatalog(
    private val tree: suspend (root: String) -> List<CatalogSection>,
    /** The persistent cache. Null in the JVM tests, which exercise the classification with no Room. */
    private val store: HomeCatalogStore? = null,
    /** A cold snapshot older than this is refreshed instead of served (see [rows]). */
    private val ttlMs: Long = TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    /** [tree] past its in-memory cache (`MagisLiveCatalog.tree(force = true)`), used after [invalidate]. */
    private val freshTree: suspend (root: String) -> List<CatalogSection> = tree,
) {
    private val passLock = Mutex()

    /** Bumped by [invalidate]. The snapshot is stale while it's ahead of [completedGeneration]. */
    @Volatile private var generation = 0

    /** The [generation] of the last complete pass this process made. */
    @Volatile private var completedGeneration = 0

    /** Per root, the [generation] it was last read through [freshTree] successfully. */
    private val forcedGeneration = ConcurrentHashMap<MagisKind, Int>()

    /** The last partial pass (or the fallback served for it), reused for [HomeFreshness.PARTIAL_PASS_TTL_MS]. */
    private class FailedPass(val at: Long, val generation: Int, val rows: List<MagisHomeRow>)

    @Volatile private var failedPass: FailedPass? = null

    /**
     * "Recargar": the next pass asks the portal for every root again, past both caches. The Room
     * snapshot is only marked stale (in memory), never deleted, so a refetch that fails still shows
     * the last good Home. Cheap and non-blocking (safe from a click handler); debouncing is the
     * caller's job ([ForcedReloadGate]).
     */
    @Synchronized
    fun invalidate() {
        generation++
        failedPass = null
    }

    /** Back online: a partial pass remembered from while offline is retried at once instead of in up to 5 min. */
    fun forgetFailedPass() {
        failedPass = null
    }

    /** The last snapshot [store] persisted, or null when there's none (or no store). */
    suspend fun cached(): CachedRows? = store?.read()

    /**
     * A fresh pass over the portal. Persists the rows it produced, but ONLY when every root
     * answered ([MagisHome.missing] empty): a partial pass is transient -- the flow refetches the
     * missing roots -- and must not overwrite a complete snapshot with a degraded one.
     */
    suspend fun load(): MagisHome = passLock.withLock { loadLocked() }

    private suspend fun loadLocked(): MagisHome = coroutineScope {
        val gen = generation
        val roots = MagisKind.entries.map { kind ->
            async {
                val force = (forcedGeneration[kind] ?: 0) < gen
                val sections = runCatching { if (force) freshTree(kind.root) else tree(kind.root) }.getOrDefault(emptyList())
                if (force && sections.isNotEmpty()) forcedGeneration[kind] = gen
                kind to sections
            }
        }.awaitAll()
        MagisHome(
            rows = MagisHomeClassifier.classify(roots.associate { (kind, sections) -> kind.root to sections }),
            missing = roots.filter { (_, sections) -> sections.isEmpty() }.mapTo(mutableSetOf()) { it.first },
        ).also { home ->
            if (home.missing.isEmpty()) {
                store?.write(home.rows, now())
                completedGeneration = maxOf(completedGeneration, gen)
                failedPass = null
            } else if (home.missing.size == MagisKind.entries.size) {
                // Every VOD root came back empty (this only runs post-activation): the "home cargó
                // pero no pintó nada" symptom -- a dead session, a geo-block, or a portal change.
                com.arkiv.player.crash.Crash.report(
                    com.arkiv.player.crash.EmptyCatalog("all ${MagisKind.entries.size} VOD roots empty"),
                    "empty-catalog",
                )
            }
        }
    }

    /**
     * Cache-first rows for the Categorías screen and the Xuper plugin's `home`/`browse` (see
     * `MagisPluginBridge`): the persisted snapshot while it's within [ttlMs] and not [invalidate]d,
     * otherwise a fresh pass. A partial pass is reused for [HomeFreshness.PARTIAL_PASS_TTL_MS]
     * before its missing roots are retried; a pass that got nothing at all serves the last good
     * snapshot, whatever its age.
     */
    suspend fun rows(): List<MagisHomeRow> = passLock.withLock {
        val gen = generation
        failedPass?.let { if (it.generation == gen && now() - it.at in 0 until HomeFreshness.PARTIAL_PASS_TTL_MS) return@withLock it.rows }
        val cached = cached()
        if (cached != null && completedGeneration >= gen && now() - cached.fetchedAt < ttlMs) return@withLock cached.rows
        val home = loadLocked()
        if (home.missing.isEmpty()) return@withLock home.rows
        val served = home.rows.ifEmpty { cached?.rows.orEmpty() }
        failedPass = FailedPass(now(), gen, served)
        served
    }

    companion object {
        /**
         * 2 h (it was 6): the portal adds about 3 movies a day and "Recién agregadas" is what people come to see, so a
         * Home older than a couple of hours is missing something. Matches the tree cache of `MagisLiveCatalog`.
         */
        const val TTL_MS = 2 * 60 * 60 * 1000L
    }
}
