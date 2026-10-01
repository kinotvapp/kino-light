package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hidden Caracol (`CaracolVisibility`): the composite never searches it -- no request, no
 * `SourceStart`, no section -- but a saved `ditu…` ref still resolves through it.
 */
class ResolveOnlySourceTest {

    private class CountingSource(val name: String, val prefix: String) : ContentSource {
        var searches = 0
        var resolved: String? = null

        override fun recognizes(ref: String) = ref.startsWith(prefix)

        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
            searches++
            emit(SearchEvent.SourceStart(name))
            emit(SearchEvent.ResultEvent(name, GatewayResult(name, "t", "${prefix}t")))
            emit(SearchEvent.SourceDone(name, 1, 1))
        }

        override suspend fun resolve(ref: String): GatewayPlayable {
            resolved = ref
            return GatewayPlayable(kind = name, url = "http://$name")
        }

        override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> {
            resolved = ref
            return listOf(GatewayEpisode(1, "Cap", ref)) to null
        }
    }

    @Test fun `the search never reaches a resolve-only source`() = runTest {
        val ditu = CountingSource("ditu", "ditu1:")
        val plugin = CountingSource("plugin:demo", "plg1:")

        val events = CompositeSource(listOf(ResolveOnlySource(ditu), plugin)).search(GatewaySearchQuery(q = "x")).toList()

        assertEquals(0, ditu.searches)
        assertTrue(events.none { it is SearchEvent.SourceStart && it.source == "ditu" })
        assertEquals(listOf("plugin:demo"), events.filterIsInstance<SearchEvent.ResultEvent>().map { it.item.source })
        assertTrue(events.last() is SearchEvent.Done)
    }

    @Test fun `a saved ditu ref still resolves and lists its chapters`() = runTest {
        val ditu = CountingSource("ditu", "ditu1:")
        val composite = CompositeSource(listOf(ResolveOnlySource(ditu), CountingSource("plugin:demo", "plg1:")))

        assertTrue(composite.recognizes("ditu1:VOD:123"))
        assertEquals("ditu", composite.resolve("ditu1:VOD:123").kind)
        assertEquals("ditu1:VOD:123", ditu.resolved)
        assertEquals(1, composite.episodes("ditu1:BUNDLE:9").size)
        assertEquals("ditu1:BUNDLE:9", ditu.resolved)
    }
}
