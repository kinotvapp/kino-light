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
class MagisHomeCatalog(private val tree: suspend (root: String) -> List<CatalogSection>) {

    suspend fun load(): MagisHome = coroutineScope {
        val roots = MagisKind.entries.map { kind ->
            async { kind to runCatching { tree(kind.root) }.getOrDefault(emptyList()) }
        }.awaitAll()
        MagisHome(
            rows = MagisHomeClassifier.classify(roots.associate { (kind, sections) -> kind.root to sections }),
            missing = roots.filter { (_, sections) -> sections.isEmpty() }.mapTo(mutableSetOf()) { it.first },
        )
    }

    suspend fun rows(): List<MagisHomeRow> = load().rows
}
