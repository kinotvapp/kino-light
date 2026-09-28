package com.arkiv.player.ui.live

import com.arkiv.player.data.db.LiveChannelCacheDao
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveCategory
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.data.live.LiveChannelProvider
import com.arkiv.player.data.live.LiveModule
import com.arkiv.player.data.live.LiveOpening
import com.arkiv.player.data.live.ProviderCategory
import com.arkiv.player.data.live.XuperLiveProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
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
// The project has no Mockito or mockk (see app/build.gradle.kts). LiveViewModel reads a
// LiveModule; the Xuper tests wrap this gateway double in the real XuperLiveProvider
// (xuperModule), so every Xuper behaviour they pin goes through the production provider. The
// multi-provider tests use FakeModule/FakeProvider below. LiveFavoriteDao/LiveChannelCacheDao are
// interfaces implemented with in-memory storage.
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
        flow.value = flow.value.filterNot { it.provider == f.provider && it.code == f.code } + f
    }
    override suspend fun delete(provider: String, code: String) {
        flow.value = flow.value.filterNot { it.provider == provider && it.code == code }
    }
    override suspend fun isFavorite(provider: String, code: String): Boolean =
        flow.value.any { it.provider == provider && it.code == code }
    override suspend fun getAll(): List<LiveFavoriteEntity> = flow.value
    // Task 2 (companion sync push side) added this. No test in this file exercises it; minimal
    // implementation to satisfy the interface.
    override suspend fun getLiveFavoritesSince(cursor: Long): List<LiveFavoriteEntity> =
        flow.value.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    // Task 3 (companion sync apply side) added this. No test in this file exercises it; minimal
    // implementation to satisfy the interface.
    override suspend fun get(provider: String, code: String): LiveFavoriteEntity? =
        flow.value.firstOrNull { it.provider == provider && it.code == code }
}

private class FakeCacheDao : LiveChannelCacheDao {
    /** Keyed by `provider to category`, like the real `(provider, code, categoria)` PK scopes a category. */
    private val store = mutableMapOf<Pair<String, String>, List<LiveChannelCacheEntity>>()

    /** Direct test setup (Xuper's portal category), without going through save()/replace(). */
    fun preload(category: Int, rows: List<LiveChannelCacheEntity>) {
        store[LiveChannelKeys.XUPER to category.toString()] = rows
    }

    override suspend fun byCategory(provider: String, category: String): List<LiveChannelCacheEntity> =
        store[provider to category].orEmpty()
    // No test in this file exercises this (they're all about chooseCategory/byCategory);
    // minimal implementation to satisfy the interface.
    override suspend fun byCodes(provider: String, codes: List<String>): List<LiveChannelCacheEntity> =
        store.filterKeys { it.first == provider }.values.flatten().filter { it.code in codes }
    override suspend fun clear(provider: String, category: String) { store.remove(provider to category) }
    override suspend fun save(rows: List<LiveChannelCacheEntity>) {
        rows.groupBy { it.provider to it.categoria }.forEach { (key, rows) -> store[key] = rows }
    }
    // replace() uses the interface's default body (clear + save), not needed here.
}

private class FakeModule(vararg initial: LiveChannelProvider) : LiveModule {
    override val providers = MutableStateFlow(initial.toList())
    override fun blockedMessage(providerId: String) = "no está"
}

private fun xuperModule(api: LiveCatalogGateway) = FakeModule(
    XuperLiveProvider(api, LiveController(resolver = { error("not used") }, urlFor = { "" })),
)

private class FakeProvider(
    override val id: String,
    override val name: String = id,
    private val initial: String? = null,
    var categoriesResult: List<ProviderCategory> = emptyList(),
    val channelsByCategory: MutableMap<String, List<LiveChannel>> = mutableMapOf(),
) : LiveChannelProvider {
    override val color = 0L
    val guideCalls = mutableListOf<List<String>>()
    override fun initialCategory() = initial
    var guide = true
    override fun hasGuide() = guide
    /** When set, [guide] answers every asked channel as "ask later" (a plugin past its per-pass cap). */
    var guideLater = false
    /** When set, [channels] waits on it: [close] fails it the way a closed PluginLiveProvider does. */
    var channelsGate: CompletableDeferred<Unit>? = null
    val noticeFlow = MutableStateFlow<String?>(null)
    override val notice: StateFlow<String?> = noticeFlow
    var closed = false
    override suspend fun categories(includeAdults: Boolean) = categoriesResult
    override suspend fun channels(categoryId: String, force: Boolean): List<LiveChannel> {
        channelsGate?.await()
        return channelsByCategory[categoryId].orEmpty()
    }
    override suspend fun guide(channels: List<LiveChannel>): Pair<Map<String, List<LiveProgram>>, List<String>> {
        guideCalls += channels.map { it.code }
        return emptyMap<String, List<LiveProgram>>() to (if (guideLater) channels.map { it.liveCode } else emptyList())
    }
    override suspend fun open(channel: LiveChannel): LiveOpening = LiveOpening.Proxied("x")
    override fun close() {
        closed = true
        channelsGate?.completeExceptionally(CancellationException("$id closed"))
    }
}

private fun ch(code: String, provider: String = "xuper", number: Int = 1) = LiveChannel(code, code.uppercase(), number, null, provider = provider)

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

        val vm = LiveViewModel(xuperModule(api), FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle() // finishes the init's initial load (Xuper's "76182"), unrelated to this

        vm.chooseCategory("1") // A: gets stuck on the gate
        advanceUntilIdle()
        vm.chooseCategory("2") // B: requested AFTER, no gate -> resolves right away and wins the screen
        advanceUntilIdle()

        assertEquals("2", vm.state.value.activeCategory)
        assertEquals(listOf("b1"), vm.state.value.channels.map { it.code })

        gateA.complete(Unit) // A arrives LATE, after the user is already looking at B
        advanceUntilIdle()

        // A's stale response must not overwrite what the user has on screen (B): neither the
        // active chip nor the listed channels. Before the fix this failed with
        // activeCategory=2 but channels=["a1"] -- the exact bug the review described.
        assertEquals("2", vm.state.value.activeCategory)
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
            preload(category, listOf(LiveChannelCacheEntity("c1", category.toString(), "Canal 1", 1, null, 0L)))
        }

        val vm = LiveViewModel(xuperModule(api), FakeFavoriteDao(), cacheDao)
        advanceUntilIdle() // init: Xuper's "76182", no cache or channels -> doesn't request EPG, doesn't pollute the count

        vm.chooseCategory(category.toString())
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

        val vm = LiveViewModel(xuperModule(api), FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.chooseCategory(category.toString())
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

        val vm = LiveViewModel(xuperModule(api), FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.chooseCategory(category.toString())
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

        val vm = LiveViewModel(xuperModule(api), FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.chooseCategory(category.toString())
        runCurrent()
        assertEquals(1, api.epgCalls.size)

        // Before the scheduled retry fires, another request (e.g. the user scrolled the row off
        // screen and back) ALREADY requests "c1" again -and this time the gateway does have it-.
        api.epgResponder = { codes -> mapOf("c1" to listOf(LiveProgram("Ya llego", 0, 10, ""))) to emptyList() }
        vm.requestEpg(listOf(LiveChannel("c1", "Canal 1", 1, null)))
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

    // --- Providers (generic live module) ---

    private val tvId = "plugin:tv"

    private fun twoProviders(): Pair<FakeProvider, FakeProvider> {
        val xuper = FakeProvider("xuper", "Xuper", initial = "76182", categoriesResult = listOf(ProviderCategory("76182", "Todos")))
        xuper.channelsByCategory["76182"] = listOf(ch("c1"))
        val tv = FakeProvider(tvId, "Tu servidor", categoriesResult = listOf(ProviderCategory("news", "Noticias")))
        tv.channelsByCategory["news"] = listOf(ch("c1", tvId))
        return xuper to tv
    }

    @Test
    fun `the first provider opens on its initial category and every provider gets a chip`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        val vm = LiveViewModel(FakeModule(xuper, tv), FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("xuper", tvId), s.providers.map { it.id })
        assertTrue(s.showProviders)
        assertEquals("xuper", s.activeProvider)
        assertEquals("76182", s.activeCategory)
        assertEquals(listOf("c1"), s.channels.map { it.liveCode })
    }

    @Test
    fun `a provider without a known category opens its first one, cached under that provider`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        val cache = FakeCacheDao()
        val vm = LiveViewModel(FakeModule(xuper, tv), FakeFavoriteDao(), cache)
        advanceUntilIdle()
        vm.chooseProvider(tvId)
        advanceUntilIdle()
        assertEquals(tvId, vm.state.value.activeProvider)
        assertEquals("news", vm.state.value.activeCategory)
        assertEquals(listOf("plugin:tv:c1"), vm.state.value.channels.map { it.liveCode })
        assertEquals(listOf("c1"), cache.byCategory(tvId, "news").map { it.code })
        assertEquals(emptyList<String>(), cache.byCategory("xuper", "news").map { it.code })
    }

    @Test
    fun `the same code in two providers is two favourites, listed together`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        val vm = LiveViewModel(FakeModule(xuper, tv), FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.toggleFavorite(ch("c1"))
        vm.toggleFavorite(ch("c1", tvId))
        advanceUntilIdle()
        assertEquals(setOf("c1", "plugin:tv:c1"), vm.state.value.favorites)
        vm.chooseCategory(CATEGORY_FAVORITES)
        advanceUntilIdle()
        assertEquals(setOf("c1", "plugin:tv:c1"), vm.state.value.channels.map { it.liveCode }.toSet())
        vm.toggleFavorite(ch("c1", tvId))
        advanceUntilIdle()
        assertEquals(setOf("c1"), vm.state.value.favorites)
    }

    @Test
    fun `when the active provider disappears the next one takes over, and none leaves an empty idle screen`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        val module = FakeModule(xuper, tv)
        val vm = LiveViewModel(module, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        module.providers.value = listOf(tv)
        advanceUntilIdle()
        assertEquals(tvId, vm.state.value.activeProvider)
        assertEquals(listOf("plugin:tv:c1"), vm.state.value.channels.map { it.liveCode })
        assertEquals(false, vm.state.value.showProviders)
        module.providers.value = emptyList()
        advanceUntilIdle()
        assertEquals(null, vm.state.value.activeProvider)
        assertEquals(emptyList<LiveChannel>(), vm.state.value.channels)
        assertEquals(false, vm.state.value.loading)
        assertTrue(vm.state.value.moduleEmpty)
    }

    @Test
    fun `favourites of a provider that is gone are hidden, never deleted`() = runTest(dispatcher) {
        val (xuper, _) = twoProviders()
        val favs = FakeFavoriteDao()
        favs.save(LiveFavoriteEntity("c1", "C1", 1, null, provider = tvId))
        favs.save(LiveFavoriteEntity("c1", "C1", 1, null))
        val vm = LiveViewModel(FakeModule(xuper), favs, FakeCacheDao())
        advanceUntilIdle()
        vm.chooseCategory(CATEGORY_FAVORITES)
        advanceUntilIdle()
        assertEquals(listOf("c1"), vm.state.value.channels.map { it.liveCode })
        assertEquals(2, favs.getAll().size)
    }

    @Test
    fun `guide requests go to each channel's own provider`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        val vm = LiveViewModel(FakeModule(xuper, tv), FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        xuper.guideCalls.clear()
        vm.requestEpg(listOf(ch("a"), ch("b", tvId)))
        advanceUntilIdle()
        assertEquals(listOf(listOf("a")), xuper.guideCalls)
        assertEquals(listOf(listOf("b")), tv.guideCalls)
    }

    @Test
    fun `the drawer's model only sees the channel's own provider`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        val favs = FakeFavoriteDao()
        favs.save(LiveFavoriteEntity("c1", "C1", 1, null))
        favs.save(LiveFavoriteEntity("c1", "C1", 1, null, provider = tvId))
        val vm = LiveViewModel(FakeModule(xuper, tv), favs, FakeCacheDao(), onlyProvider = tvId)
        advanceUntilIdle()
        assertEquals(listOf(tvId), vm.state.value.providers.map { it.id })
        vm.chooseCategory(CATEGORY_FAVORITES)
        advanceUntilIdle()
        assertEquals(listOf("plugin:tv:c1"), vm.state.value.channels.map { it.liveCode })
    }

    @Test
    fun `the guide is offered only for a provider that can have one`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        tv.guide = false
        val vm = LiveViewModel(FakeModule(xuper, tv), FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        assertEquals(true, vm.state.value.hasGuide)
        vm.chooseProvider(tvId)
        advanceUntilIdle()
        assertEquals(false, vm.state.value.hasGuide)
    }

    @Test
    fun `a plugin's later guide channels are never retried on a timer, only on the next request`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        tv.guideLater = true
        val vm = LiveViewModel(FakeModule(xuper, tv), FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.chooseProvider(tvId)
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(listOf("c1")), tv.guideCalls)
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals("no timed retry for a plugin provider", 1, tv.guideCalls.size)
        vm.requestEpg(listOf(ch("c1", tvId)))
        advanceUntilIdle()
        assertEquals("the next natural request asks again", 2, tv.guideCalls.size)
    }

    @Test
    fun `a provider replaced mid-load is re-read, never shown as an error`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        val gate = CompletableDeferred<Unit>()
        tv.channelsGate = gate
        val module = FakeModule(xuper, tv)
        val vm = LiveViewModel(module, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        vm.chooseProvider(tvId)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.loading)
        // LiveCatalog's order: the old instance is closed first, then the new list is published.
        val tv2 = FakeProvider(tvId, "Tu servidor", categoriesResult = listOf(ProviderCategory("news", "Noticias")))
        tv2.channelsByCategory["news"] = listOf(ch("c2", tvId))
        tv.close()
        advanceUntilIdle()
        assertEquals(null, vm.state.value.error)
        module.providers.value = listOf(xuper, tv2)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(tvId, s.activeProvider)
        assertEquals(null, s.error)
        assertEquals(false, s.loading)
        assertEquals(listOf("plugin:tv:c2"), s.channels.map { it.liveCode })
    }

    @Test
    fun `a replaced provider's guide call ends quietly and frees its channels`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        val module = FakeModule(xuper, tv)
        val vm = LiveViewModel(module, FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        val closing = object : LiveChannelProvider by tv {
            override suspend fun guide(channels: List<LiveChannel>): Pair<Map<String, List<LiveProgram>>, List<String>> =
                throw CancellationException("closed")
        }
        module.providers.value = listOf(xuper, closing)
        advanceUntilIdle()
        vm.requestEpg(listOf(ch("z", tvId)))
        advanceUntilIdle()
        module.providers.value = listOf(xuper, tv)
        advanceUntilIdle()
        tv.guideCalls.clear()
        vm.requestEpg(listOf(ch("z", tvId)))
        advanceUntilIdle()
        assertEquals(listOf(listOf("z")), tv.guideCalls)
    }

    @Test
    fun `the active provider's playlist notice is in the state`() = runTest(dispatcher) {
        val (xuper, tv) = twoProviders()
        val vm = LiveViewModel(FakeModule(xuper, tv), FakeFavoriteDao(), FakeCacheDao())
        advanceUntilIdle()
        tv.noticeFlow.value = "Lista recortada: 5000 de 9000 canales"
        advanceUntilIdle()
        assertEquals(null, vm.state.value.notice)
        vm.chooseProvider(tvId)
        advanceUntilIdle()
        assertEquals("Lista recortada: 5000 de 9000 canales", vm.state.value.notice)
        tv.noticeFlow.value = null
        advanceUntilIdle()
        assertEquals(null, vm.state.value.notice)
        tv.noticeFlow.value = "otra"
        vm.chooseProvider("xuper")
        advanceUntilIdle()
        assertEquals(null, vm.state.value.notice)
    }
}
