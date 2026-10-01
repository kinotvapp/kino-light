package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.catalog.TmdbInfo
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SearchEvent
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.plugin.PluginSetupRequiredException
import com.arkiv.player.ui.home.MagisDownloadActions
import com.arkiv.player.ui.search.PlaybackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** The view model with a source that keeps every season in one list (the plugin model). */
@OptIn(ExperimentalCoroutinesApi::class)
class TitleInfoViewModelInListTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // ---- fixtures ----

    private class ListContent(
        val chapters: List<GatewayEpisode>,
        val tmdbId: Int = 0,
        var failure: Exception? = null,
    ) : ContentSource {
        var requests = 0
        override fun recognizes(ref: String) = true
        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = emptyFlow()
        override suspend fun resolve(ref: String): GatewayPlayable = error("not used")
        override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> {
            requests++
            failure?.let { throw it }
            val series = if (tmdbId > 0) GatewaySeries(imdbId = "", tmdbId = tmdbId, seasonNumber = 1) else null
            return chapters to series
        }
    }

    private class ListSource(val hint: TmdbHint = TmdbHint()) : TitleSource {
        val played = mutableListOf<Triple<List<GatewayEpisode>, GatewayEpisode, GatewayResult>>()
        override fun siblingItem(current: CatalogItem, season: SeasonRef): CatalogItem =
            error("the list holds every season: no sibling is ever named")
        override val downloads: MagisDownloadActions? = null
        override fun itemId(item: CatalogItem) = "lst:${item.id}"
        override fun movieEpisodeId(item: CatalogItem) = "lst:${item.id}::0"
        override fun chapterEpisodeId(item: CatalogItem, chapter: GatewayEpisode) =
            "lst:${item.id}::s${chapter.seasonOrOne}e${chapter.number}"
        override fun gatewayResult(item: CatalogItem) = GatewayResult(source = "lst", title = item.title, ref = item.ref)
        override suspend fun tmdbHint(item: CatalogItem) = hint
        override suspend fun playMovie(result: GatewayResult): PlaybackResult = PlaybackResult.Ready("lst:movie")
        override suspend fun playSeason(
            result: GatewayResult,
            chapters: List<GatewayEpisode>,
            chosen: GatewayEpisode,
            series: GatewaySeries?,
        ): PlaybackResult {
            played += Triple(chapters, chosen, result)
            return PlaybackResult.Ready("lst:${chosen.seasonOrOne}:${chosen.number}")
        }
    }

    private fun show() = CatalogItem(
        id = "s1", title = "Show", poster = null, durationS = 0, ref = "ref-s1", type = "series",
    )

    private fun film() = CatalogItem(
        id = "m1", title = "Film", poster = null, durationS = 0, ref = "ref-m1", type = "movie",
    )

    private fun ch(season: Int?, number: Int) =
        GatewayEpisode(number = number, title = "E$number", ref = "r-$season-$number", season = season)

    private val threeSeasons = listOf(1, 2, 3).flatMap { s -> (1..3).map { ch(s, it) } }

    private fun row(id: String, positionMs: Long, watched: Boolean, last: Long) =
        id to PlaybackEntity(id, positionMs, 1_000_000L, watched, last)

    private fun vm(
        item: CatalogItem = show(),
        content: ListContent = ListContent(threeSeasons),
        source: ListSource = ListSource(),
        progress: Flow<Map<String, PlaybackEntity>> = flowOf(emptyMap()),
        tmdbInfo: suspend (String, Int) -> TmdbInfo? = { _, _ -> null },
        tmdbMovieId: suspend (String) -> Int? = { null },
    ) = TitleInfoViewModel(item, content, source, { progress }, flowOf(emptyMap()), tmdbInfo, tmdbMovieId)

    private fun tmdb() = TmdbInfo(
        id = 5, overview = "s", tagline = "", year = "2020", runtimeMinutes = 0, genres = emptyList(),
        voteAverage = null, directors = emptyList(), cast = emptyList(), certification = "",
    )

    // ---- seasons from the list ----

    @Test
    fun `the seasons are read from the chapter list`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), vm.state.value.seasons.map { it.number })
        assertTrue(vm.state.value.showSeasonSelector)
    }

    /** ERRORES-5EM/7ME: a specials chapter or a repeated number must not give the lists a duplicate key. */
    @Test
    fun `the listed chapters never repeat a list key`() = runTest {
        val vm = vm(content = ListContent(listOf(ch(0, 1), ch(1, 1), ch(1, 2), ch(1, 2), ch(null, 1))))
        advanceUntilIdle()
        val keys = vm.state.value.visibleChapters.map { it.listKey }
        assertEquals(keys.distinct(), keys)
        assertEquals(listOf("0-1", "1-1", "1-2"), keys)
    }

    @Test
    fun `tapping seasons in quick succession ends on the last one and asks nothing`() = runTest {
        val content = ListContent(threeSeasons)
        val vm = vm(content = content)
        advanceUntilIdle()
        val before = content.requests
        vm.selectSeason(SeasonRef("1", 1))
        vm.selectSeason(SeasonRef("3", 3))
        vm.selectSeason(SeasonRef("2", 2))
        vm.selectSeason(SeasonRef("3", 3))
        advanceUntilIdle()
        assertEquals(3, vm.state.value.currentSeason)
        assertEquals(listOf(3), vm.state.value.visibleChapters.map { it.seasonOrOne }.distinct())
        assertEquals("an in-list switch is a state change, never a request", before, content.requests)
    }

    @Test
    fun `a single season shows no selector`() = runTest {
        val vm = vm(content = ListContent(listOf(ch(1, 1), ch(1, 2))))
        advanceUntilIdle()
        assertFalse(vm.state.value.showSeasonSelector)
        assertEquals(2, vm.state.value.visibleChapters.size)
    }

    @Test
    fun `chapters with no season or season 0 fall into season 1`() = runTest {
        val vm = vm(content = ListContent(listOf(ch(null, 1), ch(0, 2), ch(1, 3))))
        advanceUntilIdle()
        assertEquals(listOf(1), vm.state.value.seasons.map { it.number })
        assertFalse(vm.state.value.showSeasonSelector)
        assertEquals(3, vm.state.value.visibleChapters.size)
    }

    @Test
    fun `with no progress the lowest season is shown`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.currentSeason)
        assertEquals(listOf(1, 1, 1), vm.state.value.visibleChapters.map { it.seasonOrOne })
    }

    @Test
    fun `the visible season follows the chapter the button offers`() = runTest {
        val src = ListSource()
        val id = src.chapterEpisodeId(show(), ch(2, 2))
        val vm = vm(source = src, progress = flowOf(mapOf(row(id, 60_000, false, 100))))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.currentSeason)
        assertEquals(listOf(2, 2, 2), vm.state.value.visibleChapters.map { it.seasonOrOne })
        assertEquals("Continuar T2 · E2", vm.state.value.primary?.label)
    }

    @Test
    fun `choosing a season changes the view without asking the source again`() = runTest {
        val content = ListContent(threeSeasons)
        val vm = vm(content = content)
        advanceUntilIdle()
        val primaryBefore = vm.state.value.primary
        vm.selectSeason(SeasonRef("3", 3))
        assertEquals(1, content.requests)
        assertEquals(3, vm.state.value.currentSeason)
        assertEquals(listOf(3, 3, 3), vm.state.value.visibleChapters.map { it.seasonOrOne })
        assertEquals(primaryBefore, vm.state.value.primary)
        assertTrue(vm.state.value.isCurrentSeason(SeasonRef("3", 3)))
        assertFalse(vm.state.value.isCurrentSeason(SeasonRef("1", 1)))
    }

    @Test
    fun `the person's choice wins over progress that arrives later`() = runTest {
        val src = ListSource()
        val progress = MutableStateFlow<Map<String, PlaybackEntity>>(emptyMap())
        val vm = vm(source = src, progress = progress)
        advanceUntilIdle()
        vm.selectSeason(SeasonRef("3", 3))
        progress.value = mapOf(row(src.chapterEpisodeId(show(), ch(1, 2)), 60_000, false, 100))
        advanceUntilIdle()
        assertEquals(3, vm.state.value.currentSeason)
        assertEquals("Continuar T1 · E2", vm.state.value.primary?.label)
    }

    // ---- play ----

    @Test
    fun `playing a chapter hands over the complete list and the chapter of its own season`() = runTest {
        val src = ListSource()
        val vm = vm(source = src)
        advanceUntilIdle()
        vm.play(threeSeasons.first { it.seasonOrOne == 2 && it.number == 1 })
        advanceUntilIdle()
        val (all, chosen, _) = src.played.single()
        assertEquals(9, all.size)
        assertEquals(2, chosen.seasonOrOne)
        assertEquals(1, chosen.number)
    }

    @Test
    fun `playing with no argument plays the offered chapter even when another season is shown`() = runTest {
        val src = ListSource()
        val id = src.chapterEpisodeId(show(), ch(2, 2))
        val vm = vm(source = src, progress = flowOf(mapOf(row(id, 60_000, false, 100))))
        advanceUntilIdle()
        vm.selectSeason(SeasonRef("1", 1))
        vm.play()
        advanceUntilIdle()
        val chosen = src.played.single().second
        assertEquals(2, chosen.seasonOrOne)
        assertEquals(2, chosen.number)
    }

    // ---- a plugin that is not ready ----

    @Test
    fun `a plugin that needs setup is reported with its id so the page can offer Configurar`() = runTest {
        val content = ListContent(threeSeasons, failure = PluginSetupRequiredException("demo", "Configura Demo en Ajustes ▸ Plugins"))
        val vm = vm(content = content)
        advanceUntilIdle()
        val failed = vm.state.value.episodes as EpisodesState.Failed
        assertEquals("Configura Demo en Ajustes ▸ Plugins", failed.message)
        assertEquals("demo", failed.setupPluginId)
    }

    @Test
    fun `any other plugin failure keeps its own message and offers only Reintentar`() = runTest {
        val content = ListContent(
            threeSeasons,
            failure = GatewayException("Esto venía del plugin Demo, que ya no está instalado"),
        )
        val vm = vm(content = content)
        advanceUntilIdle()
        val failed = vm.state.value.episodes as EpisodesState.Failed
        assertEquals("Esto venía del plugin Demo, que ya no está instalado", failed.message)
        assertEquals(null, failed.setupPluginId)
        assertEquals(null, vm.state.value.primary)
    }

    @Test
    fun `after the person configures the plugin a retry loads the chapters`() = runTest {
        val content = ListContent(threeSeasons, failure = PluginSetupRequiredException("demo", "Configura Demo"))
        val vm = vm(content = content)
        advanceUntilIdle()
        content.failure = null
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.episodes is EpisodesState.Loaded)
        assertEquals(2, content.requests)
    }

    // ---- TMDB hints ----

    @Test
    fun `a movie with a TMDB id in its hint asks TMDB for it without a lookup`() = runTest {
        var asked: Pair<String, Int>? = null
        var lookups = 0
        val vm = vm(
            item = film(), content = ListContent(emptyList()), source = ListSource(TmdbHint(tmdbId = 603)),
            tmdbInfo = { type, id -> asked = type to id; tmdb() }, tmdbMovieId = { lookups++; 1 },
        )
        advanceUntilIdle()
        assertEquals("movie" to 603, asked)
        assertEquals(0, lookups)
        assertEquals("2020", vm.state.value.info.year)
    }

    @Test
    fun `a movie with only an IMDb id in its hint is looked up exactly`() = runTest {
        var imdb: String? = null
        var asked: Pair<String, Int>? = null
        vm(
            item = film(), content = ListContent(emptyList()), source = ListSource(TmdbHint(imdbId = "tt7654321")),
            tmdbInfo = { type, id -> asked = type to id; tmdb() }, tmdbMovieId = { imdb = it; 77 },
        )
        advanceUntilIdle()
        assertEquals("tt7654321", imdb)
        assertEquals("movie" to 77, asked)
    }

    @Test
    fun `a movie with an empty hint is never guessed by title`() = runTest {
        var calls = 0
        vm(
            item = film(), content = ListContent(emptyList()), source = ListSource(TmdbHint()),
            tmdbInfo = { _, _ -> calls++; tmdb() }, tmdbMovieId = { calls++; 1 },
        )
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test
    fun `a series falls back to the hint when the chapters call gave no TMDB id`() = runTest {
        var asked: Pair<String, Int>? = null
        vm(
            content = ListContent(threeSeasons, tmdbId = 0), source = ListSource(TmdbHint(tmdbId = 42)),
            tmdbInfo = { type, id -> asked = type to id; tmdb() },
        )
        advanceUntilIdle()
        assertEquals("tv" to 42, asked)
    }

    @Test
    fun `the chapters call's TMDB id wins over the hint for a series`() = runTest {
        var asked: Pair<String, Int>? = null
        vm(
            content = ListContent(threeSeasons, tmdbId = 9), source = ListSource(TmdbHint(tmdbId = 42)),
            tmdbInfo = { type, id -> asked = type to id; tmdb() },
        )
        advanceUntilIdle()
        assertEquals("tv" to 9, asked)
    }
}
