package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeSourceTest {

    private class FakeSource(
        val name: String,
        val prefix: String,
        val results: List<String> = emptyList(),
        val error: String? = null,
    ) : ContentSource {
        var resolved: String? = null

        override fun recognizes(ref: String) = ref.startsWith(prefix)

        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
            emit(SearchEvent.SourceStart(name))
            if (error != null) {
                emit(SearchEvent.SourceError(name, error, 1, 0))
            } else {
                results.forEach {
                    emit(SearchEvent.ResultEvent(name, GatewayResult(name, it, "$prefix$it")))
                }
                emit(SearchEvent.SourceDone(name, results.size, 1))
            }
            emit(SearchEvent.Done(1))
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

    @Test fun `both sources' results arrive`() = runTest {
        val a = FakeSource("a", "a:", listOf("uno", "dos"))
        val b = FakeSource("b", "b:", listOf("tres"))

        val events = CompositeSource(listOf(a, b)).search(GatewaySearchQuery(q = "x")).toList()

        val titles = events.filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }
        assertEquals(setOf("uno", "dos", "tres"), titles.toSet())
    }

    /** A SINGLE Done, and last: if each source emitted its own, the screen would think the search
     *  ended the moment the first one finished. */
    @Test fun `there's a single Done and it's the last event`() = runTest {
        val a = FakeSource("a", "a:", listOf("uno"))
        val b = FakeSource("b", "b:", listOf("dos"))

        val events = CompositeSource(listOf(a, b)).search(GatewaySearchQuery(q = "x")).toList()

        assertEquals(1, events.count { it is SearchEvent.Done })
        assertTrue(events.last() is SearchEvent.Done)
    }

    /** THE RULE THAT MATTERS: a down source can't empty out the other's search. */
    @Test fun `if one source fails the other still delivers`() = runTest {
        val broken = FakeSource("broken", "r:", error = "went down")
        val healthy = FakeSource("healthy", "s:", listOf("uno", "dos"))

        val events = CompositeSource(listOf(broken, healthy)).search(GatewaySearchQuery(q = "x")).toList()

        assertEquals(2, events.filterIsInstance<SearchEvent.ResultEvent>().size)
        val err = events.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("broken", err.source)
        assertTrue(events.last() is SearchEvent.Done)
    }

    @Test fun `resolve goes to the source that recognizes the ref`() = runTest {
        val a = FakeSource("a", "a:")
        val b = FakeSource("b", "b:")

        val play = CompositeSource(listOf(a, b)).resolve("b:42")

        assertEquals("b", play.kind)
        assertEquals("b:42", b.resolved)
        assertEquals(null, a.resolved)
    }

    @Test fun `episodes goes to the source that recognizes the ref`() = runTest {
        val a = FakeSource("a", "a:")
        val b = FakeSource("b", "b:")

        CompositeSource(listOf(a, b)).episodesWithSeries("a:9")

        assertEquals("a:9", a.resolved)
        assertEquals(null, b.resolved)
    }

    @Test fun `a ref nobody recognizes is a GatewayException`() = runTest {
        val composite = CompositeSource(listOf(FakeSource("a", "a:")))

        val e = runCatching { composite.resolve("z:1") }.exceptionOrNull()
        assertTrue(e is GatewayException)
    }

    @Test fun `recognizes if any of its sources recognizes`() {
        val composite = CompositeSource(listOf(FakeSource("a", "a:"), FakeSource("b", "b:")))

        assertTrue(composite.recognizes("b:1"))
        assertTrue(!composite.recognizes("z:1"))
    }

    private class ThrowingSource(
        val name: String,
        val prefix: String,
    ) : ContentSource {
        override fun recognizes(ref: String) = ref.startsWith(prefix)

        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
            emit(SearchEvent.SourceStart(name))
            throw IllegalStateException("Deliberate explosion")
        }

        override suspend fun resolve(ref: String): GatewayPlayable {
            return GatewayPlayable(kind = name, url = "http://$name")
        }

        override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> {
            return listOf(GatewayEpisode(1, "Cap", ref)) to null
        }
    }

    /** THE RULE THAT MATTERS MOST: a source that throws can NOT empty out the others' search. */
    @Test fun `if one source throws the other still delivers`() = runTest {
        val throwing = ThrowingSource("thrower", "l:")
        val healthy = FakeSource("healthy", "s:", listOf("uno", "dos"))

        val events = CompositeSource(listOf(throwing, healthy)).search(GatewaySearchQuery(q = "x")).toList()

        assertEquals(2, events.filterIsInstance<SearchEvent.ResultEvent>().size)
        val err = events.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("thrower", err.source)
        assertEquals(0, err.count)
        assertTrue(events.last() is SearchEvent.Done)
    }

    /** Source isolation: if the collector (the screen) throws on receiving an event, that
     *  exception reaches the caller as-is, without turning into a `SourceError`. `merge()`
     *  isolates each source in its own coroutine, so a collector's exception never goes back
     *  inside any source. This test verifies that property. */
    @Test fun `a collector's exception arrives without turning into a SourceError`() = runTest {
        val healthy = FakeSource("healthy", "s:", listOf("uno"))
        val composite = CompositeSource(listOf(healthy))

        val events = mutableListOf<SearchEvent>()
        val e = runCatching {
            composite.search(GatewaySearchQuery(q = "x")).collect { event ->
                events.add(event)
                if (event is SearchEvent.ResultEvent) {
                    throw IllegalArgumentException("Collector error")
                }
            }
        }.exceptionOrNull()

        // `merge()` isolates each source, so the collector's exception doesn't enter any source
        // and isn't converted to a SourceError. The exception arrives as-is.
        val hasSourceError = events.any { it is SearchEvent.SourceError }
        assertTrue("The collector's exception was wrongly converted to SourceError", !hasSourceError)
        assertTrue("Expected IllegalArgumentException, got ${e?.javaClass?.simpleName}", e is IllegalArgumentException)
    }
}
