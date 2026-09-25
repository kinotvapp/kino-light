package com.arkiv.player.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.ui.gridColumns
import com.arkiv.player.ui.isLandscapeTablet
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.RecentTitle
import com.arkiv.player.data.local.DownloadSource
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.accent
import com.arkiv.player.ui.catalog.SourceRow
import com.arkiv.player.ui.catalog.posterFor
import com.arkiv.player.ui.catalog.SourceCard
import com.arkiv.player.ui.catalog.SourceSectionHeader
import com.arkiv.player.ui.catalog.ArkivMagisBlue
import com.arkiv.player.ui.catalog.ArkivCaracolVerde
import com.arkiv.player.ui.catalog.isSeries
import com.arkiv.player.ui.catalog.MetaChip
import com.arkiv.player.ui.home.buildRowSpecs
import com.arkiv.player.ui.home.matchCategoryRow
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/**
 * Unified search wizard: QUERY phase (search box + TMDB/anime cards), REFINE step (optional
 * season/chapter) and RESULTS phase (multi-source search in Magis and Caracol for the chosen
 * card, with S/E injected if given, or by name alone otherwise — the latter surfaces
 * whole-season/series packs).
 */
@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    onBrowseRow: ((rowId: String, title: String) -> Unit)? = null,
    shortcutKind: String? = null,
    shortcutTmdbId: Int? = null,
    shortcutAnilistId: Long? = null,
    /** A plain title to search on entry (Kinobot's suggestion chips): runs the query phase. */
    shortcutQuery: String? = null,
    /** "Ver más resultados" of a plugin whose search page carried a cursor. */
    onBrowsePlugin: ((com.arkiv.player.ui.plugin.PluginMoreTarget) -> Unit)? = null,
) {
    val graph = rememberGraph()
    // Fixed rows always available (no API): anime, cartelera, tendencias, series, etc.
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
    val sources by vm.sources.collectAsStateWithLifecycle()
    val searchingSources by vm.searchingSources.collectAsStateWithLifecycle()
    val sourcesState by vm.sourcesState.collectAsStateWithLifecycle()
    val pluginMore by vm.pluginMore.collectAsStateWithLifecycle()
    val refineSeason by vm.refineSeason.collectAsStateWithLifecycle()
    val refineEpisode by vm.refineEpisode.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val animeShow by vm.animeShow.collectAsStateWithLifecycle()
    val recentQueries by vm.recentQueries.collectAsStateWithLifecycle()
    val recentTitles by vm.recentTitles.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Notification permission (API 33+): asked when triggering a download (the local downloads
    // worker also notifies). See rememberPostNotificationsRequest.
    val askNotifications = com.arkiv.player.ui.offline.rememberPostNotificationsRequest()
    // Shows "you already have that downloaded" when the queue skips a movie download as a
    // duplicate: same helper the library uses (DetailScreen.saveEpisodesLocally).
    val notifyDuplicates = com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice()
    val playback = remember { SearchPlayback(graph) }
    var preparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    // Whether a download strategy is registered for Magis (today there always is one): decides
    // whether a movie's dialog offers "Descargar película". See `DownloadSource.hasStrategy`.
    val magisDownloadable = remember { DownloadSource.hasStrategy("magis", graph.downloadStrategies.keys) }
    val caracolDownloadable = remember { DownloadSource.hasStrategy("ditu", graph.downloadStrategies.keys) }
    // Open Magis season: a series result from the portal IS a whole season, so its chapter list
    // opens instead of playing it directly.
    var magisSeason by remember { mutableStateOf<com.arkiv.player.data.gateway.GatewayResult?>(null) }
    // Magis movie that was tapped: instead of playing right away, ask whether to watch or download.
    var magisMovieChoice by remember { mutableStateOf<MagisTapDecision.ShowMovieDialog?>(null) }
    // Open Caracol series: same as Magis, chapters are picked before playing. It's SEPARATE state
    // from Magis's on purpose: what's tapped in its window only ever reaches
    // `playback.playDituSeason`, so a Caracol chapter never falls into Magis's save path.
    var dituSeason by remember { mutableStateOf<com.arkiv.player.data.gateway.GatewayResult?>(null) }
    // Open plugin series: same path as Caracol, saved through `playback.playPluginSeason`.
    var pluginSeason by remember { mutableStateOf<PlaySource.Plugin?>(null) }

    // Shortcut from the home: enters already positioned on a title. Fires only once per arg
    // combination (LaunchedEffect doesn't re-run on recompositions with no changes), and
    // startFromShortcut() is also guarded by selected.value != null.
    LaunchedEffect(shortcutKind, shortcutTmdbId, shortcutAnilistId) {
        val k = shortcutKind ?: return@LaunchedEffect
        vm.startFromShortcut(k, shortcutTmdbId, shortcutAnilistId)
    }

    // (The plain-title entry from a Kinobot suggestion chip is handled inside QueryContent, via its
    // `initialQuery`, so the search box is filled and the results — not the history — are shown.)

    // "Enriched" metadata for the chosen card, to save a real title/poster/description (not the
    // torrent's raw name) -- same criterion as CineDetailScreen.
    val resultTitle = detail?.title ?: animeShow?.title ?: selected?.title ?: ""
    val resultPoster = detail?.posterUrl ?: animeShow?.posterUrl ?: selected?.posterUrl ?: ""
    val resultDescription = detail?.overview ?: animeShow?.description

    // Applies the PlaybackResult returned by SearchPlayback: onPlay(epId) if it ended up ready, or
    // sets the error message exactly as the original logic showed it before it got extracted to the helper.
    fun applyResult(result: PlaybackResult) {
        preparing = false
        when (result) {
            is PlaybackResult.Ready -> onPlay(result.episodeId)
            is PlaybackResult.Failed -> playError = result.message
        }
    }

    // Plays the movie exactly like playMagisResult used to before this dialog existed: same path,
    // just triggered from "Ver película" instead of directly on tapping the card.
    fun watchMagisMovie(r: com.arkiv.player.data.gateway.GatewayResult) {
        preparing = true; playError = null
        scope.launch { applyResult(playback.playMagis(r)) }
    }

    // Saves the movie and enqueues it for a device download, same as the library does in
    // DetailScreen.saveEpisodesLocally: same permission helper, same duplicate notice, and the same
    // DownloadSource.sourceFor(epId) to pick the queue's strategy. The "queued" toast only fires for a
    // fresh EnqueueOutcome.QUEUED — ALREADY_QUEUED/ALREADY_DOWNLOADED already get their own message
    // from notifyDuplicates, and showing both would be misleading. See [queuedDownloadToastText].
    fun downloadMagisMovie(r: com.arkiv.player.data.gateway.GatewayResult) {
        askNotifications()
        scope.launch {
            val epId = playback.magisEpisodeId(r)
            if (epId == null) {
                playError = "No se pudo preparar la descarga de Xuper."
                return@launch
            }
            val outcome = graph.localDownloads.enqueue(epId, DownloadSource.sourceFor(epId))
            notifyDuplicates(listOf(outcome))
            queuedDownloadToastText(outcome, r.title)?.let {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun playMagisResult(r: com.arkiv.player.data.gateway.GatewayResult) {
        // Series → open the season dialog to pick a chapter, same as always. Movie → play it
        // directly now: the watch-or-download choice (`decideMagisTap`, MagisTapDecision.kt)
        // moved to long-press (see `longPressResult`) -- asking on every single tap got in the
        // way when someone's just browsing to watch, and a movie only has that ONE thing to pick
        // between watching or downloading it (a series still opens its own list first either way).
        when (val decision = decideMagisTap(r, magisDownloadable)) {
            is MagisTapDecision.OpenSeasonDialog -> magisSeason = decision.result
            is MagisTapDecision.ShowMovieDialog -> watchMagisMovie(r)
        }
    }

    fun playDituResult(source: PlaySource.Ditu) {
        // Series → open its chapters. Movie → play directly (and it stays in the library).
        if (source.isSeries()) { dituSeason = source.result; return }
        preparing = true; playError = null
        scope.launch { applyResult(playback.playDitu(source.result)) }
    }

    fun playPluginResult(source: PlaySource.Plugin) {
        // Same as Caracol: a series opens its chapters, a movie plays (and stays in the library).
        if (source.isSeries()) { pluginSeason = source; return }
        preparing = true; playError = null
        scope.launch { applyResult(playback.playPlugin(source.result)) }
    }

    fun playResult(source: PlaySource) = when (source) {
        is PlaySource.Magis -> playMagisResult(source.result)
        is PlaySource.Ditu -> playDituResult(source)
        is PlaySource.Plugin -> playPluginResult(source)
    }

    /**
     * Long-press: the ONLY gesture left that opens [magisMovieChoice] (watch-or-download) for a
     * Magis movie. A series has nothing extra to offer here -- it opens the same season dialog
     * either way -- and Caracol has no download strategy to offer at all (Widevine), so both fall
     * through with nothing to do.
     */
    fun longPressResult(source: PlaySource) {
        if (source !is PlaySource.Magis) return
        val decision = decideMagisTap(source.result, magisDownloadable)
        if (decision is MagisTapDecision.ShowMovieDialog) magisMovieChoice = decision
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = {
                    if (phase == SearchPhase.REFINE || phase == SearchPhase.RESULTS) vm.back() else onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                }
                Text(
                    "Buscar",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.weight(1f))
            }

            if (playError != null) {
                Text(
                    playError!!,
                    color = ArkivRed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when (phase) {
                SearchPhase.REFINE -> selected?.let { card ->
                    RefineContent(card = card, onContinue = { season, episode -> vm.runSourceSearch(season, episode) })
                }
                SearchPhase.RESULTS -> ResultsContent(
                    title = resultTitle,
                    posterUrl = resultPoster,
                    // Hero background: TMDB's backdrop or AniList's banner. If neither exists the
                    // hero falls back to a plain background, not a gap.
                    backdropUrl = detail?.backdropUrl?.ifBlank { null } ?: animeShow?.bannerUrl.orEmpty(),
                    metaChips = buildList {
                        (detail?.year?.ifBlank { null } ?: animeShow?.year?.takeIf { it > 0 }?.toString())
                            ?.let { add(it) }
                        detail?.seasons?.size?.takeIf { it > 0 }?.let { add(if (it == 1) "1 temporada" else "$it temporadas") }
                        animeShow?.episodes?.takeIf { it > 0 }?.let { add("$it episodios") }
                        animeShow?.scorePct?.takeIf { it > 0 }?.let { add("★ $it%") }
                        animeShow?.genres?.firstOrNull()?.let { add(it) }
                    },
                    season = refineSeason,
                    episode = refineEpisode,
                    sources = sources,
                    searchingSources = searchingSources,
                    sourcesState = sourcesState,
                    enabled = !preparing,
                    onPlay = { playResult(it) },
                    onLongPlay = { longPressResult(it) },
                    pluginMore = pluginMore,
                    onBrowsePlugin = onBrowsePlugin,
                )
                else -> QueryContent(
                    titleResults = titleResults,
                    loadingTitles = loadingTitles,
                    initialQuery = shortcutQuery,
                    onSearchSourcesByText = { q -> vm.searchSourcesByText(q) },
                    recentQueries = recentQueries,
                    recentTitles = recentTitles,
                    onSearch = { q ->
                        val match = if (onBrowseRow != null) matchCategoryRow(q, fixedRows) else null
                        if (match != null) onBrowseRow?.invoke(match.id, match.title)
                        else vm.search(q)
                    },
                    onPickTitle = { card -> vm.pickTitle(card) },
                    onForgetQuery = { vm.forgetQuery(it) },
                    onForgetTitle = { vm.forgetTitle(it) },
                    onClearHistory = { vm.clearHistory() },
                )
            }
        }

        if (preparing) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ArkivRed)
                    Text("Preparando…", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }

    magisMovieChoice?.let { choice ->
        val r = choice.result
        AlertDialog(
            onDismissRequest = { magisMovieChoice = null },
            title = { Text(r.title) },
            confirmButton = {
                TextButton(onClick = { magisMovieChoice = null; watchMagisMovie(r) }) {
                    Text("Ver película")
                }
            },
            dismissButton = {
                if (choice.canDownload) {
                    TextButton(onClick = { magisMovieChoice = null; downloadMagisMovie(r) }) {
                        Text("Descargar película")
                    }
                }
            },
        )
    }

    magisSeason?.let { season ->
        com.arkiv.player.ui.catalog.MagisSeasonDialog(
            season = season,
            client = graph.contentSource,
            onDismiss = { magisSeason = null },
            onPlay = { chapters, chapter, series ->
                magisSeason = null
                preparing = true; playError = null
                scope.launch { applyResult(playback.playMagisSeason(season, chapters, chapter, series)) }
            },
            onSave = { _, chosen, series ->
                askNotifications()
                scope.launch {
                    // Saved chapter by chapter: each one is a separate file on the CDN and the
                    // queue already knows how to group by series to show them together in Descargas.
                    var queued = 0
                    for (chapter in chosen) {
                        val epId = playback.magisEpisodeIdFor(season, chapter, series) ?: continue
                        if (graph.localDownloads.enqueue(epId, "magis") ==
                            com.arkiv.player.data.local.EnqueueOutcome.QUEUED
                        ) queued++
                    }
                    playError = when {
                        queued == 0 -> "Esos capítulos ya estaban guardados."
                        queued == chosen.size -> null
                        else -> "Se encolaron $queued de ${chosen.size} (el resto ya estaba)."
                    }
                }
            },
        )
    }

    dituSeason?.let { caracolSeries ->
        com.arkiv.player.ui.catalog.MagisSeasonDialog(
            season = caracolSeries,
            // The composite source: with a Caracol ref, `episodesWithSeries` reaches `DituSource`.
            client = graph.contentSource,
            onDismiss = { dituSeason = null },
            // Saves to the library every chapter the window already loaded, and plays the tapped one.
            onPlay = { chapters, chapter, series ->
                dituSeason = null
                preparing = true; playError = null
                scope.launch { applyResult(playback.playDituSeason(caracolSeries, chapters, chapter, series)) }
            },
            // Caracol CAN be downloaded, since 2026-09-13. Not like Magis: what stays on the device
            // are its encrypted segments, and opening them still needs a network license (a few
            // KB). See `CaracolStore`. Only offered if a strategy is registered, the same gate the
            // rest of the app uses.
            onSave = if (!caracolDownloadable) null else { all, chosen, series ->
                askNotifications()
                scope.launch {
                    val queued = playback.enqueueCaracolDownload(caracolSeries, all, chosen, series)
                    playError = when {
                        queued == 0 -> "Esos capítulos ya estaban guardados."
                        queued == chosen.size -> null
                        else -> "Se encolaron $queued de ${chosen.size} (el resto ya estaba)."
                    }
                }
            },
            sourceLabel = "Caracol",
            accent = ArkivCaracolVerde,
        )
    }

    pluginSeason?.let { open ->
        com.arkiv.player.ui.catalog.MagisSeasonDialog(
            season = open.result,
            // The composite source: with a plg1: ref, `episodesWithSeries` reaches the plugin.
            client = graph.contentSource,
            onDismiss = { pluginSeason = null },
            onPlay = { chapters, chapter, series ->
                pluginSeason = null
                preparing = true; playError = null
                scope.launch { applyResult(playback.playPluginSeason(open.result, chapters, chapter, series)) }
            },
            // No downloads for plugin titles in v1.
            onSave = null,
            sourceLabel = open.pluginName,
            accent = open.accent,
        )
    }

}

/**
 * QUERY phase: search box + TMDB/anime title grid. Shows the history instead of an empty results
 * state while nothing has been searched yet.
 */
@Composable
private fun QueryContent(
    titleResults: List<TitleCard>,
    loadingTitles: Boolean,
    /** A title to run on entry (a Kinobot suggestion chip): fills the box and searches it once. */
    initialQuery: String? = null,
    /** Sends the text AS-IS to the sources wizard, without going through the catalog (same path
     *  as the TV's "Buscar" button): for when you remember a piece of the name and not the exact
     *  title TMDB has it under. */
    onSearchSourcesByText: (String) -> Unit,
    recentQueries: List<String>,
    recentTitles: List<RecentTitle>,
    onSearch: (String) -> Unit,
    onPickTitle: (TitleCard) -> Unit,
    onForgetQuery: (String) -> Unit,
    onForgetTitle: (RecentTitle) -> Unit,
    onClearHistory: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // While nothing has been searched yet, the history shows instead of two "Sin resultados" that
    // say nothing useful. Local state: leaving the screen and coming back shows the history again.
    var hasSearched by remember { mutableStateOf(false) }

    // Bumps on every search: the key to snap the grid back to the top. Without this the list keeps
    // the previous search's scroll and the new one shows up starting halfway down.
    var searchNumber by remember { mutableStateOf(0) }
    val gridState = rememberLazyGridState()
    // Same case as on the TV: results arrive in two batches and the ViewModel publishes
    // `tmdb + anime`, so the second one gets inserted ABOVE and the grid stays anchored where it
    // was. It's held at the top until you scroll it yourself.
    var gridTouched by remember(searchNumber) { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) gridTouched = true
    }
    LaunchedEffect(searchNumber, titleResults) {
        if (!gridTouched) gridState.scrollToItem(0)
    }

    val search: (String) -> Unit = { q ->
        text = q
        hasSearched = true
        searchNumber++
        onSearch(q)
    }

    // Entered from a Kinobot suggestion chip: fill the box and search that title once, so the results
    // (not the history) show. Keyed on the query so a different chip re-runs it.
    LaunchedEffect(initialQuery) {
        initialQuery?.takeIf { it.isNotBlank() }?.let { search(it) }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(gridColumns(3, isLandscapeTablet())),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Buscar…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                // Clearing returns to the history. Without this, once the first thing is searched
                // the history doesn't come back until leaving and re-entering the screen.
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = ""; hasSearched = false; onSearch("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Limpiar")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(text) }),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }

        if (text.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // Parity with the TV: search the sources with the text as-is, without tying it to
                // the exact title from the catalog above.
                OutlinedButton(
                    onClick = { onSearchSourcesByText(text.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Buscar \"$text\" en las fuentes",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (!hasSearched) {
            historyItems(
                queries = recentQueries,
                titles = recentTitles,
                onSearch = search,
                onPickTitle = onPickTitle,
                onForgetQuery = onForgetQuery,
                onForgetTitle = onForgetTitle,
                onClearHistory = onClearHistory,
            )
            return@LazyVerticalGrid
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Películas y series", style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (loadingTitles) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (titleResults.isEmpty() && !loadingTitles) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        items(titleResults, key = { "${it.kind}-${it.tmdbId}-${it.anilistId}-${it.title}" }) { card ->
            TitleCardItem(card, onClick = { onPickTitle(card) })
        }
    }
}

/**
 * History: searched texts as chips and opened titles as posters. Kept apart from [QueryContent]
 * to not bloat it; it's a LazyGridScope extension because it lives inside the same grid (the
 * posters have to fall in the same 3 columns as the results).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun LazyGridScope.historyItems(
    queries: List<String>,
    titles: List<RecentTitle>,
    onSearch: (String) -> Unit,
    onPickTitle: (TitleCard) -> Unit,
    onForgetQuery: (String) -> Unit,
    onForgetTitle: (RecentTitle) -> Unit,
    onClearHistory: () -> Unit,
) {
    // First time the app opens: no headers at all. Just the search box and nothing else.
    if (queries.isEmpty() && titles.isEmpty()) return

    if (queries.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Búsquedas recientes",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                queries.forEach { q ->
                    InputChip(
                        selected = false,
                        onClick = { onSearch(q) },
                        label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Quitar $q",
                                modifier = Modifier.size(16.dp).clickable { onForgetQuery(q) },
                            )
                        },
                    )
                }
            }
        }
    }

    if (titles.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Títulos recientes",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        items(titles, key = { "recent-${it.kind}-${it.tmdbId}-${it.anilistId}-${it.title}" }) { recent ->
            val card = recent.toTitleCard()
            TitleCardItem(card, onClick = { onPickTitle(card) })
        }
    }

    item(span = { GridItemSpan(maxLineSpan) }) {
        TextButton(onClick = onClearHistory, modifier = Modifier.padding(top = 8.dp)) {
            Text("Borrar historial", color = ArkivTextSecondary)
        }
    }
}

@Composable
private fun TitleCardItem(card: TitleCard, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
        ) {
            AsyncImage(
                model = card.posterUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.padding(4.dp).clip(RoundedCornerShape(4.dp))
                    .background(kindColor(card.kind)).padding(horizontal = 4.dp, vertical = 1.dp),
            ) { Text(kindLabel(card.kind), color = Color.Black, style = MaterialTheme.typography.labelSmall) }
        }
        Text(
            card.title,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (card.year.isNotBlank()) {
            Text(card.year, style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
        }
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "movie" -> "PELÍCULA"
    "series" -> "SERIE"
    else -> "ANIME"
}

private fun kindColor(kind: String): Color = when (kind) {
    "movie" -> Color(0xFF64B5F6)
    "series" -> Color(0xFF4CAF50)
    else -> Color(0xFFBA68C8)
}

/** REFINE phase: optional season/chapter (series) or optional episode (anime) before RESULTS. */
@Composable
private fun RefineContent(card: TitleCard, onContinue: (season: Int?, episode: Int?) -> Unit) {
    var seasonText by remember(card) { mutableStateOf("") }
    var episodeText by remember(card) { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Box(
                modifier = Modifier.height(180.dp).aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
            ) {
                AsyncImage(
                    model = card.posterUrl,
                    contentDescription = card.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                if (card.year.isNotBlank()) {
                    Text(card.year, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
                }
            }
        }

        Spacer(Modifier.size(24.dp))

        when (card.kind) {
            "series" -> {
                OutlinedTextField(
                    value = seasonText,
                    onValueChange = { seasonText = it.filter(Char::isDigit) },
                    label = { Text("Temporada (opcional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = episodeText,
                    onValueChange = { episodeText = it.filter(Char::isDigit) },
                    label = { Text("Capítulo (opcional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
                Button(
                    onClick = { onContinue(seasonText.toIntOrNull(), episodeText.toIntOrNull()) },
                    colors = ButtonDefaults.buttonColors(containerColor = ArkivRed),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continuar") }
            }
            "anime" -> {
                OutlinedTextField(
                    value = episodeText,
                    onValueChange = { episodeText = it.filter(Char::isDigit) },
                    label = { Text("Episodio (opcional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
                Button(
                    onClick = { onContinue(null, episodeText.toIntOrNull()) },
                    colors = ButtonDefaults.buttonColors(containerColor = ArkivRed),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continuar") }
            }
            else -> Unit // "movie" doesn't reach REFINE: pickTitle() sends it straight to RESULTS.
        }
    }
}

/**
 * RESULTS phase: multi-source search (Magis/Caracol) for the chosen card, with S/E injected if it
 * came from REFINE or by name alone otherwise. Reuses SourceSectionHeader (same collapsible
 * pattern as CineDetailScreen's bottom sheet).
 */
@Composable
private fun ResultsContent(
    title: String,
    posterUrl: String,
    backdropUrl: String,
    metaChips: List<String>,
    season: Int?,
    episode: Int?,
    sources: List<PlaySource>,
    searchingSources: SearchingSources,
    sourcesState: SourcesState,
    enabled: Boolean,
    onPlay: (PlaySource) -> Unit,
    onLongPlay: (PlaySource) -> Unit,
    pluginMore: Map<String, com.arkiv.player.ui.plugin.PluginMoreTarget> = emptyMap(),
    onBrowsePlugin: ((com.arkiv.player.ui.plugin.PluginMoreTarget) -> Unit)? = null,
) {
    // Both start open by default: a section that starts collapsed looks empty even if it brings results.
    var expandedSections by remember { mutableStateOf(setOf("MAGIS", "CARACOL")) }
    fun toggle(k: String) { expandedSections = if (k in expandedSections) expandedSections - k else expandedSections + k }
    // `rememberSaveable` and not `remember`: this screen gets destroyed when the player opens, and
    // with `remember` the chosen origin was lost -- you'd come back from watching something via
    // Magis and the list was back on "Todo", with the item you'd just tapped buried among dozens of results.
    // The KEY is saved (a `SourceTab` isn't Saveable); a plugin tab that's gone falls back to "Todo".
    var tabKey by rememberSaveable { mutableStateOf(SourceTab.ALL.key) }
    val tabs = tabsFor(sources)
    val tab = tabs.firstOrNull { it.key == tabKey } ?: SourceTab.ALL
    // Plugin sections start open like the fixed ones; this remembers the ones the person closed.
    var collapsedPlugins by rememberSaveable { mutableStateOf(setOf<String>()) }

    val magis = sources.filterIsInstance<PlaySource.Magis>()
    val caracol = sources.filterIsInstance<PlaySource.Ditu>()
    val anyLoading = searchingSources.any
    val counts = countsByTab(sources)
    // Each chip spins while its source is still searching, and "Todo" while any one is missing:
    // see [SearchingSources].
    val loadingOf = tabs.associateWith { searchingSources.isSearching(it) }

    // The hero goes full-bleed (no side margin) so the backdrop reaches the edges; that's why the
    // horizontal padding is set by each item instead of the list's contentPadding.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            ResultsHero(title, posterUrl, backdropUrl, metaChips, season, episode, sources.size, anyLoading)
        }

        item(key = "filters") {
            SourceTabRow(tabs, tab, counts, loadingOf, Modifier.padding(horizontal = HPAD, vertical = 12.dp)) { tabKey = it.key }
        }

        // One line per down source, with or without results: doesn't cover up what the others brought.
        downSourceNotices(sourcesState, tab).forEachIndexed { i, notice ->
            item(key = "aviso-$i") {
                Text(
                    notice,
                    color = ArkivRed,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = HPAD, vertical = 4.dp),
                )
            }
        }

        if (!anyLoading && sources.isEmpty()) {
            item(key = "empty") {
                Text(
                    noSourcesText(sourcesState),
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(horizontal = HPAD, vertical = 12.dp),
                )
            }
        } else if (tab == SourceTab.ALL) {
            // "Todo": a collapsible section per origin, in [SourceTab]'s order.
            sourceSection(this, "XUPER", ArkivMagisBlue, magis, searchingSources.isSearching(SourceTab.MAGIS), "MAGIS" in expandedSections, { toggle("MAGIS") }, enabled, onPlay, onLongPlay, emptySectionText(SourceTab.MAGIS, sourcesState))
            sourceSection(this, "CARACOL", ArkivCaracolVerde, caracol, searchingSources.isSearching(SourceTab.CARACOL), "CARACOL" in expandedSections, { toggle("CARACOL") }, enabled, onPlay, onLongPlay, emptySectionText(SourceTab.CARACOL, sourcesState))
            // Then one section per plugin that brought results, in [tabsFor]'s order.
            tabs.filter { PluginIds.pluginIdOfSource(it.key) != null }.forEach { t ->
                sourceSection(
                    this, t.label.uppercase(), t.accent, filterByTab(sources, t), searchingSources.isSearching(t),
                    t.key !in collapsedPlugins,
                    { collapsedPlugins = if (t.key in collapsedPlugins) collapsedPlugins - t.key else collapsedPlugins + t.key },
                    enabled, onPlay, onLongPlay, emptySectionText(t, sourcesState), key = t.key,
                )
            }
        } else {
            // With one origin chosen the section header is unnecessary: the list goes flat.
            val shown = filterByTab(sources, tab)
            val empty = if (shown.isEmpty()) emptyTabText(tab, loadingOf[tab] == true, sourcesState) else null
            if (empty != null) {
                item(key = "empty-tab") {
                    Text(
                        empty,
                        color = ArkivTextSecondary,
                        modifier = Modifier.padding(horizontal = HPAD, vertical = 16.dp),
                    )
                }
            }
            if (shown.any { posterFor(it).isNotBlank() }) {
                twoColumnCards("tab", shown, enabled, onPlay, onLongPlay)
            } else {
                items(shown, key = { sourceKey(it) }) { s ->
                    Box(Modifier.padding(horizontal = HPAD)) {
                        SourceRow(s, enabled = enabled, onLongClick = { onLongPlay(s) }) { onPlay(s) }
                    }
                }
            }
            // A plugin tab whose first page came with a cursor: the rest opens in "Ver más".
            val more = pluginMore[tab.key]
            if (more != null && onBrowsePlugin != null) {
                item(key = "plugin-more-${tab.key}") {
                    androidx.compose.material3.TextButton(onClick = { onBrowsePlugin(more) }, modifier = Modifier.padding(horizontal = HPAD)) {
                        Text("Ver más resultados de ${tab.label}", color = ArkivRed)
                    }
                }
            }
        }
    }
}

private val HPAD = 16.dp

/**
 * RESULTS phase header: full-bleed backdrop with a gradient to black, and on top the poster and
 * the title's data. The gradient is what makes the image blend into the list instead of staying a
 * box stuck at the top; without it the backdrop cuts sharply against the background.
 *
 * If there's no backdrop (AniList sometimes brings no banner) the plain background is left and
 * the poster takes over -- that's why the gradient starts opaque from the top and doesn't depend
 * on there being an image.
 */
@Composable
private fun ResultsHero(
    title: String,
    posterUrl: String,
    backdropUrl: String,
    metaChips: List<String>,
    season: Int?,
    episode: Int?,
    total: Int,
    loading: Boolean,
) {
    Box(Modifier.fillMaxWidth().height(230.dp)) {
        if (backdropUrl.isNotBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(170.dp).align(Alignment.TopCenter),
            )
        }
        // Double veil: a vertical one that blends the image into the list's background, and a
        // horizontal one from the left so the text reads over any backdrop.
        Box(
            Modifier.fillMaxSize().background(
                // Dark at top and bottom, light in the middle strip: without the darkening at the
                // top the image cuts sharply against the "Buscar" bar, which is black.
                Brush.verticalGradient(
                    0f to ArkivBlack.copy(alpha = 0.85f),
                    0.22f to ArkivBlack.copy(alpha = 0.30f),
                    0.62f to ArkivBlack.copy(alpha = 0.80f),
                    1f to ArkivBlack,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to ArkivBlack.copy(alpha = 0.75f), 0.75f to Color.Transparent),
            ),
        )

        Row(
            Modifier.align(Alignment.BottomStart).padding(start = HPAD, end = HPAD, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.height(130.dp).aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp)).background(ArkivSurfaceHigh),
            ) {
                AsyncImage(
                    model = posterUrl, contentDescription = title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.padding(start = 14.dp, bottom = 6.dp)) {
                Text(
                    title, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (metaChips.isNotEmpty()) {
                    Text(
                        metaChips.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium, color = ArkivTextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (season != null && episode != null) {
                        MetaChip("T$season · E$episode", ArkivRed, strong = true)
                    }
                    if (loading) {
                        CircularProgressIndicator(
                            color = ArkivTextSecondary, strokeWidth = 1.5.dp,
                            modifier = Modifier.size(12.dp),
                        )
                        Text("Buscando…", style = MaterialTheme.typography.labelMedium, color = ArkivTextSecondary)
                    } else {
                        Text(
                            "$total fuentes", style = MaterialTheme.typography.labelMedium,
                            color = ArkivTextSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** A section (header + rows) inside the LazyColumn, so rows compose on-demand instead of all at
 *  once: a search by name easily brings 60+ torrents. */
/**
 * Results as a two-column cover grid, for sources that bring an image.
 *
 * Goes in pairs inside the LazyColumn instead of a LazyVerticalGrid: a lazy grid nested in a lazy
 * list of the same axis has no height to measure against and crashes. With twenty results the
 * cost of not being lazy per column is nil.
 */
private fun LazyListScope.twoColumnCards(
    tag: String,
    items: List<PlaySource>,
    enabled: Boolean,
    onPlay: (PlaySource) -> Unit,
    onLongPlay: (PlaySource) -> Unit,
) {
    items(items.chunked(2), key = { pair -> "$tag-grid-${sourceKey(pair.first())}" }) { pair ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = HPAD, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pair.forEach { s ->
                Box(Modifier.weight(1f)) {
                    SourceCard(s, enabled = enabled, onLongClick = { onLongPlay(s) }) { onPlay(s) }
                }
            }
            // Odd one out: a spacer takes the gap so the last card doesn't stretch to full width.
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun sourceSection(
    scope: LazyListScope,
    tag: String,
    tagColor: Color,
    items: List<PlaySource>,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    onPlay: (PlaySource) -> Unit,
    onLongPlay: (PlaySource) -> Unit,
    /** What shows below the section when it brought back nothing ([emptySectionText]). */
    empty: String,
    /** The section's LazyColumn identity; defaults to [tag], but two plugins may share a display name. */
    key: String = tag,
) {
    scope.item(key = "sec-$key") {
        Box(Modifier.padding(horizontal = HPAD)) {
            SourceSectionHeader(tag, tagColor, items.size, loading, expanded, onToggle)
        }
    }
    if (expanded) {
        if (items.any { posterFor(it).isNotBlank() }) {
            scope.twoColumnCards(key, items, enabled, onPlay, onLongPlay)
        } else {
            scope.items(items, key = { "$key-${sourceKey(it)}" }) { s ->
                Box(Modifier.padding(horizontal = HPAD)) {
                    SourceRow(s, enabled = enabled, onLongClick = { onLongPlay(s) }) { onPlay(s) }
                }
            }
        }
        if (items.isEmpty() && !loading) {
            scope.item(key = "sec-$key-empty") {
                Text(
                    empty, color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = HPAD + 8.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** A source's stable identity, for the LazyColumn's keys (two different results with the same
 *  name would break the list if they shared a key). Same criterion the TV search uses. */
private fun sourceKey(s: PlaySource): String = when (s) {
    is PlaySource.Magis -> "m-${s.result.extra["content_id"] ?: s.result.ref}"
    // Caracol's ref is already unique per content: `ditu1:<contentType>:<contentId>`.
    is PlaySource.Ditu -> "d-${s.result.ref}"
    // The plugin item id is stable and unique within its plugin; the source keeps plugins apart.
    is PlaySource.Plugin -> "p-${s.result.source}-${s.result.extra["pluginItemId"] ?: s.result.ref}"
}

/**
 * Filter chips by origin ([tabs], from [tabsFor]: the fixed ones, then one per plugin), with their count.
 *
 * The row SCROLLS horizontally: with more sources than fit a phone's width, a non-scrolling Row
 * shrank the last chip to fit and its text came out split letter by letter vertically. Scrolling,
 * each chip keeps its natural width and reads in full.
 */
@Composable
private fun SourceTabRow(
    tabs: List<SourceTab>,
    selected: SourceTab,
    counts: Map<SourceTab, Int>,
    loading: Map<SourceTab, Boolean>,
    modifier: Modifier = Modifier,
    onSelect: (SourceTab) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { t ->
            val accent = t.accent
            val on = t == selected
            Row(
                Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (on) accent.copy(alpha = 0.22f) else ArkivSurfaceHigh.copy(alpha = 0.5f))
                    .clickable { onSelect(t) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    t.label, style = MaterialTheme.typography.labelMedium,
                    color = if (on) accent else ArkivTextSecondary,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (loading[t] == true) {
                    CircularProgressIndicator(
                        color = if (on) accent else ArkivTextSecondary,
                        strokeWidth = 1.5.dp, modifier = Modifier.size(10.dp),
                    )
                } else {
                    Text(
                        "${counts[t] ?: 0}", style = MaterialTheme.typography.labelSmall,
                        color = if (on) accent else ArkivTextSecondary,
                    )
                }
            }
        }
    }
}
