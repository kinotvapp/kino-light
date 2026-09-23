package com.arkiv.player.ui.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.gateway.toPlaySource
import com.arkiv.player.ui.catalog.PlaySource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How many search results get published at once. One by one makes Compose recompose the whole
 *  list per result, and with a few dozen the app hits ANR. */
private const val GATEWAY_BATCH_SIZE = 25

private const val GW = "ArkivGateway"

/**
 * ViewModel for the unified search wizard: QUERY phase (TMDB + AniList), REFINE step (optional
 * S/E), and RESULTS phase (search on Magis and Caracol at once, with S/E injected if given, or
 * just by name).
 */
class SearchViewModel(
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
    private val settings: SettingsStore,
    private val arkivApiClient: com.arkiv.player.data.gateway.ContentSource,
    private val searchHistory: com.arkiv.player.data.SearchHistoryRepo,
) : ViewModel() {

    private val _phase = MutableStateFlow(SearchPhase.QUERY)
    val phase: StateFlow<SearchPhase> = _phase.asStateFlow()

    private val _titleResults = MutableStateFlow<List<TitleCard>>(emptyList())
    val titleResults: StateFlow<List<TitleCard>> = _titleResults.asStateFlow()

    private val _loadingTitles = MutableStateFlow(false)
    val loadingTitles: StateFlow<Boolean> = _loadingTitles.asStateFlow()

    private val _selected = MutableStateFlow<TitleCard?>(null)
    val selected: StateFlow<TitleCard?> = _selected.asStateFlow()

    /**
     * Whether what's being searched came from typing text rather than picking a catalog card.
     *
     * Matters when SAVING: with a real card, TMDB's title and poster are the best metadata there
     * is; with free text, the query isn't metadata for anything --"dragon ball 137" isn't the name
     * of anything-- and what fits is each source's own name.
     */
    private val _searchedByText = MutableStateFlow(false)
    val searchedByText: StateFlow<Boolean> = _searchedByText.asStateFlow()

    // --- RESULTS phase: Magis and Caracol results for the chosen card ---
    private val _sources = MutableStateFlow<List<PlaySource>>(emptyList())
    val sources: StateFlow<List<PlaySource>> = _sources.asStateFlow()

    /** Which sources are still searching in the current source search (see [SearchingSources]). */
    private val _searchingSources = MutableStateFlow(SearchingSources())
    val searchingSources: StateFlow<SearchingSources> = _searchingSources.asStateFlow()

    /**
     * How many source searches were started. The one that gets canceled because another one just
     * like it started goes through the end of its coroutine (the `runCatching` in
     * [runSourceSearch] catches the cancellation): this keeps it from turning off the indicators
     * of the one that replaced it.
     */
    private var sourceSearchCount = 0

    /**
     * Which sources responded and which went down in the current source search; cleared at the
     * start of each one. Read by both the phone's and the TV's results to show a source's error
     * without covering up what the others brought (see [SourcesState]).
     */
    private val _sourcesState = MutableStateFlow(SourcesState())
    val sourcesState: StateFlow<SourcesState> = _sourcesState.asStateFlow()

    private val _refineSeason = MutableStateFlow<Int?>(null)
    val refineSeason: StateFlow<Int?> = _refineSeason.asStateFlow()

    private val _refineEpisode = MutableStateFlow<Int?>(null)
    val refineEpisode: StateFlow<Int?> = _refineEpisode.asStateFlow()

    private val _detail = MutableStateFlow<TmdbDetail?>(null)
    val detail: StateFlow<TmdbDetail?> = _detail.asStateFlow()

    private val _animeShow = MutableStateFlow<AnimeShow?>(null)
    val animeShow: StateFlow<AnimeShow?> = _animeShow.asStateFlow()

    private var searchJob: Job? = null
    private var sourceJob: Job? = null

    // --- search history -------------------------------------------
    // Recorded by the ViewModel, not the screen: that way it doesn't matter who triggers the
    // search, and there's a single place to look. The TV uses the same ViewModel and that's why it
    // also records here; its screen shows its own history (kind "tv"), which is a different list.
    val recentQueries: StateFlow<List<String>> = searchHistory.queries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recentTitles: StateFlow<List<com.arkiv.player.data.RecentTitle>> = searchHistory.titles
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Best-effort: if Room fails, the search still works. History never breaks searching.
    private fun inHistory(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }

    fun forgetQuery(q: String) = inHistory { searchHistory.removeQuery(q) }
    fun forgetTitle(t: com.arkiv.player.data.RecentTitle) = inHistory { searchHistory.removeTitle(t) }
    fun clearHistory() = inHistory { searchHistory.clear() }

    /** Launches the QUERY-phase search: TMDB + anime (title cards). */
    fun search(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) {
            _titleResults.value = emptyList()
            _loadingTitles.value = false
            return
        }
        inHistory { searchHistory.addQuery(q) }
        searchJob = viewModelScope.launch {
            _loadingTitles.value = true

            var tmdbCards: List<TitleCard> = emptyList()
            var animeCards: List<TitleCard> = emptyList()
            var tmdbDone = false
            var animeDone = false

            // The two sources overlap a lot (all the anime that's also on TMDB), and in the grid
            // that's two cards with the same name. Cleaned up on publishing, the single point both
            // batches pass through.
            fun publishTitles() { _titleResults.value = withoutDuplicates(tmdbCards + animeCards) }

            val tmdbJob = launch {
                tmdbCards = runCatching { tmdbApi.searchMulti(q) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                tmdbDone = true
                publishTitles()
                if (animeDone) _loadingTitles.value = false
            }
            val animeJob = launch {
                animeCards = runCatching { aniListApi.browse(1, "SEARCH_MATCH", q, null) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                animeDone = true
                publishTitles()
                if (tmdbDone) _loadingTitles.value = false
            }

            tmdbJob.join()
            animeJob.join()
        }
    }

    /** Entry from the home: starts already on a title, skipping the typing phase. */
    fun startFromShortcut(kind: String, tmdbId: Int?, anilistId: Long?) {
        if (selected.value != null) return   // already started (don't repeat on recomposition)
        viewModelScope.launch {
            val card = when {
                kind == "anime" && anilistId != null ->
                    runCatching { aniListApi.details(anilistId) }.getOrNull()?.toTitleCard()
                tmdbId != null -> {
                    val type = if (kind == "movie") "movie" else "tv"
                    runCatching { tmdbApi.detail(type, tmdbId) }.getOrNull()?.let { d ->
                        TitleCard(
                            kind = if (kind == "movie") "movie" else "series",
                            tmdbId = d.id, anilistId = null, title = d.title,
                            posterUrl = d.posterUrl, year = d.year, overview = d.overview,
                        )
                    }
                }
                else -> null
            } ?: return@launch
            pickTitle(card)   // movie -> RESULTS; series/anime -> REFINE
        }
    }

    /** Picks a card: movies go straight to RESULTS; series/anime move to REFINE. */
    fun pickTitle(card: TitleCard) {
        inHistory { searchHistory.addTitle(card.toRecent()) }
        _selected.value = card
        _searchedByText.value = false
        if (card.kind == "movie") {
            runSourceSearch(null, null)
        } else {
            _phase.value = SearchPhase.REFINE
        }
    }

    /**
     * Searches sources by raw text, without going through the catalog (the TV's "Ir" button):
     * useful when you remember a piece of the name and not the exact title TMDB has it under. The
     * card is built by [freeTextCard]; from there on it's the same search as always, so the
     * source list, packs, and playback don't change at all.
     *
     * Does NOT go to the title history: a card with no poster or ids would clutter the phone's
     * "recientes" row. The caller does record the query, in whichever text history fits.
     */
    fun searchSourcesByText(q: String) {
        val card = freeTextCard(q) ?: return
        _selected.value = card
        _searchedByText.value = true
        // Metadata from the previous search: `runSourceSearch` clears `_detail` on its own (the
        // card has no tmdbId), but `_animeShow` is only touched in the anime branch -- and if one
        // from a previously searched anime was left, the sources screen would show ITS title
        // instead of the typed text.
        _animeShow.value = null
        runSourceSearch(null, null)
    }

    /**
     * Search on Magis and Caracol for the chosen card: if season/episode come in, they get
     * injected into the search (a specific chapter); if not, it's searched by name only.
     * Progressive: each source adds results as soon as it has them.
     */
    fun runSourceSearch(season: Int?, episode: Int?) {
        val card = _selected.value ?: return
        sourceJob?.cancel()
        _phase.value = SearchPhase.RESULTS
        _sources.value = emptyList()
        _sourcesState.value = SourcesState()
        val thisSearch = ++sourceSearchCount
        _searchingSources.value = SearchingSources.starting()
        // Only while this is still the current search: see [sourceSearchCount].
        fun updateSearching(change: (SearchingSources) -> SearchingSources) {
            if (thisSearch == sourceSearchCount) _searchingSources.value = change(_searchingSources.value)
        }
        _refineSeason.value = season
        _refineEpisode.value = episode

        sourceJob = viewModelScope.launch {
            var d: TmdbDetail? = null

            if (card.kind == "anime") {
                val show = card.anilistId?.let { id -> runCatching { aniListApi.details(id) }.getOrNull() }
                _animeShow.value = show
            } else {
                val tmdbType = if (card.kind == "movie") "movie" else "tv"
                d = card.tmdbId?.let { id -> runCatching { tmdbApi.detail(tmdbType, id) }.getOrNull() }
                _detail.value = d
            }

            fun append(new: List<PlaySource>) { _sources.value = _sources.value + new }

            // `arkivApiClient` is the composite source (`AppGraph.contentSource`: Magis and
            // Caracol, each straight to its own API). This used to sit behind a flag (`useGateway`)
            // so it could be turned off without publishing an APK and fall back "to the old path":
            // there's no more old path or flag -- nobody had a way to turn it off.
            launch {
                runCatching {
                    val ctx = com.arkiv.player.data.gateway.GatewaySearchQuery(
                        q = card.title,
                        type = when (card.kind) {
                            "movie" -> "movie"
                            "anime" -> "anime"
                            else -> "tv"
                        },
                        season = season ?: 0,
                        episode = episode ?: 0,
                        tmdbId = card.tmdbId ?: 0,
                    )
                    // Results accumulate and get published IN A BATCH. Publishing one at a time
                    // fires a recomposition per result: with 20 from magis on top of 50+ sources
                    // already visible, the UI chokes measuring text and the app hits ANR.
                    val batch = mutableListOf<PlaySource>()
                    fun flushBatch() {
                        if (batch.isEmpty()) return
                        append(batch.toList())
                        batch.clear()
                    }
                    arkivApiClient.search(ctx).collect { ev ->
                        when (ev) {
                            is com.arkiv.player.data.gateway.SearchEvent.ResultEvent -> {
                                ev.item.toPlaySource()?.let { batch += it }
                                if (batch.size >= GATEWAY_BATCH_SIZE) flushBatch()
                            }
                            is com.arkiv.player.data.gateway.SearchEvent.SourceError -> {
                                // The technical detail goes to the log; the on-screen Caracol line
                                // is written by `CaracolFailure` (see `downSourceNotices`).
                                Log.w(GW, "source ${ev.source} failed: ${ev.error} (delivered ${ev.count})", ev.cause)
                                _sourcesState.value = _sourcesState.value.withFailure(ev.source, ev.error, ev.cause)
                                // Its "Buscando…" turns off right away, without waiting for the other sources.
                                updateSearching { it.sourceFinished(ev.source) }
                                flushBatch()
                            }
                            is com.arkiv.player.data.gateway.SearchEvent.SourceDone -> {
                                Log.w(GW, "source ${ev.source}: ${ev.count} in ${ev.ms}ms")
                                _sourcesState.value = _sourcesState.value.withResponse(ev.source)
                                updateSearching { it.sourceFinished(ev.source) }
                                flushBatch()
                            }
                            else -> Unit
                        }
                    }
                    flushBatch()
                }.onFailure {
                    android.util.Log.w("ArkivGateway", "the gateway failed: ${it.message}")
                }
                updateSearching { it.allFinished() }
            }
        }
    }

    /** Goes back one step: from RESULTS to REFINE (or QUERY if the card was a movie), from REFINE to QUERY. */
    fun back() {
        when (_phase.value) {
            SearchPhase.RESULTS -> {
                sourceJob?.cancel()
                _phase.value = if (_selected.value?.kind == "movie") SearchPhase.QUERY else SearchPhase.REFINE
            }
            SearchPhase.REFINE -> _phase.value = SearchPhase.QUERY
            SearchPhase.QUERY -> Unit
        }
        if (_phase.value == SearchPhase.QUERY) {
            _selected.value = null
            _searchedByText.value = false
        }
    }
}
