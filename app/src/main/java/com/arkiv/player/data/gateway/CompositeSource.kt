package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Several sources behind a single one. Exists so that adding Caracol -- and later RCN -- doesn't
 * force touching any screen: `AppGraph.contentSource` stays as ONE object.
 *
 * Two rules govern the search:
 *
 * - **A down source doesn't empty out the others' search.** Each source is protected separately
 *   with Flow's `.catch`: if it throws, a `SourceError` is emitted instead of propagating. The
 *   `.catch` doesn't trap `CancellationException`: if the coroutine is cancelled, the exception
 *   propagates. That's what sets it apart from a plain `try/catch`, which WOULD trap it.
 * - **There's a single `Done`, at the end.** Each source's own `Done` gets discarded and one of
 *   its own is emitted once all of them finish: if the inner ones went through, the screen would
 *   think the search ended the moment the first source finished.
 *
 * For resolving and for listing chapters there's no mixing: the `ref` decides. Each source knows
 * how to read its own (`recognizes`), including the gateway's old ones, which carry no visible
 * prefix.
 */
internal class CompositeSource(private val sources: List<ContentSource>) : ContentSource {

    override fun recognizes(ref: String): Boolean = sources.any { it.recognizes(ref) }

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
        val t0 = System.currentTimeMillis()
        // `merge` runs the sources in parallel and emits each one's as it arrives, which is what
        // the screen expects: it paints results while the other source keeps searching. Each
        // source is protected with Flow's `.catch`: if it throws, a SourceError is emitted. The
        // `.catch` doesn't trap CancellationException (if the coroutine is cancelled, the
        // exception propagates). A plain `try/catch` WOULD trap it, only the emit afterward would
        // fail silently.
        val merged = sources
            .map { source ->
                flow {
                    var sourceName = "desconocida"
                    var resultCount = 0
                    val sourceT0 = System.currentTimeMillis()
                    emitAll(
                        source.search(ctx)
                            .filterNot { it is SearchEvent.Done }
                            .onEach { event ->
                                if (event is SearchEvent.SourceStart) {
                                    sourceName = event.source
                                }
                                if (event is SearchEvent.ResultEvent) {
                                    resultCount++
                                }
                            }
                            .catch { e ->
                                emit(SearchEvent.SourceError(sourceName, e.message ?: "Error desconocido",
                                    System.currentTimeMillis() - sourceT0, resultCount))
                            }
                    )
                }
            }
            .merge()
        emitAll(merged)
        emit(SearchEvent.Done(System.currentTimeMillis() - t0))
    }

    override suspend fun resolve(ref: String): GatewayPlayable = sourceFor(ref).resolve(ref)

    override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> =
        sourceFor(ref).episodesWithSeries(ref)

    private fun sourceFor(ref: String): ContentSource =
        sources.firstOrNull { it.recognizes(ref) }
            ?: throw GatewayException("No hay ninguna fuente que sepa abrir esto")
}
