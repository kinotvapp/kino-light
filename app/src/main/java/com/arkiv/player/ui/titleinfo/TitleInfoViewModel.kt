package com.arkiv.player.ui.titleinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.AppGraph
import com.arkiv.player.data.catalog.TmdbInfo
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.local.ChapterDownloadState
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.local.EnqueueOutcome
import com.arkiv.player.data.plugin.PluginSetupRequiredException
import com.arkiv.player.ui.home.chapterEnqueueMessage
import com.arkiv.player.ui.home.queuedDownloadToastText
import com.arkiv.player.ui.search.PlaybackResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a series' chapters are: a movie has none to load. */
sealed interface EpisodesState {
    /** A movie: there is nothing to load. */
    data object None : EpisodesState
    data object Loading : EpisodesState
    data class Loaded(val chapters: List<GatewayEpisode>, val series: GatewaySeries?) : EpisodesState
    /** [setupPluginId] is set when the failure is "this plugin needs configuring": the page offers Configurar. */
    data class Failed(val message: String, val setupPluginId: String? = null) : EpisodesState
}

data class TitleInfoState(
    /** The card the page was opened with (or the sibling season switched to). Kept whole so the source can rebuild its gateway result exactly as the home does. */
    val item: CatalogItem,
    val info: TitleInfo,
    val episodes: EpisodesState,
    /** Where the title came from: names its library ids, playback and seasons model. */
    val source: TitleSource,
    /**
     * Every season the listing gave, this one included; the selector shows only when there is more
     * than one. Siblings the source named ([siblings]), else the seasons found in the chapter list.
     */
    val seasons: List<SeasonRef> = emptyList(),
    /**
     * True when [seasons] are sibling titles the listing named (`SeriesListing.seasons`): choosing
     * one opens that title. False when they were read from the chapter list: choosing one only
     * changes which chapters are shown.
     */
    val siblings: Boolean = false,
    /** Chapter-list seasons only: the season the person chose. Null until they choose (see [currentSeason]). */
    val selectedSeason: Int? = null,
    /** Playback progress by episode id. Read-only. */
    val progress: Map<String, PlaybackEntity> = emptyMap(),
    val downloads: Map<String, DownloadDisplayState> = emptyMap(),
    /** A play is being prepared: the button shows a spinner and ignores taps. */
    val resolving: Boolean = false,
) {
    val itemId: String get() = source.itemId(item)
    val movieEpisodeId: String get() = source.movieEpisodeId(item)
    fun chapterEpisodeId(chapter: GatewayEpisode): String = source.chapterEpisodeId(item, chapter)

    val primary: PrimaryAction?
        get() = primaryAction(
            info.kind,
            movieEpisodeId,
            { chapterEpisodeId(it) },
            (episodes as? EpisodesState.Loaded)?.chapters,
            progress,
        )

    val showSeasonSelector: Boolean get() = seasons.size > 1

    /** The loaded chapters, or null while they are not. */
    val chapters: List<GatewayEpisode>? get() = (episodes as? EpisodesState.Loaded)?.chapters

    /**
     * Chapter-list seasons only (null when the listing named siblings): the season being shown.
     * The person's choice; else the season of the chapter the main button offers; else the lowest one.
     */
    val currentSeason: Int?
        get() {
            if (siblings) return null
            return selectedSeason ?: primary?.season ?: chapters?.minOfOrNull { it.seasonOrOne }
        }

    /** The chapters the page lists: every one, or the current season's when one list holds several seasons. */
    val visibleChapters: List<GatewayEpisode>
        get() {
            val all = chapters.orEmpty()
            val season = currentSeason ?: return all
            return if (seasons.size > 1) all.filter { it.seasonOrOne == season } else all
        }

    /** A chapter-list season: the one shown. A sibling: this page's title, or the one the source flagged as current. */
    fun isCurrentSeason(season: SeasonRef): Boolean =
        currentSeason?.let { it == season.number } ?: (season.contentId == item.id || season.current)
}

/** One-shot things the screen reacts to. */
sealed interface TitleInfoEvent {
    data class OpenPlayer(val episodeId: String) : TitleInfoEvent
    data class Message(val text: String) : TitleInfoEvent

    /**
     * The outcome of a download request, for the Compose-only duplicate notice and toast.
     * [noticeDuplicates] is true for a movie (its toast says nothing about duplicates, the notice
     * does) and false for a chapter batch (its own message already covers "ya estaban guardados",
     * and showing both would say the same thing twice).
     */
    data class Downloaded(
        val outcomes: List<EnqueueOutcome>,
        val toast: String?,
        val noticeDuplicates: Boolean,
    ) : TitleInfoEvent
}

/**
 * State and actions of the info page, shared by the phone and TV screens and by every source.
 *
 * Opens instantly from the card that was tapped and then loads, in the background: the series'
 * listing (its chapters and, when the source keeps each season as its own title, its sibling
 * seasons; the only step whose failure the person sees) and, when TMDB knows this exact title, its
 * year, runtime, genres, cast and rating. Reads playback progress and
 * download state; **writes nothing**. Only [play] and the download functions reach code that saves
 * to the library, and they do it through the same paths the home and search already use.
 *
 * Everything that differs per source lives in [source]; every dependency is a function or a flow so
 * the whole thing is testable without an `AppGraph`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TitleInfoViewModel(
    initial: CatalogItem,
    private val content: ContentSource,
    private val source: TitleSource,
    observeProgress: (itemId: String) -> Flow<Map<String, PlaybackEntity>>,
    downloadStates: Flow<Map<String, DownloadDisplayState>>,
    private val tmdbInfo: suspend (type: String, tmdbId: Int) -> TmdbInfo?,
    private val tmdbMovieId: suspend (imdbId: String) -> Int?,
) : ViewModel() {

    val canDownload: Boolean get() = source.canDownload

    private val _state = MutableStateFlow(
        initial.toTitleInfo().copy(year = source.initialYear).let { info ->
            TitleInfoState(
                item = initial,
                info = info,
                episodes = if (info.kind == TitleKind.SERIES) EpisodesState.Loading else EpisodesState.None,
                source = source,
            )
        },
    )
    val state: StateFlow<TitleInfoState> = _state.asStateFlow()

    private val _events = Channel<TitleInfoEvent>(Channel.BUFFERED)
    val events: Flow<TitleInfoEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    /** The TMDB `(type, id)` whose data the page already shows; only touched on the main thread. */
    private var enrichedEntry: Pair<String, Int>? = null

    init {
        // Progress follows the item being shown: switching to a sibling season restarts the observation.
        viewModelScope.launch {
            _state.map { source.itemId(it.item) }.distinctUntilChanged()
                .flatMapLatest { itemId -> observeProgress(itemId) }
                .collect { rows -> _state.update { it.copy(progress = rows) } }
        }
        viewModelScope.launch {
            downloadStates.collect { states -> _state.update { it.copy(downloads = states) } }
        }
        loadEpisodes()
        if (_state.value.info.kind == TitleKind.MOVIE) {
            val item = _state.value.item
            viewModelScope.launch { enrich(item, null) }
        }
    }

    // ---- loading ----

    private fun loadEpisodes() {
        val item = _state.value.item
        if (_state.value.info.kind == TitleKind.MOVIE) return
        loadJob?.cancel()
        _state.update { it.copy(episodes = EpisodesState.Loading) }
        loadJob = viewModelScope.launch {
            val listing = try {
                content.seriesListing(item.ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    if (s.item.id != item.id) s
                    else s.copy(
                        episodes = EpisodesState.Failed(
                            e.message ?: "No se pudieron cargar los episodios",
                            setupPluginId = (e as? PluginSetupRequiredException)?.pluginId,
                        ),
                    )
                }
                return@launch
            }
            // The person may have switched season while this request was in flight.
            if (_state.value.item.id != item.id) return@launch
            val chapters = listing.episodes
            val series = listing.series
            // The portal answers a transient failure with a detail that has no chapters instead of
            // an error. An empty Loaded left the button on "Cargando…" for ever with no way out.
            if (chapters.isEmpty()) {
                _state.update { it.copy(episodes = EpisodesState.Failed("No hay episodios disponibles por ahora")) }
                return@launch
            }
            val siblings = listing.seasons.isNotEmpty()
            _state.update { s ->
                s.copy(
                    episodes = EpisodesState.Loaded(chapters, series),
                    // The siblings this listing named; else the ones already known (a sibling's own
                    // listing may name none, and the selector must not vanish on a switch); else the
                    // seasons found in the chapter list.
                    seasons = when {
                        siblings -> listing.seasons
                        s.siblings -> s.seasons
                        else -> chapters.map { it.seasonOrOne }.distinct().sorted().map { SeasonRef(it.toString(), it) }
                    },
                    siblings = siblings || s.siblings,
                    info = s.info.copy(
                        seasonNumber = series?.seasonNumber?.takeIf { it > 0 } ?: s.info.seasonNumber,
                        episodeCount = chapters.size.takeIf { it > 0 } ?: s.info.episodeCount,
                    ),
                )
            }
            launch { enrich(item, series) }
        }
    }

    /**
     * Best-effort: when TMDB has this exact title, adds what only it knows (see [withTmdb]). A series
     * is identified by the TMDB id its chapters call already resolved (or the source's hint); a
     * movie by an id the source published, never by its title. Any failure or miss leaves the page
     * as the source drew it.
     */
    private suspend fun enrich(item: CatalogItem, series: GatewaySeries?) {
        val entry = tmdbEntry(item, series) ?: return
        // Switching to a sibling season of the same show resolves to the same TMDB entry, and what
        // it added is kept by selectSeason: nothing new to ask.
        if (entry == enrichedEntry) return
        val (type, tmdbId) = entry
        val detail = attempt { tmdbInfo(type, tmdbId) } ?: return
        enrichedEntry = entry
        _state.update { s -> if (s.item.id != item.id) s else s.copy(info = s.info.withTmdb(detail)) }
    }

    /** The TMDB `(type, id)` this page is about, or null when it cannot be pinned down exactly. */
    private suspend fun tmdbEntry(item: CatalogItem, series: GatewaySeries?): Pair<String, Int>? {
        val hint = attempt { source.tmdbHint(item) } ?: TmdbHint()
        return when (_state.value.info.kind) {
            TitleKind.SERIES ->
                (series?.tmdbId?.takeIf { it > 0 } ?: hint.tmdbId.takeIf { it > 0 })?.let { "tv" to it }
            TitleKind.MOVIE ->
                (
                    hint.tmdbId.takeIf { it > 0 }
                        ?: hint.imdbId.takeIf { it.isNotBlank() }?.let { imdb -> attempt { tmdbMovieId(imdb) } }
                    )?.let { "movie" to it }
        }
    }

    fun retry() {
        if (_state.value.episodes is EpisodesState.Failed) loadEpisodes()
    }

    /**
     * Chooses a season. A sibling (the listing named it) swaps the page's item for the one the
     * source builds and asks for its chapters; a chapter-list season only changes which chapters
     * are shown, with no request. Everything else on the page (what TMDB added included) is kept:
     * it is the same series.
     */
    fun selectSeason(season: SeasonRef) {
        val current = _state.value
        if (!current.siblings) {
            _state.update { it.copy(selectedSeason = season.number) }
            return
        }
        if (season.contentId == current.item.id) return
        val next = source.siblingItem(current.item, season)
        _state.update { s ->
            s.copy(
                item = next,
                info = s.info.copy(
                    title = next.title.ifBlank { next.id },
                    seasonNumber = season.number.takeIf { it > 0 },
                    episodeCount = 0,
                ),
                episodes = EpisodesState.Loading,
                progress = emptyMap(),
                selectedSeason = null,
            )
        }
        loadEpisodes()
    }

    // ---- play ----

    /**
     * Plays the movie, or a chapter of the series ([chapter], or the one the main button offers).
     * Ignored while another play is resolving, and for a series whose chapters are not loaded: it
     * needs them to know what to play. A series goes through the season path with the chapters and
     * series this page already loaded (that path saves the whole series and must not re-request
     * them).
     */
    fun play(chapter: GatewayEpisode? = null) {
        val s = _state.value
        if (s.resolving) return
        val result = source.gatewayResult(s.item)
        val loaded = s.episodes as? EpisodesState.Loaded
        val chosen = chapter ?: s.primary?.let { p -> loaded?.chapters?.firstOrNull(p::plays) }
        if (s.info.kind == TitleKind.SERIES && chosen == null) return
        viewModelScope.launch {
            _state.update { it.copy(resolving = true) }
            val outcome = try {
                if (chosen != null && loaded != null) source.playSeason(result, loaded.chapters, chosen, loaded.series)
                else source.playMovie(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaybackResult.Failed(e.message ?: "No se pudo preparar la reproducción.")
            }
            _state.update { it.copy(resolving = false) }
            _events.send(
                when (outcome) {
                    is PlaybackResult.Ready -> TitleInfoEvent.OpenPlayer(outcome.episodeId)
                    is PlaybackResult.Failed -> TitleInfoEvent.Message(outcome.message)
                },
            )
        }
    }

    // ---- downloads ----

    fun downloadMovie() {
        val s = _state.value
        val actions = source.downloads ?: return
        if (s.info.kind != TitleKind.MOVIE) return
        viewModelScope.launch {
            val outcome = attempt { actions.enqueueMovie(source.gatewayResult(s.item)) }
            if (outcome == null) {
                _events.send(TitleInfoEvent.Message("No se pudo preparar la descarga."))
                return@launch
            }
            _events.send(
                TitleInfoEvent.Downloaded(
                    listOf(outcome), queuedDownloadToastText(outcome, s.info.title), noticeDuplicates = true,
                ),
            )
        }
    }

    fun downloadChapters(numbers: List<Int>) {
        val s = _state.value
        val actions = source.downloads ?: return
        val loaded = s.episodes as? EpisodesState.Loaded ?: return
        val chosen = loaded.chapters.filter { it.number in numbers }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            // The whole loaded list goes with the chosen ones: the save is the same one playing makes.
            val outcomes = attempt { actions.enqueueChapters(source.gatewayResult(s.item), loaded.chapters, chosen, loaded.series) } ?: emptyList()
            _events.send(
                TitleInfoEvent.Downloaded(outcomes, chapterEnqueueMessage(outcomes, chosen.size), noticeDuplicates = false),
            )
        }
    }

    fun downloadSeason() {
        val loaded = _state.value.episodes as? EpisodesState.Loaded ?: return
        downloadChapters(loaded.chapters.map { it.number })
    }

    /** Runs [block]; null on any failure except cancellation, which propagates. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** Wires the view model to the app's real content and TMDB. Used by both the phone and the TV screen. */
internal fun titleInfoViewModel(graph: AppGraph, item: CatalogItem, source: TitleSource): TitleInfoViewModel =
    TitleInfoViewModel(
        initial = item,
        content = graph.contentSource,
        source = source,
        observeProgress = { itemId -> graph.repository.observePlayback(itemId) },
        downloadStates = graph.repository.observeDownloadRows()
            .map { rows -> rows.associate { it.episodeId to ChapterDownloadState.of(it) } },
        tmdbInfo = { type, tmdbId -> graph.tmdbApi.info(type, tmdbId) },
        tmdbMovieId = { imdbId -> graph.tmdbApi.movieIdByImdb(imdbId) },
    )
