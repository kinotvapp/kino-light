package com.arkiv.player.ui.search

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.arkiv.player.data.SearchHistoryRepo
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.db.RecentTitleDao
import com.arkiv.player.data.db.RecentTitleEntity
import com.arkiv.player.data.db.SearchHistoryDao
import com.arkiv.player.data.db.SearchHistoryEntity
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SearchEvent
import com.arkiv.player.ui.catalog.PlaySource
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * That the search ViewModel EXPOSES each source's error, in addition to passing along the results
 * of the ones that did respond. This is what the phone's and the TV's results read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelSourcesTest {

    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun after() {
        Dispatchers.resetMain()
    }

    private fun vm(source: ContentSource) = SearchViewModel(
        tmdbApi = TmdbApi(apiKey = "x"),
        aniListApi = AniListApi(),
        settings = SettingsStore(TestContext()),
        arkivApiClient = source,
        searchHistory = SearchHistoryRepo(EmptyHistory(), EmptyRecents()),
    )

    @Test fun `if caracol goes down, magis shows and caracol's failure stays exposed`() = runTest {
        val noNetwork = java.net.UnknownHostException("sin red")
        val vm = vm(TestSource {
            listOf(
                SearchEvent.SourceStart("magis"),
                SearchEvent.ResultEvent("magis", GatewayResult(source = "magis", title = "Rigo", ref = "m1")),
                SearchEvent.SourceDone("magis", 1, 5),
                SearchEvent.SourceStart("ditu"),
                SearchEvent.SourceError("ditu", "sin red", 5, 0, cause = noNetwork),
                SearchEvent.Done(10),
            )
        })

        vm.searchSourcesByText("rigo")
        advanceUntilIdle()

        assertEquals(listOf("Rigo"), vm.sources.value.map { (it as PlaySource.Magis).result.title })
        assertEquals(mapOf("ditu" to "sin red"), vm.sourcesState.value.failed)
        // The exception reaches the screen: it's what `CaracolFailure` writes the line with.
        assertEquals(mapOf<String, Throwable>("ditu" to noNetwork), vm.sourcesState.value.causes)
        assertEquals(setOf("magis"), vm.sourcesState.value.responded)
        assertFalse(vm.searchingSources.value.any)
    }

    /** "Buscando en Magis…" turns off with Magis's SourceDone, not with the whole search's Done. */
    @Test fun `magis stops spinning as soon as it responds, even if caracol keeps searching`() = runTest {
        val caracolAnswers = CompletableDeferred<Unit>()
        val vm = vm(FlowSource {
            flow {
                emit(SearchEvent.SourceStart("magis"))
                emit(SearchEvent.ResultEvent("magis", GatewayResult(source = "magis", title = "Rigo", ref = "m1")))
                emit(SearchEvent.SourceDone("magis", 1, 5))
                emit(SearchEvent.SourceStart("ditu"))
                caracolAnswers.await()
                emit(SearchEvent.SourceDone("ditu", 0, 5))
                emit(SearchEvent.Done(10))
            }
        })

        vm.searchSourcesByText("rigo")
        advanceUntilIdle()

        assertFalse(vm.searchingSources.value.isSearching(SourceTab.MAGIS))
        assertTrue(vm.searchingSources.value.isSearching(SourceTab.CARACOL))
        assertTrue(vm.searchingSources.value.isSearching(SourceTab.ALL))

        caracolAnswers.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.searchingSources.value.any)
    }

    @Test fun `a new search starts without the previous one's errors`() = runTest {
        var fails = true
        val vm = vm(TestSource {
            if (fails) {
                listOf(SearchEvent.SourceStart("ditu"), SearchEvent.SourceError("ditu", "sin red", 0, 0), SearchEvent.Done(0))
            } else {
                listOf(SearchEvent.SourceStart("ditu"), SearchEvent.SourceDone("ditu", 0, 0), SearchEvent.Done(0))
            }
        })

        vm.searchSourcesByText("rigo")
        advanceUntilIdle()
        assertEquals(setOf("ditu"), vm.sourcesState.value.failed.keys)

        fails = false
        vm.searchSourcesByText("rigo")
        advanceUntilIdle()
        assertTrue(vm.sourcesState.value.failed.isEmpty())
        assertEquals(setOf("ditu"), vm.sourcesState.value.responded)
    }
}

/** A source whose flow the test builds, to be able to leave a source halfway through. */
private class FlowSource(private val flow: () -> Flow<SearchEvent>) : ContentSource {
    override fun recognizes(ref: String) = false
    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow()
    override suspend fun resolve(ref: String): GatewayPlayable = throw GatewayException("sin uso en el test")
    override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> =
        emptyList<GatewayEpisode>() to null
}

/** A source that returns, on every search, whatever events the test says. */
private class TestSource(private val events: () -> List<SearchEvent>) : ContentSource {
    override fun recognizes(ref: String) = false
    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = events().asFlow()
    override suspend fun resolve(ref: String): GatewayPlayable = throw GatewayException("sin uso en el test")
    override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> =
        emptyList<GatewayEpisode>() to null
}

/**
 * `SettingsStore` reads `SharedPreferences` on construction, and `SearchViewModel` doesn't use it
 * for anything (it has no `settings.` at all): preferences that return the default value are enough.
 */
private class TestContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> false
                // getInt/getBoolean/getString(key, default): the second argument.
                else -> args?.getOrNull(1)
            }
        } as SharedPreferences
}

private class EmptyHistory : SearchHistoryDao {
    override suspend fun upsert(entry: SearchHistoryEntity) = Unit
    override suspend fun recent(kind: String, limit: Int): List<SearchHistoryEntity> = emptyList()
    override fun observeRecent(kind: String, limit: Int): Flow<List<SearchHistoryEntity>> = flowOf(emptyList())
    override suspend fun deleteOne(kind: String, query: String) = Unit
    override suspend fun clearKind(kind: String) = Unit
    override suspend fun clear() = Unit
}

private class EmptyRecents : RecentTitleDao {
    override suspend fun upsert(entry: RecentTitleEntity) = Unit
    override fun observeRecent(limit: Int): Flow<List<RecentTitleEntity>> = flowOf(emptyList())
    override suspend fun deleteOne(id: String) = Unit
    override suspend fun clear() = Unit
    override suspend fun trim(keep: Int) = Unit
}
