package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Border
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.catalog.TmdbEpisode
import com.arkiv.player.data.catalog.TmdbSeason
import com.arkiv.player.data.db.SearchHistoryEntity
import com.arkiv.player.ui.catalog.ChaptersBySeason
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.isSeries
import com.arkiv.player.ui.home.buildRowSpecs
import com.arkiv.player.ui.home.matchCategoryRow
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPhase
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.search.SearchViewModel
import com.arkiv.player.ui.search.SourceTab
import com.arkiv.player.ui.search.TitleCard
import com.arkiv.player.ui.search.countsByTab
import com.arkiv.player.ui.search.visibleRows
import com.arkiv.player.ui.search.SourcesState
import com.arkiv.player.ui.search.SearchingSources
import com.arkiv.player.ui.search.downSourceNotices
import com.arkiv.player.ui.search.emptyTabText
import com.arkiv.player.ui.search.noSourcesText
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** TV search history's kind (separate from the phone's catalog). */
private const val SEARCH_HISTORY_KIND = "tv"

/**
 * TV's search box: two remote-navigable columns — on-screen keyboard on the left, and on the
 * right the recent searches (before searching) or the title grid (TMDB/anime). Nothing fires on
 * typing: with the remote, each letter cost a network round trip.
 *
 * Below the keyboard there are two buttons. "Autocompletar" brings the catalog's title grid,
 * which works as suggestions: picking a card lets you write its name in the search box (and edit
 * it) instead of starting a search. "Buscar" sends the text —autocompleted or typed by hand—
 * straight to the sources, without tying it to the catalog's exact title.
 *
 * REFINE adds the visual season/chapter selector; RESULTS shows the sources (packs first) with
 * immediate playback, and the chapter list when a pack is chosen.
 */
@Composable
fun TvSearchScreen(
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    onBrowseRow: ((rowId: String, title: String) -> Unit)? = null,
    shortcutKind: String? = null,
    shortcutTmdbId: Int? = null,
    shortcutAnilistId: Long? = null,
) {
    val graph = rememberGraph()
    val fixedRows = remember { buildRowSpecs(emptyList(), emptyList(), emptyList()) }
    val vm: SearchViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    graph.tmdbApi, graph.aniListApi,
                    graph.settings, graph.contentSource,
                    graph.searchHistory,
                )
            }
        },
    )
    val phase by vm.phase.collectAsStateWithLifecycle()
    val titleResults by vm.titleResults.collectAsStateWithLifecycle()
    val loadingTitles by vm.loadingTitles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val vmDetail by vm.detail.collectAsStateWithLifecycle()
    val vmAnimeShow by vm.animeShow.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val searchingSources by vm.searchingSources.collectAsStateWithLifecycle()
    val sourcesState by vm.sourcesState.collectAsStateWithLifecycle()
    val refineSeason by vm.refineSeason.collectAsStateWithLifecycle()
    val refineEpisode by vm.refineEpisode.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf("") }

    // Playback/saving of the source chosen in RESULTS: reuses SearchPlayback (Task 2) exactly like
    // the phone does, to avoid duplicating the resolve/save logic. `packFor` is the "picked a pack"
    // sub-state within the same RESULTS phase (chapter list instead of source list).
    val scope = rememberCoroutineScope()
    val playback = remember { SearchPlayback(graph) }
    // Tapped card that hasn't chosen how to watch yet (whole thing or by season). null = no dialog.
    var askModeFor by remember { mutableStateOf<TitleCard?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    // Chosen Magis season: a series result from the portal IS a whole season, so it opens the
    // chapter list instead of playing the first one.
    var magisSeasonFor by remember { mutableStateOf<com.arkiv.player.data.gateway.GatewayResult?>(null) }
    // Chosen Caracol series: same as Magis, opens its chapters. A SEPARATE state on purpose: its
    // chapter list only calls `playback.playDituSeason`, so a Caracol chapter never falls into
    // Magis's save path.
    var dituSeasonFor by remember { mutableStateOf<com.arkiv.player.data.gateway.GatewayResult?>(null) }

    // The chosen card's "enriched" metadata, to save the real title/poster — same criterion as
    // SearchScreen (phone).
    val resultTitle = vmDetail?.title ?: vmAnimeShow?.title ?: selected?.title ?: ""
    val resultPoster = vmDetail?.posterUrl ?: vmAnimeShow?.posterUrl ?: selected?.posterUrl ?: ""

    fun applyResult(result: PlaybackResult) {
        preparing = false
        when (result) {
            is PlaybackResult.Ready -> onPlay(result.episodeId)
            is PlaybackResult.Failed -> playError = result.message
        }
    }

    fun playMagisResult(r: com.arkiv.player.data.gateway.GatewayResult) {
        preparing = true; playError = null
        scope.launch { applyResult(playback.playMagis(r)) }
    }

    fun playDituResult(r: com.arkiv.player.data.gateway.GatewayResult) {
        preparing = true; playError = null
        scope.launch { applyResult(playback.playDitu(r)) }
    }

    // Saves a whole Magis season. Chapter by chapter, same as the phone: each one is a separate
    // file on the CDN and the queue already groups them by series in Descargas.
    fun saveMagisSeason(
        season: com.arkiv.player.data.gateway.GatewayResult,
        chapters: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        // Same as on the phone: the series travels along in the save too, because saving rewrites
        // the whole episode row. See `SearchPlayback.magisEpisodeIdFor`.
        series: com.arkiv.player.data.gateway.GatewaySeries?,
    ) {
        preparing = true; playError = null
        scope.launch {
            var queued = 0
            for (chapter in chapters) {
                val epId = playback.magisEpisodeIdFor(season, chapter, series) ?: continue
                if (graph.localDownloads.enqueue(epId, "magis") ==
                    com.arkiv.player.data.local.EnqueueOutcome.QUEUED
                ) queued++
            }
            preparing = false
            magisSeasonFor = null
            playError = when {
                queued == 0 -> "Esos capítulos ya estaban guardados."
                queued == chapters.size -> null
                else -> "Se encolaron $queued de ${chapters.size} (el resto ya estaba)."
            }
        }
    }

    fun playResult(source: PlaySource) = when (source) {
        is PlaySource.Magis ->
            if (source.result.extra["program_type"] in com.arkiv.player.data.gateway.MAGIS_SERIES) {
                magisSeasonFor = source.result
            } else {
                playMagisResult(source.result)
            }
        is PlaySource.Ditu ->
            if (source.isSeries()) {
                dituSeasonFor = source.result
            } else {
                playDituResult(source.result)
            }
    }

    // Search does NOT fire on every keystroke: with the remote, each letter cost a full network
    // round-trip (TMDB + AniList) that was almost always discarded. Search happens on the button.
    // `searched` distinguishes "hasn't searched anything yet" (we show recents) from "searched and
    // there was nothing".
    var searched by remember { mutableStateOf(false) }
    // Goes up on every search run. It's the key that resets the grid to the top: without this, a
    // lazy list keeps the previous search's scroll and the new one shows up starting halfway down,
    // with the first cards off screen.
    var searchNumber by remember { mutableStateOf(0) }
    var recents by remember { mutableStateOf(emptyList<String>()) }
    val historyDao = remember { graph.database.searchHistoryDao() }

    suspend fun refreshRecents() {
        recents = runCatching { historyDao.recent(SEARCH_HISTORY_KIND, 12).map { it.query } }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { refreshRecents() }

    /**
     * Goes back to the recents screen without leaving the search box.
     *
     * This used to be a dead end: once something was searched there was no way back to the recents
     * list. Deleting all the text didn't work either — the button turned off and the results stayed
     * on screen.
     */
    fun newSearch() {
        text = ""
        searched = false
        vm.search("")
        scope.launch { refreshRecents() }
    }

    fun recordQuery(query: String) {
        scope.launch {
            runCatching {
                historyDao.upsert(SearchHistoryEntity(query, SEARCH_HISTORY_KIND, System.currentTimeMillis()))
            }
            refreshRecents()
        }
    }

    fun searchTitles(q: String) {
        val query = q.trim()
        if (query.isBlank()) return
        val match = if (onBrowseRow != null) matchCategoryRow(query, fixedRows) else null
        if (match != null) { onBrowseRow?.invoke(match.id, match.title); return }
        text = query
        searched = true
        searchNumber++
        vm.search(query)
        recordQuery(query)
    }

    fun searchSources() {
        val query = text.trim()
        if (query.isBlank()) return
        val match = if (onBrowseRow != null) matchCategoryRow(query, fixedRows) else null
        if (match != null) { onBrowseRow?.invoke(match.id, match.title); return }
        text = query
        vm.searchSourcesByText(query)
        recordQuery(query)
    }

    /**
     * "Buscar" button: sends the text as-is to the sources, without going through the catalog entry.
     *
     * The card path ties the search to TMDB's EXACT title; here it's whatever's in the search box,
     * whether it came from a suggestion or the keyboard.
     *
     * Deliberately doesn't touch `searched`: the right column stays as it was, so coming back from
     * the sources doesn't leave the screen on "Sin resultados" for a title search that never ran.
     */

    /**
     * A suggestion's "Usar este nombre": writes the card's title in the search box and leaves focus
     * on "Buscar", the only thing left to do. Without this, focus stays on the grid and you have to
     * cross the whole column with the D-pad to finish the search.
     */
    val searchSourcesFocus = remember { FocusRequester() }
    fun useName(name: String) {
        text = name
        scope.launch {
            delay(150)
            runCatching { searchSourcesFocus.requestFocus() }
        }
    }

    // Shortcut from the home: enters already positioned on a title (same pattern as the phone).
    LaunchedEffect(shortcutKind, shortcutTmdbId, shortcutAnilistId) {
        val k = shortcutKind ?: return@LaunchedEffect
        vm.startFromShortcut(k, shortcutTmdbId, shortcutAnilistId)
    }

    // Initial focus on the keyboard's first key. Keyed on `phase` (not `Unit`): REFINE/RESULTS/
    // PACK refocus themselves on entry, but going back to QUERY with vm.back() destroys the node
    // that held focus, and if this only ran once on entering the screen, nothing would bring it
    // back — the D-pad would end up "dead". Repeating the effect every time QUERY is returned to
    // avoids that.
    val firstKeyFocus = remember { FocusRequester() }
    LaunchedEffect(phase) {
        if (phase == SearchPhase.QUERY) {
            delay(200)
            runCatching { firstKeyFocus.requestFocus() }
        }
    }

    // In REFINE/RESULTS, back steps one phase back within the wizard; in the titles phase, back
    // exits the screen. With a series' chapters open inside RESULTS (from Magis or Caracol), back
    // goes to the source list first (doesn't exit the phase).
    BackHandler {
        when {
            phase == SearchPhase.RESULTS && magisSeasonFor != null -> magisSeasonFor = null
            phase == SearchPhase.RESULTS && dituSeasonFor != null -> dituSeasonFor = null
            phase != SearchPhase.QUERY -> vm.back()
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        when (phase) {
            SearchPhase.QUERY -> Row(Modifier.fillMaxSize()) {
                Column(
                    // 380dp: with 24dp of padding on each side, ~332 usable remain, so the 6 keys
                    // per row come out at ~48dp (comfortable to see from a distance) without clipping.
                    modifier = Modifier.fillMaxHeight().width(380.dp).padding(24.dp),
                ) {
                    Text(
                        text.ifBlank { "Buscar…" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (text.isBlank()) ArkivTextSecondary else androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    TvKeyboardWithNative(
                        text = text,
                        // Deleting down to empty goes back to recents. It's the gesture that already
                        // existed (⌫) and that until now led nowhere.
                        onTextChange = {
                            text = it
                            if (it.isBlank() && searched) newSearch()
                        },
                        firstKeyFocus = firstKeyFocus,
                    )
                    Spacer(Modifier.height(16.dp))
                    // Left to right, the flow's order: "Autocompletar" brings the catalog's
                    // suggestions and "Buscar" finishes with whatever text is left. Half and half
                    // because both get used, and they're navigated between with left/right.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            onClick = { searchTitles(text) },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = arkivTvSurfaceColors(),
                            border = arkivTvSurfaceBorder(),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Autocompletar", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            }
                        }
                        Surface(
                            onClick = { searchSources() },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.weight(1f).height(52.dp).focusRequester(searchSourcesFocus),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = arkivTvSurfaceColors(),
                            border = arkivTvSurfaceBorder(),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Buscar", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            }
                        }
                    }
                    // Doesn't depend on deleting the text letter by letter: with the remote that's
                    // ten clicks. Only shows up once there's something to discard.
                    if (searched) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            onClick = { newSearch() },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = arkivTvSurfaceColors(),
                            border = arkivTvSurfaceBorder(),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Nueva búsqueda", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxSize().padding(top = 24.dp, end = 24.dp)) {
                    // Before searching, this space (as long as it isn't empty) shows the last thing
                    // searched: with the remote, going back to a previous search is much cheaper
                    // than retyping it letter by letter.
                    if (!searched && recents.isNotEmpty()) {
                        Text(
                            "Búsquedas recientes",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        LazyColumn(
                            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp, end = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(recents, key = { it }) { q ->
                                Surface(
                                    onClick = { searchTitles(q) },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                    colors = arkivTvSurfaceColors(),
                                    border = arkivTvSurfaceBorder(),
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) { Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1) }
                                }
                            }
                        }
                        return@Row
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                        Text("Películas y series", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        if (loadingTitles) {
                            Spacer(Modifier.width(8.dp))
                            Text("Buscando…", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
                        }
                    }
                    if (titleResults.isEmpty() && !loadingTitles && searched) {
                        Text("Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    val titlesGrid = rememberLazyGridState()
                    // Results arrive in two batches and the ViewModel publishes `tmdb + anime`: if
                    // anime arrives first, the TMDB batch gets INSERTED ABOVE. With keys, the grid
                    // anchors to what was already being viewed and the new stuff ends up off screen,
                    // above — it looks the same as if it had scrolled on its own. As long as focus
                    // hasn't moved to the grid, we keep it at the top.
                    var gridTouched by remember(searchNumber) { mutableStateOf(false) }
                    LaunchedEffect(searchNumber, titleResults) {
                        if (!gridTouched) titlesGrid.scrollToItem(0)
                    }
                    LazyVerticalGrid(
                        state = titlesGrid,
                        columns = GridCells.Fixed(5),
                        // Room all around so the focus zoom (1.1x) doesn't clip against the grid's
                        // edges or against neighboring cards.
                        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp, end = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(titleResults, key = { "${it.kind}-${it.tmdbId}-${it.anilistId}-${it.title}" }) { card ->
                            TvPosterCard(
                                title = card.title,
                                posterUrl = card.posterUrl,
                                cardHeight = 180.dp,
                                onFocus = { gridTouched = true },
                                // No card ever starts a search on its own: movies ask too. The grid
                                // is both the catalog and the search box's autocompleter, and which
                                // of the two you want can't be guessed.
                                onClick = { askModeFor = card },
                            )
                        }
                    }
                }
            }

            // Task 5: visual season/chapter selector. Task 6 replaces RESULTS' placeholder.
            SearchPhase.REFINE -> selected?.let { card ->
                TvRefineContent(
                    card = card,
                    vmDetail = vmDetail,
                    vmAnimeShow = vmAnimeShow,
                    tmdbApi = graph.tmdbApi,
                    aniListApi = graph.aniListApi,
                    onAllSeries = { vm.runSourceSearch(null, null) },
                    onPickEpisode = { season, episode -> vm.runSourceSearch(season, episode) },
                )
            }
            // Source list with immediate playback on picking one; a Magis season or a Caracol
            // series opens TvMagisSeasonContent (chapter list) instead of playing.
            SearchPhase.RESULTS -> {
                val currentMagis = magisSeasonFor
                val currentDitu = dituSeasonFor
                if (currentMagis != null) {
                    TvMagisSeasonContent(
                        season = currentMagis,
                        client = graph.contentSource,
                        posterUrl = resultPoster,
                        preparing = preparing,
                        onPlayOne = { chapters, chapter, series ->
                            magisSeasonFor = null
                            preparing = true; playError = null
                            scope.launch {
                                applyResult(playback.playMagisSeason(currentMagis, chapters, chapter, series))
                            }
                        },
                        onSaveAll = { chapters, series -> saveMagisSeason(currentMagis, chapters, series) },
                    )
                } else if (currentDitu != null) {
                    TvCaracolChapters(
                        series = currentDitu,
                        posterUrl = currentDitu.extra["poster"].orEmpty().ifBlank { resultPoster },
                        preparing = preparing,
                        onChoose = { save ->
                            dituSeasonFor = null
                            preparing = true; playError = null
                            scope.launch { applyResult(save()) }
                        },
                    )
                } else {
                    TvResultsContent(
                        title = resultTitle,
                        posterUrl = resultPoster,
                        season = refineSeason,
                        episode = refineEpisode,
                        sources = sources,
                        searchingSources = searchingSources,
                        sourcesState = sourcesState,
                        preparing = preparing,
                        playError = playError,
                        onSelect = { source -> playResult(source) },
                    )
                }
            }
        }
    }

    askModeFor?.let { card ->
        TvWhatToDoWithCardDialog(
            title = card.title,
            isSeries = card.kind != "movie",
            onUseName = {
                askModeFor = null
                useName(card.title)
            },
            onFullSeries = {
                askModeFor = null
                // pickTitle leaves the card as `selected` (and saves it in history); only then can
                // runSourceSearch look up its sources. With no S/E, packs get surfaced.
                vm.pickTitle(card)
                vm.runSourceSearch(null, null)
            },
            // A movie has no seasons to choose: pickTitle sends it straight to RESULTS.
            onBySeason = {
                askModeFor = null
                vm.pickTitle(card)
            },
            onDismiss = { askModeFor = null },
        )
    }
}

/**
 * What to do with the just-chosen card: use its name as the search box's autocomplete, or search
 * its sources through the catalog entry.
 *
 * The grid does two jobs at once —it's the catalog and it's the autocompleter— and which of the
 * two is wanted can't be guessed from the click, so it asks. "Usar este nombre" goes first and
 * with focus because it's the grid's reason for being when someone only remembers a piece of the
 * name: TMDB completes the title and from there the search continues by text, without tying
 * itself to the `tmdb_id` (which is exactly what sometimes finds nothing on the mirror).
 *
 * For series the usual two entries are kept: the whole series (where the packs show up) and the
 * season/chapter selector. A movie has nothing to choose, so its only catalog path is "Ver fuentes".
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvWhatToDoWithCardDialog(
    title: String,
    isSeries: Boolean,
    onUseName: () -> Unit,
    onFullSeries: () -> Unit,
    onBySeason: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { first.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.width(560.dp).clip(RoundedCornerShape(16.dp))
                .background(ArkivSurfaceHigh).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2)
            Text(
                "¿Qué quieres hacer?",
                style = MaterialTheme.typography.bodyLarge,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Button(
                onClick = onUseName,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            ) { Text("Usar este nombre") }
            Text(
                "Lo escribe en el buscador para que lo edites si quieres, y con \"Buscar\" va tal cual a las fuentes.",
                style = MaterialTheme.typography.labelLarge,
                color = ArkivTextSecondary,
            )
            if (isSeries) {
                Button(
                    onClick = onFullSeries,
                    colors = arkivTvButtonColors(),
                    border = arkivTvButtonBorder(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ver serie completa") }
                Text(
                    "Busca la serie entera: es donde salen los packs de temporada.",
                    style = MaterialTheme.typography.labelLarge,
                    color = ArkivTextSecondary,
                )
                Button(
                    onClick = onBySeason,
                    colors = arkivTvButtonColors(),
                    border = arkivTvButtonBorder(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Buscar por temporada") }
                Text(
                    "Abre el selector de temporadas y capítulos.",
                    style = MaterialTheme.typography.labelLarge,
                    color = ArkivTextSecondary,
                )
            } else {
                Button(
                    onClick = onBySeason,
                    colors = arkivTvButtonColors(),
                    border = arkivTvButtonBorder(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ver fuentes") }
                Text(
                    "Busca por la ficha del catálogo, con su título exacto.",
                    style = MaterialTheme.typography.labelLarge,
                    color = ArkivTextSecondary,
                )
            }
        }
    }
}

/**
 * TV's REFINE phase: remote-navigable visual selector — seasons in a horizontal row (TMDB
 * series) or an episode list (anime), with "Toda la serie" always on top to jump straight to the
 * packs. No numeric keyboard: on the remote, typing a number is tedious, so everything gets
 * picked with focus/click.
 *
 * Reuses `vm.detail`/`vm.animeShow` when the ViewModel already loaded them (e.g. on coming back
 * from RESULTS with `back()`); if they're still empty —first time entering REFINE, because
 * [SearchViewModel.runSourceSearch] only fills them once the source search fires— they're
 * requested right here with `tmdbApi.detail`/`aniListApi.details` so as not to block the selector.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvRefineContent(
    card: TitleCard,
    vmDetail: TmdbDetail?,
    vmAnimeShow: AnimeShow?,
    tmdbApi: TmdbApi,
    aniListApi: AniListApi,
    onAllSeries: () -> Unit,
    onPickEpisode: (season: Int?, episode: Int) -> Unit,
) {
    var localDetail by remember(card) { mutableStateOf<TmdbDetail?>(null) }
    var localAnimeShow by remember(card) { mutableStateOf<AnimeShow?>(null) }

    val effectiveDetail = vmDetail?.takeIf { it.id == card.tmdbId } ?: localDetail
    val effectiveAnimeShow = vmAnimeShow?.takeIf { it.id == card.anilistId } ?: localAnimeShow

    // Only requests what the VM doesn't already have (avoids a refetch on returning from RESULTS with back()).
    LaunchedEffect(card.tmdbId, vmDetail) {
        val tmdbId = card.tmdbId ?: return@LaunchedEffect
        if (card.kind != "series") return@LaunchedEffect
        if (vmDetail?.id == tmdbId) return@LaunchedEffect
        localDetail = runCatching { tmdbApi.detail("tv", tmdbId) }.getOrNull()
    }
    LaunchedEffect(card.anilistId, vmAnimeShow) {
        val anilistId = card.anilistId ?: return@LaunchedEffect
        if (card.kind != "anime") return@LaunchedEffect
        if (vmAnimeShow?.id == anilistId) return@LaunchedEffect
        localAnimeShow = runCatching { aniListApi.details(anilistId) }.getOrNull()
    }

    var selectedSeason by remember(card) { mutableStateOf<Int?>(null) }
    var episodesBySeason by remember(card) { mutableStateOf<Map<Int, List<TmdbEpisode>>>(emptyMap()) }
    var loadingEpisodes by remember(card) { mutableStateOf(false) }

    // Preselects the first "real" season (skips specials = season 0) as soon as they're known.
    LaunchedEffect(effectiveDetail) {
        if (selectedSeason == null) {
            val seasons = effectiveDetail?.seasons.orEmpty()
            selectedSeason = seasons.firstOrNull { it.seasonNumber >= 1 }?.seasonNumber
                ?: seasons.firstOrNull()?.seasonNumber
        }
    }

    // Loads the focused/chosen season's chapters; caches per season to avoid repeating the fetch
    // when going back and forth between already-seen seasons.
    // `selectedSeason` changes with FOCUS (the chip's onFocus), so going through the seasons with
    // the D-pad restarts this effect once per chip. Two fixes here:
    //  - delay(250) at the start, BEFORE touching `loadingEpisodes` or the cache: if the user keeps
    //    scrubbing, every restart cancels the previous coroutine during the delay and it never gets
    //    to fire the TMDB fetch — avoids one call per chip.
    //  - `loadingEpisodes` only gets set to true AFTER the cache check, and the fetch goes in a
    //    try/finally: if the season was already cached, the flag never gets touched; if the fetch
    //    gets cancelled halfway (focus moved to another season), the finally sets it back to false
    //    all the same, so it never gets stuck on "Cargando…".
    LaunchedEffect(selectedSeason, card.tmdbId) {
        val season = selectedSeason ?: return@LaunchedEffect
        val tmdbId = card.tmdbId ?: return@LaunchedEffect
        delay(250)
        if (episodesBySeason.containsKey(season)) return@LaunchedEffect
        loadingEpisodes = true
        try {
            // `.orEmpty()`: this only paints the chapter list; a network failure looks the same as
            // an empty season and gets retried just by entering again.
            val eps = runCatching { tmdbApi.seasonEpisodes(tmdbId, season) }.getOrNull().orEmpty()
            episodesBySeason = episodesBySeason + (season to eps)
        } finally {
            loadingEpisodes = false
        }
    }

    // Initial focus on "Toda la serie" (Step 2 of the brief).
    val allSeriesFocus = remember(card) { FocusRequester() }
    LaunchedEffect(card) {
        delay(200)
        runCatching { allSeriesFocus.requestFocus() }
    }

    val seasons = effectiveDetail?.seasons.orEmpty()
    val currentEpisodes = selectedSeason?.let { episodesBySeason[it] }.orEmpty()
    val animeTotal = effectiveAnimeShow?.episodes ?: 0

    LazyColumn(
        // The margin goes INSIDE the list (contentPadding), not as external padding: on focus,
        // rows zoom (1.1x) and with the margin outside, the list clipped them against its own
        // edge. This way the zoom draws over that margin instead of getting cut off.
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row {
                Box(
                    modifier = Modifier.height(160.dp).width(160.dp * 2f / 3f)
                        .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
                ) {
                    if (card.posterUrl.isNotBlank()) {
                        AsyncImage(
                            model = card.posterUrl,
                            contentDescription = card.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(modifier = Modifier.padding(start = 20.dp).align(Alignment.CenterVertically)) {
                    Text(card.title, style = MaterialTheme.typography.headlineMedium, color = ArkivTextPrimary)
                    if (card.year.isNotBlank()) {
                        Text(
                            card.year,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArkivTextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = onAllSeries,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).focusRequester(allSeriesFocus),
            ) { Text("Toda la serie") }
        }

        when (card.kind) {
            "series" -> {
                if (seasons.isNotEmpty()) {
                    item {
                        Text(
                            "Temporadas",
                            style = MaterialTheme.typography.titleMedium,
                            color = ArkivTextPrimary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(seasons, key = { it.seasonNumber }) { season ->
                                TvSeasonChip(
                                    season = season,
                                    selected = season.seasonNumber == selectedSeason,
                                    onFocus = { selectedSeason = season.seasonNumber },
                                    onClick = { selectedSeason = season.seasonNumber },
                                )
                            }
                        }
                    }
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                        ) {
                            Text("Capítulos", style = MaterialTheme.typography.titleMedium, color = ArkivTextPrimary)
                            if (loadingEpisodes) {
                                Spacer(Modifier.width(8.dp))
                                Text("Cargando…", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
                            }
                        }
                    }
                    items(currentEpisodes, key = { it.episode }) { ep ->
                        TvRefineRow(
                            label = "E${ep.episode} · ${ep.name}",
                            onClick = { onPickEpisode(selectedSeason, ep.episode) },
                        )
                    }
                } else {
                    item {
                        Text(
                            if (effectiveDetail == null) "Cargando temporadas…" else "Sin temporadas disponibles.",
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
            "anime" -> {
                if (animeTotal > 0) {
                    item {
                        Text(
                            "Episodios",
                            style = MaterialTheme.typography.titleMedium,
                            color = ArkivTextPrimary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items((1..animeTotal).toList(), key = { it }) { n ->
                        TvRefineRow(
                            label = "Episodio $n",
                            onClick = { onPickEpisode(null, n) },
                        )
                    }
                } else {
                    item {
                        Text(
                            if (effectiveAnimeShow == null) "Cargando episodios…" else "Cantidad de episodios desconocida — usa \"Toda la serie\".",
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
            else -> Unit // "movie" doesn't reach REFINE: pickTitle() sends it straight to RESULTS.
        }
    }
}

/**
 * Row of chips by source ("Todo 12 · Magis 12"), equivalent to the phone app's
 * ([com.arkiv.player.ui.search.SourceTabRow]).
 *
 * With a single real source (Magis), the filter no longer separates anything, but it's kept in
 * case there's more than one source at once again.
 *
 * Both chips are always painted, even at 0: if they appeared and disappeared as results arrived,
 * focus would jump chips while the user navigates. For the same reason, a source that's still
 * searching shows a spinner instead of "0" — a premature zero reads as "there's nothing here"
 * when really it just hasn't finished yet.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSourceTabRow(
    selected: SourceTab,
    counts: Map<SourceTab, Int>,
    loading: Map<SourceTab, Boolean>,
    modifier: Modifier = Modifier,
    onSelect: (SourceTab) -> Unit,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SourceTab.entries.forEach { t ->
            val accent = when (t) {
                SourceTab.ALL -> androidx.compose.ui.graphics.Color.White
                SourceTab.MAGIS -> com.arkiv.player.ui.catalog.ArkivMagisBlue
                SourceTab.CARACOL -> com.arkiv.player.ui.catalog.ArkivCaracolVerde
            }
            val on = t == selected
            Surface(
                onClick = { onSelect(t) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    // The selected one tints with the source's color; focus always wins on
                    // contrast, which is what the user needs to see from the couch.
                    containerColor = if (on) accent.copy(alpha = 0.28f) else ArkivSurfaceHigh,
                    focusedContainerColor = accent.copy(alpha = 0.55f),
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color.White),
                    ),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        t.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                    if (loading[t] == true) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = androidx.compose.ui.graphics.Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        Text(
                            "${counts[t] ?: 0}",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (on) androidx.compose.ui.graphics.Color.White else ArkivTextSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** Season chip in the horizontal row ("T1", "T2"… or "Especiales" for season 0). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSeasonChip(
    season: TmdbSeason,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ArkivRed else ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color.White)),
        ),
    ) {
        Text(
            text = if (season.seasonNumber == 0) "Especiales" else "T${season.seasonNumber}",
            style = MaterialTheme.typography.titleSmall,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Navigable row for a selectable chapter/episode ("E3 · Nombre" or "Episodio 12"). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvRefineRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color.White)),
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

/**
 * TV's RESULTS phase: a SINGLE vertical list of Magis sources — unlike the phone, which groups
 * them into collapsible sections by type, here they all go together because the D-pad navigates a
 * single list better than jumping between sections. Picking a source plays it RIGHT AWAY
 * (SearchPlayback via onSelect, no "where to watch" dialog); a Magis season is handled by the
 * parent (TvSearchScreen) showing TvMagisSeasonContent in its place.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvResultsContent(
    title: String,
    posterUrl: String,
    season: Int?,
    episode: Int?,
    sources: List<PlaySource>,
    searchingSources: SearchingSources,
    sourcesState: SourcesState,
    preparing: Boolean,
    playError: String?,
    onSelect: (PlaySource) -> Unit,
) {
    // distinctBy(sourceKey) is belt-and-braces: the pipeline above should already arrive with no
    // duplicates, but this avoids a Compose crash from repeated keys if something slips through.
    val ordered = remember(sources) { sources.distinctBy { sourceKey(it) } }
    val anyLoading = searchingSources.any

    // Filter by source. The counters come from `ordered` (already deduplicated), not `sources`, so
    // the chip's number is exactly the count of rows that'll be seen on picking it.
    var tab by remember { mutableStateOf(SourceTab.ALL) }
    val counts = countsByTab(ordered)
    // Each tab spins while its source is still searching, and "Todo" while any is missing: see
    // [SearchingSources].
    val loadingOf = SourceTab.entries.associateWith { searchingSources.isSearching(it) }

    // Initial focus on the first source as soon as the first batch shows up (progressive: doesn't
    // steal focus back from the user when more results arrive later).
    val firstFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(ordered.isNotEmpty()) {
        if (ordered.isNotEmpty() && !focusedOnce) {
            focusedOnce = true
            delay(150)
            runCatching { firstFocus.requestFocus() }
        }
    }

    // A failed playback attempt leaves `preparing` at false but, since `focusedOnce` is already
    // true, the effect above doesn't fire again: the row stays disabled during `preparing`
    // (non-focusable Surface) and on re-enabling, nothing requests focus again — the user sees the
    // error and the D-pad doesn't respond. Refocusing here when a new error shows up fixes it.
    LaunchedEffect(playError) {
        if (playError != null && ordered.isNotEmpty()) {
            runCatching { firstFocus.requestFocus() }
        }
    }

    // On entering ANOTHER title's sources, the list has to start at the top, not where the
    // previous one left off. The key is the title + the chapter: that's what changes between
    // searches.
    val sourcesList = rememberLazyListState()
    LaunchedEffect(title, season, episode) { sourcesList.scrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = sourcesList,
            // The margin goes INSIDE the list (contentPadding), not as external padding: on focus,
            // rows zoom (1.1x) and with the margin outside, the list clipped them against its own
            // edge. This way the zoom draws over that margin instead of getting cut off.
            modifier = Modifier.fillMaxSize(),
            // No horizontal margin on the LIST: each item sets its own, and source rows set theirs
            // as the LazyRow's contentPadding, so cards scroll all the way to the screen's edge
            // instead of clipping against the list's margin.
            contentPadding = PaddingValues(vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                // Compact header: with horizontal rows below, every dp it takes here is one fewer
                // source visible without scrolling. Used to be a 140 dp poster and two lines.
                Row(modifier = Modifier.padding(horizontal = 48.dp)) {
                    Box(
                        modifier = Modifier.height(90.dp).width(90.dp * 2f / 3f)
                            .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
                    ) {
                        if (posterUrl.isNotBlank()) {
                            AsyncImage(
                                model = posterUrl,
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 20.dp).align(Alignment.CenterVertically)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = ArkivTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // A single line of context: chapter, how many sources there are, and
                        // whether it's still searching. The separate "Fuentes / Buscando…" got
                        // merged into it here.
                        val meta = buildString {
                            if (season != null && episode != null) append("T").append(season).append(" · E").append(episode)
                            if (ordered.isNotEmpty()) {
                                if (isNotEmpty()) append("  ·  ")
                                append(ordered.size).append(" fuentes")
                            }
                            if (anyLoading) {
                                if (isNotEmpty()) append("  ·  ")
                                append("buscando…")
                            }
                        }
                        if (meta.isNotBlank()) {
                            Text(
                                meta,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArkivTextSecondary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            if (playError != null) {
                item {
                    Text(
                        playError,
                        color = ArkivRed,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                    )
                }
            }

            item {
                TvSourceTabRow(
                    selected = tab,
                    counts = counts,
                    loading = loadingOf,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
                    onSelect = { tab = it },
                )
            }

            // One line per down source, whether or not there are results: doesn't cover what the
            // others brought.
            downSourceNotices(sourcesState, tab).forEach { notice ->
                item {
                    Text(
                        notice,
                        color = ArkivRed,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
                    )
                }
            }

            val rows = visibleRows(ordered, tab)

            if (ordered.isEmpty() && !anyLoading) {
                item {
                    Text(
                        noSourcesText(sourcesState),
                        color = ArkivTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    )
                }
            } else if (rows.isEmpty()) {
                // There are results, but not from this source. Without this notice the list stays
                // blank and it looks like the app hung, when really going back to "Todo" is
                // enough. If this tab's source is down, this doesn't show: its line above already
                // says so.
                emptyTabText(tab, loadingOf[tab] == true, sourcesState)?.let { empty ->
                    item {
                        Text(
                            empty,
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // One horizontal row per source. Initial focus goes to the FIRST row's first card: if
            // every row asked for focus, they'd steal it from each other as they arrive.
            rows.forEach { (source, sourceItems) ->
                tvSourceRow(
                    source = source,
                    items = sourceItems,
                    enabled = !preparing,
                    loading = loadingOf[source] == true,
                    firstCard = if (source == rows.first().first) firstFocus else null,
                    onPlay = { onSelect(it) },
                )
            }
        }

        if (preparing) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Text("Preparando…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Stable and UNIQUE key for the source list (avoids focus "jumps" as new results arrive, and
 *  avoids a Compose crash from duplicate keys in a lazy list).
 *  Magis: the portal's `content_id`, or the `ref` if it doesn't bring one. Caracol: its `ref`,
 *  already unique per content (`ditu1:<contentType>:<contentId>`). */
internal fun sourceKey(s: PlaySource): String = when (s) {
    is PlaySource.Magis -> "magis-${s.result.extra["content_id"] ?: s.result.ref}"
    is PlaySource.Ditu -> "ditu-${s.result.ref}"
}

/**
 * A Caracol series' chapters, to choose which one to watch. Opened by search and by Caracol's
 * section ([TvCaracolScreen]), and it's a single one on purpose: tapping a chapter always saves
 * via [SearchPlayback.playDituSeason] —the whole series, `ditu:` id, never Magis's save path— and
 * plays the tapped one. No "Guardar toda la temporada": there, saving means downloading to the
 * device, and Caracol doesn't download (Widevine, see `DownloadSource`). It enters the library on
 * playing.
 *
 * [onChoose] receives that save already built and runs it in the calling screen's scope: both
 * close this list on picking, so it can't run in one from inside here.
 */
@Composable
internal fun TvCaracolChapters(
    series: com.arkiv.player.data.gateway.GatewayResult,
    posterUrl: String,
    preparing: Boolean,
    onChoose: (save: suspend () -> PlaybackResult) -> Unit,
) {
    val graph = rememberGraph()
    val playback = remember { SearchPlayback(graph) }
    TvMagisSeasonContent(
        season = series,
        // The composed source: with a Caracol ref, `episodesWithSeries` reaches `DituSource`.
        client = graph.contentSource,
        posterUrl = posterUrl,
        preparing = preparing,
        // With the whole list the screen already loaded: all get saved, the tapped one plays.
        onPlayOne = { chapters, chapter, data ->
            onChoose { playback.playDituSeason(series, chapters, chapter, data) }
        },
        onSaveAll = null,
        label = "Caracol",
    )
}

/** Navigable row for a Magis or Caracol chapter ("E3 · Title"). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvMagisEpisodeRow(
    chapter: com.arkiv.player.data.gateway.GatewayEpisode,
    // "E3", or "T2 · E3" with several seasons: see [ChaptersBySeason].
    label: String = "E${chapter.number}",
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!chapter.still.isNullOrBlank()) {
                Box(
                    modifier = Modifier.height(56.dp).width(56.dp * 16f / 9f)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black),
                ) {
                    AsyncImage(
                        model = chapter.still,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                // The portal's number RULES: it identifies the chapter that's about to play, and
                // if the TMDB cross-reference drifted for this season, it's still the trustworthy
                // datum. The name goes alongside, never in its place. Priority: TMDB's title (the
                // real one) -> the portal's title (unless it just repeats the season's name, see
                // below) -> "Capítulo N" as the last resort.
                "$label  " + (
                    chapter.tmdbTitle?.takeIf { it.isNotBlank() }
                        ?: chapter.title.takeIf { it.isNotBlank() && it != chapter.number.toString() }
                        ?: "Capítulo ${chapter.number}"
                    ),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
