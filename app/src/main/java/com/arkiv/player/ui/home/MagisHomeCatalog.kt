package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogSection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One pass over the VOD roots: the classified [rows], and the roots that contributed nothing to
 * them -- they failed, or came back with no sections, which `tree` also answers when the portal
 * errors. Either way it's worth asking again later (see [shouldRefetch]).
 */
data class MagisHome(val rows: List<MagisHomeRow>, val missing: Set<MagisKind>)

/**
 * The home's rows, straight from the Magis catalog: the four VOD roots requested in parallel and
 * classified on the device ([MagisHomeClassifier]). The adults root is never asked for.
 *
 * [tree] is `MagisLiveCatalog.tree` in the app (6 h in-memory cache, so the home and "Ver todo"
 * share one fetch); a lambda here so the JVM tests don't need the portal. A root that fails (portal
 * error, timeout) just contributes nothing to [load] -- it doesn't take the other three down with
 * it. Failures aren't cached, so another [load] asks again only for the roots that are missing.
 */
class MagisHomeCatalog(
    private val tree: suspend (root: String) -> List<CatalogSection>,
    /** The persistent cache. Null in the JVM tests, which exercise the classification with no Room. */
    private val store: HomeCatalogStore? = null,
    /** A cold snapshot older than this is refreshed instead of served (see [rows]/[magisHomeRows]). */
    private val ttlMs: Long = TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** The last snapshot [store] persisted, or null when there's none (or no store). */
    suspend fun cached(): CachedRows? = store?.read()

    /**
     * A fresh pass over the portal. Persists the rows it produced, but ONLY when every root
     * answered ([MagisHome.missing] empty): a partial pass is transient -- the flow refetches the
     * missing roots -- and must not overwrite a complete snapshot with a degraded one.
     */
    suspend fun load(): MagisHome = coroutineScope {
        val roots = MagisKind.entries.map { kind ->
            async { kind to runCatching { tree(kind.root) }.getOrDefault(emptyList()) }
        }.awaitAll()
        MagisHome(
            rows = MagisHomeClassifier.classify(roots.associate { (kind, sections) -> kind.root to sections }),
            missing = roots.filter { (_, sections) -> sections.isEmpty() }.mapTo(mutableSetOf()) { it.first },
        ).also { home ->
            if (home.missing.isEmpty()) {
                store?.write(home.rows, now())
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
     * Cache-first rows for the Categorías screen: the persisted snapshot while it's within [ttlMs],
     * otherwise a fresh [load]. (The home uses [magisHomeRows] instead, which paints the snapshot
     * AND then refreshes it.)
     */
    suspend fun rows(): List<MagisHomeRow> {
        cached()?.let { if (now() - it.fetchedAt < ttlMs) return it.rows }
        return load().rows
    }

    companion object {
        /** 6 h, the interval the user chose and the same one MagisLiveCatalog's in-memory cache uses. */
        const val TTL_MS = 6 * 60 * 60 * 1000L
    }
}
