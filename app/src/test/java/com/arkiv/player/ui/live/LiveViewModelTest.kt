package com.arkiv.player.ui.live

import com.arkiv.player.data.db.LiveChannelCacheDao
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveCategory
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveViewModelTest {
    private val channels = listOf(
        LiveChannel("c1", "ESPN", 501, null),
        LiveChannel("c2", "TNT Sports", 502, null),
        LiveChannel("c3", "Caracol", 101, null),
    )

    @Test
    fun `searches by name regardless of case or accents`() {
        assertEquals(listOf("c3"), filterChannels(channels, "caracol").map { it.code })
    }

    @Test
    fun `searches by channel number`() {
        assertEquals(listOf("c2"), filterChannels(channels, "502").map { it.code })
    }

    @Test
    fun `with no text returns everything in the order it came in`() {
        assertEquals(channels, filterChannels(channels, "  "))
    }

    // --- programProgress: thin progress bar for the current program (Step 5 of the brief) ---

    @Test
    fun `progress is zero right when the program starts`() {
        val p = LiveProgram("Noticias", start = 1000L, end = 2000L, synopsis = "")
        assertEquals(0f, programProgress(p, nowSeconds = 1000L))
    }

    @Test
    fun `progress is half at the halfway point`() {
        val p = LiveProgram("Noticias", start = 1000L, end = 2000L, synopsis = "")
        assertEquals(0.5f, programProgress(p, nowSeconds = 1500L))
    }

    @Test
    fun `progress doesn't go past one even if the program already ended`() {
        val p = LiveProgram("Noticias", start = 1000L, end = 2000L, synopsis = "")
        assertEquals(1f, programProgress(p, nowSeconds = 5000L))
    }

    @Test
    fun `progress doesn't go below zero with inconsistent duration data`() {
        // end <= start shouldn't happen in practice, but if the portal sends something odd,
        // the bar must not break or show a negative number or NaN.
        val p = LiveProgram("Raro", start = 2000L, end = 2000L, synopsis = "")
        assertEquals(0f, programProgress(p, nowSeconds = 2000L))
    }
}

// --- Test doubles -------------------------------------------------------------------------
//
// The project has no Mockito or mockk (see app/build.gradle.kts). LiveViewModel depends on
// LiveCatalogGateway -the narrow interface defined next to LiveApi in data/gateway/LiveApi.kt,
// with only the three operations this ViewModel consumes- and not on LiveApi directly, so this
// double implements it directly, without inheriting from any production class or touching the
// network. (An earlier version opened LiveApi with `open class`/`open fun` so it could be
// inherited in the test; that was dropped in review for being the only open class in the whole
// module with no architectural reason -see LiveCatalogGateway's KDoc for the full reasoning.)
// LiveFavoriteDao/LiveChannelCacheDao were already interfaces and get implemented the same way,
// with in-memory storage.
private class FakeLiveApi : LiveCatalogGateway {
    var categoriesResult: List<LiveCategory> = emptyList()
    val channelsByCategory = mutableMapOf<Int, List<LiveChannel>>()

    /** Category -> gate that holds channels(category) until the test completes it by hand. */
    val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
    val channelsCalls = mutableListOf<Int>()
    val epgCalls = mutableListOf<List<String>>()

    /**
     * By default returns nothing and is missing nothing -the behavior the existing tests already
     * used-. The tests for the bounded EPG retry (finding F3) replace it to simulate that the
     * gateway doesn't have the programming for some codes yet (`missing`).
     */
    var epgResponder: (List<String>) -> Pair<Map<String, List<LiveProgram>>, List<String>> =
        { emptyMap<String, List<LiveProgram>>() to emptyList() }

    override suspend fun categories(includeAdults: Boolean): List<LiveCategory> = categoriesResult

    override suspend fun channels(category: Int, force: Boolean): List<LiveChannel> {
        channelsCalls.add(category)
        gates[category]?.await()
        return channelsByCategory[category].orEmpty()
    }

    override suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>> {
        epgCalls.add(codes)
        return epgResponder(codes)
    }
}

private class FakeFavoriteDao : LiveFavoriteDao {
    private val flow = MutableStateFlow<List<LiveFavoriteEntity>>(emptyList())
    override fun flowAll(): Flow<List<LiveFavoriteEntity>> = flow
    override suspend fun save(f: LiveFavoriteEntity) {
        flow.value = flow.value.filterNot { it.code == f.code } + f
    }
    override suspend fun delete(code: String) {
        flow.value = flow.value.filterNot { it.code == code }
    }
    override suspend fun isFavorite(code: String): Boolean = flow.value.any { it.code == code }
    override suspend fun getAll(): List<LiveFavoriteEntity> = flow.value
    // Task 2 (companion sync push side) added this. No test in this file exercises it; minimal
    // implementation to satisfy the interface.
    override suspend fun getLiveFavoritesSince(cursor: Long): List<LiveFavoriteEntity> =
        flow.value.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    // Task 3 (companion sync apply side) added this. No test in this file exercises it; minimal
    // implementation to satisfy the interface.
    override suspend fun get(code: String): LiveFavoriteEntity? = flow.value.firstOrNull { it.code == code }
}

private class FakeCacheDao : LiveChannelCacheDao {
    private val store = mutableMapOf<Int, List<LiveChannelCacheEntity>>()

    /** Direct test setup, without going through save()/replace(). */
    fun preload(category: Int, rows: List<LiveChannelCacheEntity>) {
        store[category] = rows
    }

    override suspend fun byCategory(category: Int): List<LiveChannelCacheEntity> = store[category].orEmpty()
    // No test in this file exercises this (they're all about chooseCategory/byCategory);
    // minimal implementation to satisfy the interface.
    override suspend fun byCodes(codes: List<String>): List<LiveChannelCacheEntity> =
        store.values.flatten().filter { it.code in codes }
    override suspend fun clear(category: Int) { store.remove(category) }
    override suspend fun save(rows: List<LiveChannelCacheEntity>) {
        rows.groupBy { it.categoria }.forEach { (cat, rows) -> store[cat] = rows }
    }
    // replace() uses the interface's default body (clear + save), not needed here.
}

// --- The ViewModel's dynamic behavior ----------------------------------------------------
//
// The tests above only covered pure functions (filterChannels/programProgress); these exercise
// LiveViewModel for real, with coroutines controlled by hand (StandardTestDispatcher) to be able
// to force the order responses arrive in. `viewModelScope` uses Dispatchers.Main.immediate,
// which doesn't exist in a plain JVM test without this test dispatcher -- hence
// kotlinx-coroutines-test as a new testImplementation dependency (see the comment in
// build.gradle.kts).
@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelAsyncTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `an old response doesn't overwrite the newer active category`() = runTest(dispatcher) {
        val api = FakeLiveApi()
        api.channelsByCategory[1] = listOf(LiveChannel("a1", "Canal A", 1, null))
        api.channelsByCategory[2] = listOf(LiveChannel("b1", "Canal B", 2, null))
        // Category 1 (A) is left waiting on this gate INSIDE channels(1) -- simulates the gateway
        // taking a while to respond to the first category the user tapped.
        val gateA = CompletableDeferred<Unit>()
        api.gates[1] = gateA

        val vm = LiveViewModel(api, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle() // finishes the init's initial load (CATEGORY_ALL), unrelated to this

        vm.chooseCategory(1) // A: gets stuck on the gate
        advanceUntilIdle()
        vm.chooseCategory(2) // B: requested AFTER, no gate -> resolves right away and wins the screen
        advanceUntilIdle()

        assertEquals(2, vm.state.value.activeCategory)
        assertEquals(listOf("b1"), vm.state.value.channels.map { it.code })

        gateA.complete(Unit) // A arrives LATE, after the user is already looking at B
        advanceUntilIdle()

        // A's stale response must not overwrite what the user has on screen (B): neither the
        // active chip nor the listed channels. Before the fix this failed with
        // activeCategory=2 but channels=["a1"] -- the exact bug the review described.
        assertEquals(2, vm.state.value.activeCategory)
        assertEquals(listOf("b1"), vm.state.value.channels.map { it.code })
    }

    @Test
    fun `doesn't duplicate the EPG request between the painted cache and the fresh response`() = runTest(dispatcher) {
        val api = FakeLiveApi()
        val category = 5
        api.channelsByCategory[category] = listOf(LiveChannel("c1", "Canal 1", 1, null))
        val cacheDao = FakeCacheDao().apply {
            // "c1" is already in the cache AND in the fresh response -- exactly the case that
            // duplicated the EPG request before the fix (same channel, two different load steps).
            preload(category, listOf(LiveChannelCacheEntity("c1", category, "Canal 1", 1, null, 0L)))
        }

        val vm = LiveViewModel(api, FakeFavoriteDao(), cacheDao)
        advanceUntilIdle() // init: CATEGORY_ALL, no cache or channels -> doesn't request EPG, doesn't pollute the count

        vm.chooseCategory(category)
        advanceUntilIdle()

        assertEquals(1, api.epgCalls.size)
        assertEquals(listOf("c1"), api.epgCalls.single())
    }

    // --- Finding F3 from the final review: with no background sweep on the server, the FIRST
    // EPG query for any channel almost always comes back with that channel in `missing` -the
    // gateway's worker only fills its cache at 1.5s per channel-. With no retry, the guide would
    // stay on "Cargando programación…" until the user scrolled the row off screen and back. ---

    @Test
    fun `the EPG that came back in missing is retried once after 10s`() = runTest(dispatcher) {
        val api = FakeLiveApi()
        val category = 7
        api.channelsByCategory[category] = listOf(LiveChannel("c1", "Canal 1", 1, null))
        var call = 0
        api.epgResponder = { codes ->
            call++
            if (call == 1) emptyMap<String, List<LiveProgram>>() to codes
            else mapOf(codes.first() to listOf(LiveProgram("Partido", 0, 10, ""))) to emptyList()
        }

        val vm = LiveViewModel(api, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.chooseCategory(category)
        // runCurrent(), not advanceUntilIdle(): the latter does NOT stop at the retry's 10s
        // delay() -it advances the virtual clock until draining EVERYTHING scheduled, sleeping
        // coroutines included-, so it would already have fired the retry before this check.
        runCurrent()

        // First round: the gateway doesn't have it yet. Without the retry, this stays like this.
        assertEquals(1, api.epgCalls.size)
        assertTrue(vm.state.value.programming["c1"].isNullOrEmpty())

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals("the retry bounded to ~10s", 2, api.epgCalls.size)
        assertEquals(listOf("Partido"), vm.state.value.programming["c1"]?.map { it.title })
    }

    @Test
    fun `if the retry also comes back missing a third request isn't chained`() = runTest(dispatcher) {
        val api = FakeLiveApi()
        val category = 8
        api.channelsByCategory[category] = listOf(LiveChannel("c1", "Canal 1", 1, null))
        api.epgResponder = { codes -> emptyMap<String, List<LiveProgram>>() to codes }  // never has it

        val vm = LiveViewModel(api, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.chooseCategory(category)
        runCurrent()
        assertEquals(1, api.epgCalls.size)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("the ONE bounded retry", 2, api.epgCalls.size)

        advanceTimeBy(60_000)
        advanceUntilIdle()  // no delay left scheduled (retry=false): fully draining is safe
        assertEquals(
            "must not chain a third request if the gateway never has it -infinite loop",
            2,
            api.epgCalls.size,
        )
    }

    @Test
    fun `the retry doesn't fight the duplicate-request guard`() = runTest(dispatcher) {
        val api = FakeLiveApi()
        val category = 9
        api.channelsByCategory[category] = listOf(LiveChannel("c1", "Canal 1", 1, null))
        api.epgResponder = { codes -> emptyMap<String, List<LiveProgram>>() to codes }

        val vm = LiveViewModel(api, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.chooseCategory(category)
        runCurrent()
        assertEquals(1, api.epgCalls.size)

        // Before the scheduled retry fires, another request (e.g. the user scrolled the row off
        // screen and back) ALREADY requests "c1" again -and this time the gateway does have it-.
        api.epgResponder = { codes -> mapOf("c1" to listOf(LiveProgram("Ya llego", 0, 10, ""))) to emptyList() }
        vm.requestEpg(listOf("c1"))
        runCurrent()
        assertEquals(2, api.epgCalls.size)
        assertEquals(listOf("Ya llego"), vm.state.value.programming["c1"]?.map { it.title })

        // The retry scheduled by the original load fires anyway, but since "c1" is already in
        // `programming`, requestEpg() discards it on its own -without fighting epgInFlight or
        // requesting again something that already arrived another way.
        advanceTimeBy(10_000)
        advanceUntilIdle()
        assertEquals("must not request again what already arrived another way", 2, api.epgCalls.size)
    }
}
