package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.MagisEntities
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
import com.arkiv.player.data.gateway.MAGIS_SERIES
import com.arkiv.player.data.gateway.SearchEvent
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.gateway.SeriesListing
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.local.EnqueueOutcome
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.ui.home.MagisDownloadActions
import com.arkiv.player.ui.search.PlaybackResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TitleInfoViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // ---- fixtures ----

    private class FakeContent(
        var episodesFor: suspend (String) -> Pair<List<GatewayEpisode>, GatewaySeries?> = { emptyList<GatewayEpisode>() to null },
        var seasonsFor: suspend (String) -> List<SeasonRef> = { emptyList() },
        var imdbFor: suspend (String) -> String? = { null },
    ) : ContentSource {
        val episodeRequests = mutableListOf<String>()
        override fun recognizes(ref: String) = true
        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = emptyFlow()
        override suspend fun resolve(ref: String): GatewayPlayable = error("not used by the page")
        override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> {
            episodeRequests += ref
            return episodesFor(ref)
        }
        /** One answer, as a plugin's `episodes` is: the chapters and the siblings it names (none = read the seasons from the chapters). */
        override suspend fun seriesListing(ref: String): SeriesListing {
            val (episodes, series) = episodesWithSeries(ref)
            return SeriesListing(episodes, series, seasonsFor(ref))
        }
        override suspend fun movieImdbId(ref: String): String? = imdbFor(ref)
    }

    /**
     * A source shaped like the retired native Magis one (its ids, its `MagisRef` season refs, a
     * `content_id` in the result), so the view model's sibling-season, download and IMDb-hint
     * paths stay covered by a source that is not the plugin one.
     */
    private class SiblingsSource(
        override val downloads: MagisDownloadActions?,
        private val movieImdbId: suspend (ref: String) -> String?,
        private val onPlayMovie: suspend (GatewayResult) -> PlaybackResult,
        private val onPlaySeason: suspend (GatewayResult, List<GatewayEpisode>, GatewayEpisode, GatewaySeries?) -> PlaybackResult,
    ) : TitleSource {
        override fun siblingItem(current: CatalogItem, season: SeasonRef): CatalogItem = current.copy(
            id = season.contentId,
            ref = MagisRef(season.contentId, current.type, 0).encode(),
            title = seasonTitle(current.title, season.number),
            episodeCount = 0,
        )
        override fun itemId(item: CatalogItem): String = MagisEntities.itemIdFor(item.id)
        override fun movieEpisodeId(item: CatalogItem): String = MagisEntities.movieEpisodeId(itemId(item))
        override fun chapterEpisodeId(item: CatalogItem, chapter: GatewayEpisode): String =
            MagisEntities.episodeIdFor(itemId(item), chapter.number)
        override fun gatewayResult(item: CatalogItem): GatewayResult = GatewayResult(
            source = "magis", title = item.title.ifBlank { item.id }, ref = item.ref,
            kind = if (item.type in MAGIS_SERIES) "series" else "movie",
            extra = mapOf("content_id" to item.id, "program_type" to item.type),
        )
        override suspend fun tmdbHint(item: CatalogItem): TmdbHint =
            if (item.type in MAGIS_SERIES) TmdbHint() else TmdbHint(imdbId = movieImdbId(item.ref).orEmpty())
        override suspend fun playMovie(result: GatewayResult): PlaybackResult = onPlayMovie(result)
        override suspend fun playSeason(
            result: GatewayResult,
            chapters: List<GatewayEpisode>,
            chosen: GatewayEpisode,
            series: GatewaySeries?,
        ): PlaybackResult = onPlaySeason(result, chapters, chosen, series)
    }

    private fun movie(id: String = "m1") = CatalogItem(
        id = id, title = "Una peli", poster = "p", durationS = 6000, ref = MagisRef(id, "movie", 0).encode(),
        type = "movie", genres = listOf("Drama"), description = "Sinopsis del portal",
    )

    private fun show(id: String = "s1", title: String = "Una serie T1", description: String = "Sinopsis del portal") = CatalogItem(
        id = id, title = title, poster = "p", durationS = 0, ref = MagisRef(id, "teleplay", 0).encode(),
        type = "teleplay", description = description, episodeCount = 3,
    )

    private fun chapters(vararg numbers: Int) = numbers.map { GatewayEpisode(number = it, title = "E$it", ref = "r$it") }
    private fun series(tmdbId: Int = 5, season: Int = 1) = GatewaySeries(imdbId = "tt1", tmdbId = tmdbId, seasonNumber = season)
    private fun tmdb(year: String = "2026", overview: String = "Sinopsis TMDB") = TmdbInfo(
        id = 5, overview = overview, tagline = "Un lema", year = year, runtimeMinutes = 144,
        genres = listOf("Comedia"), voteAverage = 7.6, directors = listOf("Alguien"),
        cast = listOf("Actriz", "Actor"), certification = "12+",
    )
    private fun noDownloads() = MagisDownloadActions({ null }, { _, _, _ -> null }, { _, _ -> EnqueueOutcome.QUEUED })

    private fun vm(
        item: CatalogItem,
        content: FakeContent = FakeContent(),
        playMovie: suspend (GatewayResult) -> PlaybackResult = { PlaybackResult.Ready("magis:${it.extra["content_id"]}::0") },
        playSeason: suspend (GatewayResult, List<GatewayEpisode>, GatewayEpisode, GatewaySeries?) -> PlaybackResult =
            { _, _, chosen, _ -> PlaybackResult.Ready("ep${chosen.number}") },
        downloads: MagisDownloadActions = noDownloads(),
        progress: (String) -> Flow<Map<String, PlaybackEntity>> = { flowOf(emptyMap()) },
        downloadStates: Flow<Map<String, DownloadDisplayState>> = flowOf(emptyMap()),
        tmdbInfo: suspend (String, Int) -> TmdbInfo? = { _, _ -> null },
        tmdbMovieId: suspend (String) -> Int? = { null },
    ) = TitleInfoViewModel(
        item, content,
        SiblingsSource(
            downloads = downloads,
            movieImdbId = { ref -> content.movieImdbId(ref) },
            onPlayMovie = playMovie,
            onPlaySeason = playSeason,
        ),
        progress, downloadStates, tmdbInfo, tmdbMovieId,
    )

    /** Existing tests name a chapter by number; the view model now takes the chapter itself. */
    private fun TitleInfoViewModel.play(number: Int) {
        val chapter = (state.value.episodes as EpisodesState.Loaded).chapters.first { it.number == number }
        play(chapter)
    }

    private fun TestScope.collect(vm: TitleInfoViewModel): List<TitleInfoEvent> {
        val events = mutableListOf<TitleInfoEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    // ---- loading ----

    @Test
    fun `a movie opens with its own data and loads nothing`() = runTest {
        val content = FakeContent()
        val vm = vm(movie(), content)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("Una peli", state.info.title)
        assertEquals(TitleKind.MOVIE, state.info.kind)
        assertEquals(EpisodesState.None, state.episodes)
        assertTrue(content.episodeRequests.isEmpty())
        assertEquals(PrimaryAction("Reproducir", null), state.primary)
    }

    @Test
    fun `a series shows Loading and then its chapters`() = runTest {
        val gate = CompletableDeferred<Pair<List<GatewayEpisode>, GatewaySeries?>>()
        val vm = vm(show(), FakeContent(episodesFor = { gate.await() }))
        advanceUntilIdle()
        assertEquals(EpisodesState.Loading, vm.state.value.episodes)
        assertNull(vm.state.value.primary)

        gate.complete(chapters(1, 2, 3) to series())
        advanceUntilIdle()
        val loaded = vm.state.value.episodes as EpisodesState.Loaded
        assertEquals(listOf(1, 2, 3), loaded.chapters.map { it.number })
        assertEquals(PrimaryAction("Reproducir episodio 1", 1), vm.state.value.primary)
        assertEquals(1, vm.state.value.info.seasonNumber)
    }

    @Test
    fun `a series the portal answers with no chapters is reported and can be retried`() = runTest {
        // MagisSource does not throw for this (it happens on transient portal failures): without an
        // explicit state the button said "Cargando…" over an empty list, for ever, with no retry.
        var answer: Pair<List<GatewayEpisode>, GatewaySeries?> = emptyList<GatewayEpisode>() to series()
        val vm = vm(show(), FakeContent(episodesFor = { answer }))
        advanceUntilIdle()
        assertTrue(vm.state.value.episodes is EpisodesState.Failed)
        assertNull(vm.state.value.primary)

        answer = chapters(1, 2) to series()
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.episodes is EpisodesState.Loaded)
        assertEquals(PrimaryAction("Reproducir episodio 1", 1), vm.state.value.primary)
    }

    @Test
    fun `TMDB adds the year and keeps the portal's synopsis`() = runTest {
        val vm = vm(show(), FakeContent(episodesFor = { chapters(1) to series() }), tmdbInfo = { _, _ -> tmdb() })
        advanceUntilIdle()
        assertEquals("2026", vm.state.value.info.year)
        assertEquals("Sinopsis del portal", vm.state.value.info.synopsis)
    }

    @Test
    fun `TMDB fills the synopsis only when the portal sent none`() = runTest {
        val vm = vm(show(description = ""), FakeContent(episodesFor = { chapters(1) to series() }), tmdbInfo = { _, _ -> tmdb() })
        advanceUntilIdle()
        assertEquals("Sinopsis TMDB", vm.state.value.info.synopsis)
    }

    @Test
    fun `TMDB is not asked when the series has no TMDB id, and a TMDB failure changes nothing`() = runTest {
        var calls = 0
        val noId = vm(show(), FakeContent(episodesFor = { chapters(1) to series(tmdbId = 0) }), tmdbInfo = { _, _ -> calls++; tmdb() })
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals("", noId.state.value.info.year)

        val failing = vm(show(), FakeContent(episodesFor = { chapters(1) to series() }), tmdbInfo = { _, _ -> error("no key") })
        advanceUntilIdle()
        assertEquals("", failing.state.value.info.year)
        assertTrue(failing.state.value.episodes is EpisodesState.Loaded)
    }

    // ---- TMDB: movies through their IMDb id, and the extra fields ----

    @Test
    fun `a movie is enriched through its IMDb id`() = runTest {
        var imdbAsked: String? = null
        var infoAsked: Pair<String, Int>? = null
        val vm = vm(
            movie().copy(durationS = 0),
            FakeContent(imdbFor = { "tt6300910" }),
            tmdbInfo = { type, id -> infoAsked = type to id; tmdb() },
            tmdbMovieId = { imdb -> imdbAsked = imdb; 1233413 },
        )
        advanceUntilIdle()
        assertEquals("tt6300910", imdbAsked)
        assertEquals("movie" to 1233413, infoAsked)
        val info = vm.state.value.info
        assertEquals("2026", info.year)
        assertEquals(144, info.runtimeMinutes)
        assertEquals("Un lema", info.tagline)
        assertEquals(listOf("Actriz", "Actor"), info.cast)
        assertEquals(listOf("Alguien"), info.directors)
        assertEquals("12+", info.certification)
        assertEquals(listOf("Comedia"), info.genres)
        assertEquals("Sinopsis del portal", info.synopsis)
    }

    @Test
    fun `a movie with no IMDb id is never guessed by its title`() = runTest {
        var lookups = 0
        var infos = 0
        val vm = vm(
            movie(),
            FakeContent(imdbFor = { null }),
            tmdbInfo = { _, _ -> infos++; tmdb() },
            tmdbMovieId = { lookups++; 1 },
        )
        advanceUntilIdle()
        assertEquals(0, lookups)
        assertEquals(0, infos)
        assertEquals("", vm.state.value.info.year)
    }

    @Test
    fun `a movie TMDB does not know keeps the portal's data`() = runTest {
        var infos = 0
        val vm = vm(
            movie(),
            FakeContent(imdbFor = { "tt1234567" }),
            tmdbInfo = { _, _ -> infos++; tmdb() },
            tmdbMovieId = { null },
        )
        advanceUntilIdle()
        assertEquals(0, infos)
        assertEquals(listOf("Drama"), vm.state.value.info.genres)
    }

    @Test
    fun `a failure at any step of a movie's lookup changes nothing`() = runTest {
        val portalFails = vm(
            movie(), FakeContent(imdbFor = { throw GatewayException("caído") }),
            tmdbInfo = { _, _ -> tmdb() }, tmdbMovieId = { 1 },
        )
        val findFails = vm(
            movie(), FakeContent(imdbFor = { "tt1234567" }),
            tmdbInfo = { _, _ -> tmdb() }, tmdbMovieId = { error("sin llave") },
        )
        val infoFails = vm(
            movie(), FakeContent(imdbFor = { "tt1234567" }),
            tmdbInfo = { _, _ -> error("500") }, tmdbMovieId = { 1 },
        )
        advanceUntilIdle()
        listOf(portalFails, findFails, infoFails).forEach {
            assertEquals("", it.state.value.info.year)
            assertEquals("Una peli", it.state.value.info.title)
            assertEquals(100, it.state.value.info.runtimeMinutes)
        }
    }

    @Test
    fun `a series asks TMDB for its tv entry and shows the extra fields`() = runTest {
        var infoAsked: Pair<String, Int>? = null
        val vm = vm(
            show(),
            FakeContent(episodesFor = { chapters(1) to series(tmdbId = 42) }),
            tmdbInfo = { type, id -> infoAsked = type to id; tmdb() },
        )
        advanceUntilIdle()
        assertEquals("tv" to 42, infoAsked)
        assertEquals("Un lema", vm.state.value.info.tagline)
        assertEquals(listOf("Actriz", "Actor"), vm.state.value.info.cast)
        assertEquals("12+", vm.state.value.info.certification)
    }

    @Test
    fun `switching season keeps what TMDB added`() = runTest {
        val vm = vm(
            show(),
            FakeContent(
                episodesFor = { chapters(1) to series() },
                seasonsFor = { listOf(SeasonRef("s1", 1), SeasonRef("s2", 2)) },
            ),
            tmdbInfo = { _, _ -> tmdb() },
        )
        advanceUntilIdle()
        vm.selectSeason(SeasonRef("s2", 2))
        val right = vm.state.value.info
        assertEquals(2, right.seasonNumber)
        assertEquals("Un lema", right.tagline)
        assertEquals(listOf("Actriz", "Actor"), right.cast)
        assertEquals(listOf("Comedia"), right.genres)
        assertEquals("2026", right.year)
    }

    @Test
    fun `a sibling whose own listing names no seasons keeps the selector it already had`() = runTest {
        var switched = false
        val vm = vm(
            show(),
            FakeContent(
                episodesFor = { chapters(1) to series() },
                seasonsFor = { if (switched) emptyList() else listOf(SeasonRef("s1", 1), SeasonRef("s2", 2)) },
            ),
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.showSeasonSelector)
        switched = true
        vm.selectSeason(SeasonRef("s2", 2))
        advanceUntilIdle()
        assertTrue("the seasons are already known: an answer naming none must not hide them", vm.state.value.showSeasonSelector)
        assertEquals(2, vm.state.value.seasons.size)
        assertTrue("and they stay siblings, never re-read from the new chapter list", vm.state.value.siblings)
        assertTrue(vm.state.value.isCurrentSeason(SeasonRef("s2", 2)))
    }

    @Test
    fun `a sibling whose listing fails keeps the selector and reports only the chapters`() = runTest {
        var switched = false
        val vm = vm(
            show(),
            FakeContent(
                episodesFor = { if (switched) throw GatewayException("caído") else chapters(1) to series() },
                seasonsFor = { listOf(SeasonRef("s1", 1), SeasonRef("s2", 2)) },
            ),
        )
        advanceUntilIdle()
        switched = true
        vm.selectSeason(SeasonRef("s2", 2))
        advanceUntilIdle()
        assertTrue(vm.state.value.episodes is EpisodesState.Failed)
        assertTrue(vm.state.value.showSeasonSelector)
        assertEquals("s2", vm.state.value.item.id)
    }

    @Test
    fun `switching season does not ask TMDB again for the same series`() = runTest {
        var asked = 0
        val vm = vm(
            show(),
            FakeContent(
                episodesFor = { chapters(1) to series(tmdbId = 5) },
                seasonsFor = { listOf(SeasonRef("s1", 1), SeasonRef("s2", 2)) },
            ),
            tmdbInfo = { _, _ -> asked++; tmdb() },
        )
        advanceUntilIdle()
        assertEquals(1, asked)
        vm.selectSeason(SeasonRef("s2", 2))
        advanceUntilIdle()
        assertEquals("same TMDB id: nothing new to learn", 1, asked)
        assertEquals("Un lema", vm.state.value.info.tagline)
    }

    @Test
    fun `a TMDB answer that lands after a season switch does not stop the new season being enriched`() = runTest {
        val gate = CompletableDeferred<TmdbInfo?>()
        var calls = 0
        val vm = vm(
            show(),
            FakeContent(
                episodesFor = { chapters(1) to series(tmdbId = 5) },
                seasonsFor = { listOf(SeasonRef("s1", 1), SeasonRef("s2", 2)) },
            ),
            tmdbInfo = { _, _ -> calls++; if (calls == 1) gate.await() else tmdb() },
        )
        advanceUntilIdle() // the first season's TMDB request is in flight
        vm.selectSeason(SeasonRef("s2", 2))
        gate.complete(tmdb()) // it lands for a page that is already on season 2: dropped
        advanceUntilIdle()
        assertEquals("the dropped answer must not count as done", "Un lema", vm.state.value.info.tagline)
    }

    @Test
    fun `a season that resolves to another TMDB id is asked`() = runTest {
        val asked = mutableListOf<Int>()
        val vm = vm(
            show(),
            FakeContent(
                episodesFor = { ref -> chapters(1) to series(tmdbId = if (ref.endsWith("s2")) 6 else 5) },
                seasonsFor = { listOf(SeasonRef("s1", 1), SeasonRef("s2", 2)) },
            ),
            tmdbInfo = { _, id -> asked += id; tmdb() },
        )
        advanceUntilIdle()
        vm.selectSeason(SeasonRef("s2", 2))
        advanceUntilIdle()
        assertEquals(listOf(5, 6), asked)
    }

    @Test
    fun `sibling seasons show a selector with this title selected, a single season shows none`() = runTest {
        val many = vm(show(), FakeContent(episodesFor = { chapters(1) to series() }, seasonsFor = { listOf(SeasonRef("s1", 1), SeasonRef("s2", 2)) }))
        val single = vm(show(), FakeContent(episodesFor = { chapters(1) to series() }, seasonsFor = { emptyList() }))
        advanceUntilIdle()
        assertTrue(many.state.value.showSeasonSelector)
        assertTrue(many.state.value.siblings)
        assertTrue(many.state.value.isCurrentSeason(SeasonRef("s1", 1)))
        assertFalse(many.state.value.isCurrentSeason(SeasonRef("s2", 2)))
        assertNull("siblings: no chapter-list season is being shown", many.state.value.currentSeason)
        assertFalse(single.state.value.showSeasonSelector)
        assertFalse(single.state.value.siblings)
    }

    @Test
    fun `the source's current flag selects a sibling whose id is not the page's`() = runTest {
        val vm = vm(
            show(id = "portal-1"),
            FakeContent(
                episodesFor = { chapters(1) to series() },
                seasonsFor = { listOf(SeasonRef("a", 1, title = "Primera"), SeasonRef("b", 2, title = "Segunda", current = true)) },
            ),
        )
        advanceUntilIdle()
        assertFalse(vm.state.value.isCurrentSeason(vm.state.value.seasons[0]))
        assertTrue(vm.state.value.isCurrentSeason(vm.state.value.seasons[1]))
        assertEquals(listOf("Primera", "Segunda"), vm.state.value.seasons.map { it.label })
    }

    @Test
    fun `a listing naming no siblings reads the seasons from the chapter list and filters without asking again`() = runTest {
        val twoSeasons = listOf(1, 2).flatMap { s -> (1..2).map { GatewayEpisode(number = it, title = "E$it", ref = "r$s-$it", season = s) } }
        val content = FakeContent(episodesFor = { twoSeasons to series() }, seasonsFor = { emptyList() })
        val vm = vm(show(), content)
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.siblings)
        assertEquals(listOf(SeasonRef("1", 1), SeasonRef("2", 2)), state.seasons)
        assertTrue(state.showSeasonSelector)
        assertEquals(1, state.currentSeason)
        assertEquals(listOf("r1-1", "r1-2"), state.visibleChapters.map { it.ref })

        vm.selectSeason(SeasonRef("2", 2))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.currentSeason)
        assertEquals(listOf("r2-1", "r2-2"), vm.state.value.visibleChapters.map { it.ref })
        assertEquals("the item is the same: nothing to reload", 1, content.episodeRequests.size)
        assertEquals("s1", vm.state.value.item.id)
    }

    @Test
    fun `a failed load shows Failed and a retry recovers`() = runTest {
        var calls = 0
        val played = mutableListOf<Int>()
        val vm = vm(
            show(),
            FakeContent(episodesFor = { if (calls++ == 0) throw GatewayException("Sin conexión") else chapters(1, 2) to series() }),
            playSeason = { _, _, chosen, _ -> played += chosen.number; PlaybackResult.Ready("ep") },
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.episodes is EpisodesState.Failed)
        assertNull(vm.state.value.primary)

        vm.play()
        advanceUntilIdle()
        assertTrue("nothing plays while the chapters are missing", played.isEmpty())

        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.episodes is EpisodesState.Loaded)
        assertEquals(PrimaryAction("Reproducir episodio 1", 1), vm.state.value.primary)
    }

    // ---- seasons ----

    @Test
    fun `switching season rebuilds the item and reloads its chapters`() = runTest {
        val requested = mutableListOf<String>()
        val content = FakeContent(
            episodesFor = { ref -> requested += ref; chapters(1, 2) to series(season = if (ref.endsWith("s2")) 2 else 1) },
            seasonsFor = { listOf(SeasonRef("s1", 1), SeasonRef("s2", 2)) },
        )
        val vm = vm(show(id = "s1", title = "Una serie T1"), content)
        advanceUntilIdle()

        vm.selectSeason(SeasonRef("s2", 2))
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("s2", state.item.id)
        assertEquals("Una serie T2", state.item.title)
        assertEquals("Una serie T2", state.info.title)
        assertEquals(MagisRef("s2", "teleplay", 0).encode(), state.item.ref)
        assertEquals(2, state.info.seasonNumber)
        assertEquals(2, state.seasons.size)
        assertEquals(listOf(MagisRef("s1", "teleplay", 0).encode(), MagisRef("s2", "teleplay", 0).encode()), requested)

        vm.selectSeason(SeasonRef("s2", 2))
        advanceUntilIdle()
        assertEquals("choosing the current season is a no-op", 2, requested.size)
    }

    @Test
    fun `a stale season answer is ignored`() = runTest {
        // The siblings are known from the first listing; the second season's answer is slow and the
        // person moves on to the third before it lands.
        val second = CompletableDeferred<Pair<List<GatewayEpisode>, GatewaySeries?>>()
        val content = FakeContent(
            episodesFor = { ref ->
                when {
                    ref.endsWith("s2") -> second.await()
                    ref.endsWith("s3") -> chapters(30, 31) to series(season = 3)
                    else -> chapters(1) to series()
                }
            },
            seasonsFor = { listOf(SeasonRef("s1", 1), SeasonRef("s2", 2), SeasonRef("s3", 3)) },
        )
        val vm = vm(show(id = "s1"), content)
        advanceUntilIdle()

        vm.selectSeason(SeasonRef("s2", 2))
        advanceUntilIdle()
        vm.selectSeason(SeasonRef("s3", 3))
        advanceUntilIdle()
        second.complete(chapters(20, 21) to series(season = 2))
        advanceUntilIdle()

        val loaded = vm.state.value.episodes as EpisodesState.Loaded
        assertEquals("s3", vm.state.value.item.id)
        assertEquals(listOf(30, 31), loaded.chapters.map { it.number })
    }

    // ---- progress ----

    @Test
    fun `progress flows into the main button`() = runTest {
        val progress = MutableStateFlow<Map<String, PlaybackEntity>>(emptyMap())
        val vm = vm(show(), FakeContent(episodesFor = { chapters(1, 2, 3) to series() }), progress = { progress })
        advanceUntilIdle()
        assertEquals(PrimaryAction("Reproducir episodio 1", 1), vm.state.value.primary)

        val id = MagisEntities.episodeIdFor(MagisEntities.itemIdFor("s1"), 2)
        progress.value = mapOf(id to PlaybackEntity(id, 60_000, 1_000_000, false, 100))
        advanceUntilIdle()
        assertEquals(PrimaryAction("Continuar episodio 2", 2), vm.state.value.primary)
    }

    @Test
    fun `download states flow into the state`() = runTest {
        val vm = vm(movie(), downloadStates = flowOf(mapOf("magis:m1::0" to DownloadDisplayState.Done)))
        advanceUntilIdle()
        assertEquals(DownloadDisplayState.Done, vm.state.value.downloads["magis:m1::0"])
    }

    // ---- play ----

    @Test
    fun `playing a series hands the loaded chapters and series to the season path`() = runTest {
        val calls = mutableListOf<Triple<GatewayResult, List<Int>, Int>>()
        val seriesBlock = series()
        var seenSeries: GatewaySeries? = null
        val vm = vm(
            show(),
            FakeContent(episodesFor = { chapters(1, 2, 3) to seriesBlock }),
            playSeason = { season, chs, chosen, s -> calls += Triple(season, chs.map { it.number }, chosen.number); seenSeries = s; PlaybackResult.Ready("magis:s1::e${chosen.number}") },
        )
        val events = collect(vm)
        advanceUntilIdle()

        vm.play()
        advanceUntilIdle()
        assertEquals(1, calls.size)
        assertEquals(listOf(1, 2, 3), calls[0].second)
        assertEquals(1, calls[0].third)
        assertEquals("s1", calls[0].first.extra["content_id"])
        assertEquals(seriesBlock, seenSeries)
        assertEquals(listOf<TitleInfoEvent>(TitleInfoEvent.OpenPlayer("magis:s1::e1")), events)
        assertFalse(vm.state.value.resolving)

        vm.play(3)
        advanceUntilIdle()
        assertEquals(3, calls[1].third)
    }

    @Test
    fun `playing a movie uses the movie path`() = runTest {
        val vm = vm(movie())
        val events = collect(vm)
        advanceUntilIdle()
        vm.play()
        advanceUntilIdle()
        assertEquals(listOf<TitleInfoEvent>(TitleInfoEvent.OpenPlayer("magis:m1::0")), events)
    }

    @Test
    fun `a failed play says why and clears the spinner`() = runTest {
        val vm = vm(movie(), playMovie = { PlaybackResult.Failed("No se pudo preparar la reproducción de Xuper.") })
        val events = collect(vm)
        advanceUntilIdle()
        vm.play()
        advanceUntilIdle()
        assertEquals(listOf<TitleInfoEvent>(TitleInfoEvent.Message("No se pudo preparar la reproducción de Xuper.")), events)
        assertFalse(vm.state.value.resolving)
    }

    @Test
    fun `a play that throws becomes a message too`() = runTest {
        val vm = vm(movie(), playMovie = { error("boom") })
        val events = collect(vm)
        advanceUntilIdle()
        vm.play()
        advanceUntilIdle()
        assertTrue(events.single() is TitleInfoEvent.Message)
        assertFalse(vm.state.value.resolving)
    }

    @Test
    fun `a second play is ignored while the first is resolving`() = runTest {
        val gate = CompletableDeferred<PlaybackResult>()
        var calls = 0
        val vm = vm(movie(), playMovie = { calls++; gate.await() })
        advanceUntilIdle()
        vm.play()
        advanceUntilIdle()
        assertTrue(vm.state.value.resolving)
        vm.play()
        advanceUntilIdle()
        assertEquals(1, calls)
        gate.complete(PlaybackResult.Ready("magis:m1::0"))
        advanceUntilIdle()
        assertFalse(vm.state.value.resolving)
    }

    // ---- downloads ----

    @Test
    fun `downloading a season enqueues every chapter and reports the batch`() = runTest {
        val queued = mutableListOf<String>()
        val downloads = MagisDownloadActions({ null }, { _, chapter, _ -> "magis:s1::e${chapter.number}" }, { id, _ -> queued += id; EnqueueOutcome.QUEUED })
        val vm = vm(show(), FakeContent(episodesFor = { chapters(1, 2, 3) to series() }), downloads = downloads)
        val events = collect(vm)
        advanceUntilIdle()

        vm.downloadSeason()
        advanceUntilIdle()
        assertEquals(listOf("magis:s1::e1", "magis:s1::e2", "magis:s1::e3"), queued)
        assertEquals(
            TitleInfoEvent.Downloaded(List(3) { EnqueueOutcome.QUEUED }, "Descargando 3 capítulo(s)…", noticeDuplicates = false),
            events.single(),
        )
    }

    @Test
    fun `downloading chosen chapters enqueues only those`() = runTest {
        val queued = mutableListOf<String>()
        val downloads = MagisDownloadActions({ null }, { _, chapter, _ -> "magis:s1::e${chapter.number}" }, { id, _ -> queued += id; EnqueueOutcome.QUEUED })
        val vm = vm(show(), FakeContent(episodesFor = { chapters(1, 2, 3) to series() }), downloads = downloads)
        advanceUntilIdle()
        vm.downloadChapters(listOf(2))
        advanceUntilIdle()
        assertEquals(listOf("magis:s1::e2"), queued)
    }

    @Test
    fun `downloading a movie reports the queued toast`() = runTest {
        val downloads = MagisDownloadActions({ "magis:m1::0" }, { _, _, _ -> null }, { _, _ -> EnqueueOutcome.QUEUED })
        val vm = vm(movie(), downloads = downloads)
        val events = collect(vm)
        advanceUntilIdle()
        vm.downloadMovie()
        advanceUntilIdle()
        assertEquals(
            TitleInfoEvent.Downloaded(listOf(EnqueueOutcome.QUEUED), "Descarga de \"Una peli\" en cola", noticeDuplicates = true),
            events.single(),
        )
    }

    @Test
    fun `a movie whose download cannot be prepared says so`() = runTest {
        val vm = vm(movie(), downloads = noDownloads())
        val events = collect(vm)
        advanceUntilIdle()
        vm.downloadMovie()
        advanceUntilIdle()
        assertEquals(listOf<TitleInfoEvent>(TitleInfoEvent.Message("No se pudo preparar la descarga.")), events)
    }
}
