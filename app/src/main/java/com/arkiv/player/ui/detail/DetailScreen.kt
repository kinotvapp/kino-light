package com.arkiv.player.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.local.DownloadAction
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.local.ChapterDownloadState
import com.arkiv.player.data.local.DownloadLabel
import com.arkiv.player.data.local.DownloadSource
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.thumbnails.ThumbnailChoice
import com.arkiv.player.ui.isLandscapeTablet
import com.arkiv.player.ui.formatDuration
import com.arkiv.player.ui.ChapterLabel
import com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice
import com.arkiv.player.ui.offline.rememberPostNotificationsRequest
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.components.DownloadBar
import com.arkiv.player.ui.components.DownloadControl
import com.arkiv.player.ui.components.DownloadConfirmDialog
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.theme.NucDownloadedGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    identifier: String,
    onBack: () -> Unit,
    onPlayEpisode: (String) -> Unit,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    // Notification permission (API 33+): only asked when a download is triggered, which is the
    // only thing that notifies from this screen. Same moment and same helper as
    // AnimeShowDetailScreen/CineDetailScreen.
    val askNotifications = rememberPostNotificationsRequest()
    // Shows "you already have that downloaded" when the queue skips a duplicate download (see
    // DuplicateDownloadPolicy): otherwise the button would look like it does nothing.
    val notifyDuplicates = rememberDuplicateDownloadNotice()
    val vm: DetailViewModel = viewModel(
        // The phone always navigates with a raw identifier (not a group key); observeGroupMembers
        // resolves it the same way through its fallback to `rows.filter { identifier == groupKey }`.
        factory = viewModelFactory { initializer { DetailViewModel(graph.repository, groupKey = identifier) } },
    )
    val detail by vm.detail.collectAsStateWithLifecycle()
    val skipMarker by vm.skipMarker.collectAsStateWithLifecycle()
    // Title and image of each chapter per TMDB. It's a local cache: the series' first opening
    // fills it and from then on it comes from the database. If it's not known which series the
    // item belongs to, the maps stay empty and each row falls back to its file name.
    val tmdbTitles by graph.repository.observeEpisodeTitles(identifier)
        .collectAsStateWithLifecycle(emptyMap())
    val tmdbStills by graph.repository.observeEpisodeStills(identifier)
        .collectAsStateWithLifecycle(emptyMap())
    // Frames captured during playback: the chapter's real scene, when it exists it beats the TMDB
    // still (see ThumbnailChoice). Only has an entry if the chapter got started, so "only wins on
    // what's started" simply falls out of the key being absent.
    val tmdbFrames by graph.repository.observeEpisodeFrames(identifier)
        .collectAsStateWithLifecycle(emptyMap())
    // Each chapter's synopsis (TMDB). Same cache as titles/stills, and the same empty-map rule
    // when it's not known which series the item belongs to.
    val tmdbOverviews by graph.repository.observeEpisodeOverviews(identifier)
        .collectAsStateWithLifecycle(emptyMap())
    LaunchedEffect(identifier) {
        // Same fix as in the TV detail screen: Magis items saved with no `tmdbId` have nothing to
        // ask stills with, so the gateway gets asked first (only once).
        com.arkiv.player.data.gateway.repairMagisIdentity(
            graph.repository, graph.contentSource, identifier,
        )
        runCatching { graph.repository.ensureEpisodeStills(identifier) }
    }
    // On-device download state of each chapter, straight from the `downloads` table (the same one
    // the Downloads screen shows). Replaces the "what's on the NUC" cache: that green checkmark
    // used to announce "already downloaded" over content that, since remote playback was
    // disconnected, nobody could see or play anymore.
    val downloadRows by graph.repository.observeDownloadRows().collectAsStateWithLifecycle(emptyList())
    val savedEpisodeIds = remember(downloadRows) {
        downloadRows.filter { it.state == LocalDownloadState.COMPLETED }.map { it.episodeId }.toSet()
    }
    // Download state PER chapter, not a plain "doing something / not doing anything": the row
    // needs to know whether it's waiting its turn, what percentage it's at, or why it failed. See
    // [ChapterDownloadState].
    val downloadStates = remember(downloadRows) {
        downloadRows.associate { it.episodeId to ChapterDownloadState.of(it) }
    }

    // Saves chapters to the DEVICE. The notification permission is asked ONCE per user action (not
    // once per chapter): a batch of 30 would fire 30 `launcher.launch` calls in a row on the same
    // ActivityResultLauncher before the user answers the first dialog.
    fun saveEpisodesLocally(episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        askNotifications()
        scope.launch {
            // A single notice for the whole batch, not one per chapter.
            notifyDuplicates(episodes.map { graph.localDownloads.enqueue(it.id, DownloadSource.sourceFor(it.id)) })
        }
    }

    val onDownloadEpisode: (Episode) -> Unit = { ep -> saveEpisodesLocally(listOf(ep)) }

    // Retry what failed, without leaving to the Downloads screen: the failure shows in the same
    // row the download was requested from, so the action lives there too.
    val onRetryEpisode: (Episode) -> Unit = { ep -> scope.launch { graph.localDownloads.retry(ep.id) } }

    // Undoing also lives in the row, for the same reason. `cancel` keeps the partial (the download
    // resumes from there); `remove` deletes the row and the file, which fits both what never
    // started and what's no longer wanted saved.
    val onDownloadAction: (Episode, DownloadAction) -> Unit = { ep, action ->
        scope.launch {
            when (action) {
                DownloadAction.CANCEL -> graph.localDownloads.cancel(ep.id)
                DownloadAction.REMOVE_FROM_QUEUE, DownloadAction.DELETE ->
                    graph.localDownloads.remove(ep.id)
            }
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var showMarkersDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    // Only chapters that CAN be downloaded are offered for saving to the device: the ones with a
    // strategy in `AppGraph.downloadStrategies` (today, only Magis). A Caracol (Widevine) chapter
    // or an old archive.org row used to end up FAILED with "Fuente no soportada" AFTER this screen
    // said "Guardando": an option that's going to fail isn't shown. See `DownloadSource.canDownload`.
    val strategies = remember { graph.downloadStrategies.keys }
    val canDownload: (Episode) -> Boolean = { ep -> DownloadSource.canDownload(ep.id, strategies) }
    val savableEpisodes = detail?.episodes.orEmpty().filter(canDownload)

    // This screen's button saves TO THE DEVICE (local worker), not to any server of our own: the
    // download to the NUC (`ArkivOfflineApi`/`NucDownloadCheckWorker`) was deleted entirely in this
    // branch's pruning, it wasn't left "disconnected" -- not a single line of that machinery exists
    // in the tree.
    fun saveSelectedLocally(episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        saveEpisodesLocally(episodes)
        // Queuing is instant and silent; without this confirmation the tap would leave no visible
        // trace until the worker starts. A snackbar (not a fixed Text) because this screen's
        // content is a LazyColumn that also auto-scrolls.
        scope.launch { snackbarHost.showSnackbar("Guardando en el dispositivo (${episodes.size})") }
    }

    if (showMarkersDialog) {
        MarkersDialog(
            current = skipMarker,
            onDismiss = { showMarkersDialog = false },
            onSave = { openStart, openEnd, endStart ->
                vm.saveSkipMarker(openStart, openEnd, endStart)
            },
        )
    }

    if (showRenameDialog) {
        var newTitle by remember(showRenameDialog) { mutableStateOf(detail?.title ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Cambiar nombre") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    label = { Text("Nombre") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank(),
                    onClick = { vm.rename(newTitle); showRenameDialog = false },
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancelar") } },
        )
    }

    if (showSaveDialog && savableEpisodes.isNotEmpty()) {
        SaveEpisodesDialog(
            episodes = savableEpisodes,
            alreadySaved = savedEpisodeIds,
            onDismiss = { showSaveDialog = false },
            onConfirm = { chosen ->
                showSaveDialog = false
                saveSelectedLocally(chosen)
            },
        )
    }

    Scaffold(
        containerColor = ArkivBlack,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(detail?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Más opciones")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        // Goes in the "⋮" and not next to "Reproducir": saving several chapters is
                        // a whole-series action used once, not something that visually competes
                        // with the main button. Saving a SINGLE chapter still lives in its own row.
                        if (savableEpisodes.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Guardar en el dispositivo") },
                                onClick = {
                                    menuExpanded = false
                                    showSaveDialog = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Marcadores de intro/outro") },
                            onClick = {
                                menuExpanded = false
                                showMarkersDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Cambiar nombre") },
                            onClick = {
                                menuExpanded = false
                                showRenameDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Quitar de mi biblioteca") },
                            onClick = {
                                menuExpanded = false
                                vm.removeFromLibrary { onBack() }
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ArkivBlack),
            )
        },
    ) { padding ->
        val data = detail
        if (data == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Cargando…", color = ArkivTextSecondary)
            }
            return@Scaffold
        }
        DetailContent(
            data = data,
            downloadStates = downloadStates,
            onPlayEpisode = onPlayEpisode,
            onDownloadEpisode = onDownloadEpisode,
            canDownload = canDownload,
            onRetryEpisode = onRetryEpisode,
            onDownloadAction = onDownloadAction,
            onToggleWatched = vm::toggleWatched,
            tmdbTitles = tmdbTitles,
            tmdbStills = tmdbStills,
            tmdbFrames = tmdbFrames,
            tmdbOverviews = tmdbOverviews,
            // The bottom one is always used. The top one is almost never needed -- in a single
            // panel, what's below the TopAppBar is the full-bleed poster (decorative, can be
            // covered) -- but in two panels the right panel starts with tappable content (chips or
            // the first chapter), so there it IS needed to avoid leaving it covered and
            // unreachable. See DetailContent.
            bottomInset = padding.calculateBottomPadding(),
            topInset = padding.calculateTopPadding(),
        )
    }
}

@Composable
private fun DetailContent(
    data: ItemDetail,
    /** episodeId -> what its device download is doing. See [DetailScreen]. */
    downloadStates: Map<String, DownloadDisplayState>,
    onPlayEpisode: (String) -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    /** Whether there's a way to download that chapter. Without it, its row doesn't offer saving it. See [DetailScreen]. */
    canDownload: (Episode) -> Boolean,
    onRetryEpisode: (Episode) -> Unit,
    /** Remove from queue / cancel / delete. Already confirmed by the user. */
    onDownloadAction: (Episode, DownloadAction) -> Unit,
    onToggleWatched: (String, Boolean) -> Unit,
    /** episodeId -> chapter title / image / synopsis per TMDB. Empty if the series isn't known. See [DetailScreen]. */
    tmdbTitles: Map<String, String>,
    tmdbStills: Map<String, String>,
    /** episodeId -> on-disk path of the captured frame. Beats [tmdbStills]; see [DetailScreen]. */
    tmdbFrames: Map<String, String>,
    tmdbOverviews: Map<String, String>,
    bottomInset: androidx.compose.ui.unit.Dp,
    /** TopAppBar height. Only used in two panels, so the right panel doesn't start covered by the
     *  bar; in a single panel the content still starts at y=0, with no padding, as always. */
    topInset: androidx.compose.ui.unit.Dp,
) {
    // Chapter + action the user asked to undo and hasn't confirmed yet. See
    // [DownloadConfirmation]: all three actions ask for confirmation because the control is tiny
    // and all of them are expensive if tapped by accident.
    var pendingConfirmation by remember { mutableStateOf<Pair<Episode, DownloadAction>?>(null) }

    // Sites detected across ALL of the series' episodes (not the already-filtered list): the chip
    // set can't shrink when the user picks a filter, or the way back to "Todos" would disappear.
    // See [siteLabelOf].
    val siteLabels = remember(data.episodes) {
        data.episodes.mapNotNull { siteLabelOf(it.sourceRef) }.toCollection(sortedSetOf())
    }
    // null = "Todos" (no filter), the default -- don't touch the existing behavior until the user
    // deliberately picks a chip. rememberSaveable (not remember): Navigation Compose takes this
    // screen out of composition when the player opens, and a plain remember resets on return --
    // the chosen filter was getting lost on every "back" from the player.
    var selectedSite by rememberSaveable(data.identifier) { mutableStateOf<String?>(null) }
    // Episodes without a recognizable sourceRef -- which today is basically ALL of them: Magis and
    // Ditu both write an opaque ref into `torrentData` (`magis1:...`, `ditu1:...`), not a URL, so
    // `siteLabelOf` returns null for them. This only ever resolves to a real host for a legacy row
    // saved by the now-removed web source (a plain URL) or before sourceRef was persisted at all --
    // these episodes don't belong to ANY site in the filter, so they stay visible always instead of
    // disappearing when the user filters by one specific site.
    val filteredEpisodes = remember(data.episodes, selectedSite) {
        val site = selectedSite
        if (site == null) {
            data.episodes
        } else {
            data.episodes.filter { ep -> val label = siteLabelOf(ep.sourceRef); label == null || label == site }
        }
    }
    val bySection = filteredEpisodes.groupBy { it.section }

    // Same condition to decide the layout (further below, where the item card goes) and
    // resumeIndex's offset: if computed separately, the day one changes without the other, the
    // auto-scroll silently drifts out of sync. See ItemHero.
    val twoPanels = isLandscapeTablet()

    // (Flattened) index of the episode I'm on, to auto-scroll to it on opening. In a single panel,
    // the LazyColumn's first item is ItemHero (image + title block); in two panels the item card
    // lives apart, in the left panel, and doesn't count -- that's why headerItems comes from
    // twoPanels and not a fixed number. After that, IF there are 2+ sites, 1 more item comes with
    // the filter chip row (see further below); and after that each named section adds 1 header
    // item before its episodes.
    val listState = rememberLazyListState()
    val currentEpisodeId = data.inProgressEpisode?.id
    val resumeIndex = remember(filteredEpisodes, data.progress, siteLabels, twoPanels) {
        val target = data.inProgressEpisode ?: return@remember null
        val headerItems = if (twoPanels) 0 else 1
        var idx = headerItems + if (siteLabels.size >= 2) 1 else 0
        bySection.forEach { (section, episodes) ->
            if (section.isNotBlank()) idx += 1
            val pos = episodes.indexOfFirst { it.id == target.id }
            if (pos >= 0) return@remember idx + pos
            idx += episodes.size
        }
        null
    }
    // Only auto-scrolls once per screen opening, to not fight the user if they scroll by hand
    // afterward.
    var didAutoScroll by remember(data.identifier) { mutableStateOf(false) }
    LaunchedEffect(resumeIndex) {
        val idx = resumeIndex
        if (idx != null && !didAutoScroll) {
            didAutoScroll = true
            listState.scrollToItem(idx)
        }
    }

    // The chapter list is THE SAME in one panel or two: it lives here once (chips + sections +
    // rows) and both branches of the if below call it as-is, never copy it.
    val episodeList: @Composable () -> Unit = {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                // In two panels this LazyColumn sits BELOW the TopAppBar (which here covers
                // tappable content, not a full-bleed poster), and a LazyColumn can't scroll above
                // offset 0 -- without this padding the first row (chips or the first chapter) stays
                // covered forever. In a single panel it stays at 0.dp, same as today.
                top = if (twoPanels) topInset else 0.dp,
                bottom = 32.dp + bottomInset,
            ),
        ) {
            // In a single panel the item card goes here inside, as always. In two panels it was
            // already drawn apart (further below, in the left panel) and isn't duplicated -- that's
            // why resumeIndex also counts it or not based on this same `twoPanels`.
            if (!twoPanels) {
                item { ItemHero(data = data, onPlayEpisode = onPlayEpisode) }
            }

            // Filtering only makes sense if there are 2+ different sites saved for this series --
            // with 0 or 1 site, the chip row wouldn't filter anything and would be pure visual noise.
            if (siteLabels.size >= 2) {
                item {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedSite == null,
                            onClick = { selectedSite = null },
                            label = { Text("Todos") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                        )
                        siteLabels.forEach { site ->
                            FilterChip(
                                selected = selectedSite == site,
                                onClick = { selectedSite = site },
                                label = { Text(site) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                            )
                        }
                    }
                }
            }

            bySection.forEach { (section, episodes) ->
                if (section.isNotBlank()) {
                    item {
                        Text(
                            section,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                        )
                    }
                }
                items(episodes, key = { it.id }) { ep ->
                    EpisodeRow(
                        episode = ep,
                        progress = data.progress[ep.id],
                        isCurrent = ep.id == currentEpisodeId,

                        // Real chapter title and image (TMDB). Only show up when it could tell which
                        // series/number this row is; otherwise the row falls back to the filename and
                        // to the on-device captured frame (or the series poster, if there's no frame
                        // either) -- see ThumbnailChoice further down.
                        tmdbTitle = tmdbTitles[ep.id],
                        tmdbStill = tmdbStills[ep.id],
                        tmdbFrame = tmdbFrames[ep.id],
                        tmdbOverview = tmdbOverviews[ep.id],
                        // Thumbnail fallback: chapters never bring their own still today -- both
                        // MagisEntities and DituEntities always save thumbPath = null, the same way
                        // the removed web source's `addWebSeriesEpisode` used to; only the series
                        // poster is known, and without this the row was left with an empty box.
                        fallbackThumb = data.thumbnailUrl,
                        state = downloadStates[ep.id] ?: DownloadDisplayState.NotDownloaded,
                        onPlay = { onPlayEpisode(ep.id) },
                        onDownload = if (canDownload(ep)) { { onDownloadEpisode(ep) } } else null,
                        onRetry = { onRetryEpisode(ep) },
                        onRequestAction = { action -> pendingConfirmation = ep to action },
                        onToggleWatched = onToggleWatched,
                    )
                }
            }
        }
    }

    // Landscape tablet: item card on the left, chapters on the right, both visible at the same
    // time. Nothing changes on a phone or in portrait -- it's the same list as always.
    if (twoPanels) {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    // Same reason as the LazyColumn's bottom next to it: without this, if the
                    // synopsis fills the panel, the last line ends up under the gesture bar.
                    .padding(bottom = bottomInset),
            ) {
                ItemHero(data = data, onPlayEpisode = onPlayEpisode)
            }
            Box(Modifier.weight(1.4f)) { episodeList() }
        }
    } else {
        episodeList()
    }

    DownloadConfirmDialog(
        action = pendingConfirmation?.second,
        chapterName = pendingConfirmation?.first?.let { tmdbTitles[it.id] ?: it.displayName },
        onConfirm = {
            pendingConfirmation?.let { (episode, action) -> onDownloadAction(episode, action) }
            pendingConfirmation = null
        },
        onClose = { pendingConfirmation = null },
    )
}

/**
 * The item's card: 16:9 image with a gradient, title, progress summary, play/continue button and
 * synopsis. On phone/portrait it's [DetailContent]'s [LazyColumn]'s first item; on a landscape
 * tablet it's drawn apart, in the left panel -- same content in both cases, never two copies.
 */
@Composable
private fun ItemHero(data: ItemDetail, onPlayEpisode: (String) -> Unit) {
    val resume = data.resumeEpisode
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(ArkivSurfaceHigh),
    ) {
        AsyncImage(
            model = data.thumbnailUrl,
            contentDescription = data.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(80.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, ArkivBlack),
                    ),
                ),
        )
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(data.title, style = MaterialTheme.typography.headlineMedium)
        Text(
            ChapterLabel.progressSummary(data, "videos"),
            color = ArkivTextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (resume != null) {
            Button(
                onClick = { onPlayEpisode(resume.id) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("  ${ChapterLabel.playButtonLabel(data)}", fontWeight = FontWeight.Bold)
            }
        }
        if (!data.description.isNullOrBlank()) {
            Text(
                data.description,
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * Short host ("serieskao.top") from an episode's `sourceRef`, to group the list filter by origin
 * site. Returns null for anything that isn't a real http URL -- which today is essentially
 * everything: Magis and Ditu both write an opaque ref (`magis1:...`, `ditu1:...`), not a URL, into
 * the field this reads (see `siteLabels` above). This only ever resolves for a legacy row saved by
 * the now-removed web source (a real page URL), a torrent magnet, or an episode saved before
 * `sourceRef` was persisted -- none of those has a "site" to show as a chip either. Uses plain
 * `java.net.URL(...).host` rather than inventing a second way to pull a host out of a URL.
 */
private fun siteLabelOf(sourceRef: String?): String? {
    if (sourceRef.isNullOrBlank()) return null
    val host = runCatching { java.net.URL(sourceRef).host }.getOrNull()?.lowercase()?.ifBlank { null }
        ?: return null
    return host.removePrefix("www.")
}

/**
 * Choosing which of the series' chapters to save to the device (all, some, or one).
 *
 * Over this screen's already-saved [Episode]s: a tri-state "select all" header, a per-season
 * header with its own check/uncheck (a long series has hundreds of rows and checking them one by
 * one isn't viable), and the same informational green checkmark for what's already saved.
 */
@Composable
private fun SaveEpisodesDialog(
    episodes: List<Episode>,
    alreadySaved: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<Episode>) -> Unit,
) {
    // The default selection is EVERYTHING that's missing, not plainly everything: the normal case
    // of opening this on a series that's already half-downloaded is "get me the rest", and
    // re-downloading what's already there would waste data and disk for nothing. If nothing's
    // missing, everything gets preselected anyway, so the dialog doesn't open empty with nothing
    // to confirm (re-downloading is a valid case, e.g. if the copy came out corrupted).
    val selected = remember(episodes, alreadySaved) {
        val pending = episodes.filterNot { it.id in alreadySaved }
        mutableStateListOf<String>().apply { addAll((pending.ifEmpty { episodes }).map { it.id }) }
    }
    val total = episodes.size
    val allSelected = selected.size == total && episodes.isNotEmpty()
    fun toggleAll(on: Boolean) {
        selected.clear()
        if (on) selected.addAll(episodes.map { it.id })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar en el dispositivo") },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(episodes.filter { it.id in selected }) },
            ) {
                Text("Guardar (${selected.size})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { toggleAll(!allSelected) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriStateCheckbox(
                        state = when {
                            allSelected -> ToggleableState.On
                            selected.isEmpty() -> ToggleableState.Off
                            else -> ToggleableState.Indeterminate
                        },
                        onClick = { toggleAll(!allSelected) },
                    )
                    Text(
                        if (allSelected) "Deseleccionar todo" else "Seleccionar todo",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${selected.size}/$total", style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()

                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    episodes.groupBy { it.section }.forEach { (section, eps) ->
                        val ids = eps.map { it.id }
                        if (section.isNotBlank()) {
                            item(key = "sec-$section") {
                                val allOfSection = ids.all { it in selected }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            if (allOfSection) selected.removeAll(ids)
                                            else selected.addAll(ids.filterNot { it in selected })
                                        }
                                        .padding(top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = allOfSection,
                                        onCheckedChange = { on ->
                                            if (on) selected.addAll(ids.filterNot { it in selected })
                                            else selected.removeAll(ids)
                                        },
                                    )
                                    Text(section, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.weight(1f))
                                    Text("${eps.size}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        items(eps, key = { it.id }) { ep ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = ep.id in selected,
                                    onCheckedChange = { on ->
                                        if (on) selected.add(ep.id) else selected.remove(ep.id)
                                    },
                                )
                                Text(
                                    ep.displayName,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (ep.id in alreadySaved) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Ya guardado en el dispositivo",
                                        tint = NucDownloadedGreen,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    progress: com.arkiv.player.data.db.PlaybackEntity?,
    isCurrent: Boolean,
    /** The series' thumbnail, for rows whose episode doesn't bring its own. */
    fallbackThumb: String?,
    tmdbTitle: String?,
    tmdbStill: String?,
    /** On-disk path of the captured frame. Beats [tmdbStill]; see [ThumbnailChoice]. */
    tmdbFrame: String?,
    /** The chapter's synopsis (TMDB). Null if it couldn't be resolved; the row simply doesn't show it. */
    tmdbOverview: String?,
    /** What its device download is doing: drives the right-side icon and the bar below. */
    state: DownloadDisplayState,
    onPlay: () -> Unit,
    /** Null = there's no way to download this chapter (see `DownloadSource.canDownload`). */
    onDownload: (() -> Unit)?,
    onRetry: () -> Unit,
    /** The user asked to undo something about the download; whoever receives this handles confirming it. */
    onRequestAction: (DownloadAction) -> Unit,
    onToggleWatched: (String, Boolean) -> Unit,
) {
    val watched = progress?.watched == true
    val cardShape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(cardShape)
            // Watched: subtle gray background ("already saw it"). Current one: white border ("I'm here").
            .background(if (watched) ArkivSurface else Color.Transparent)
            .then(
                if (isCurrent) Modifier.border(1.5.dp, Color.White, cardShape) else Modifier,
            )
            .clickable(onClick = onPlay),
    ) {
        // The inner padding lives here and not on the card: the download bar has to reach the
        // card's edges, not float 8dp from each side.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = 63.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ArkivSurfaceHigh),
            ) {
                // The captured frame first (the real scene of where you're at), then TMDB's still
                // (the chapter's photo), and last the series' fallback. The archive.org frame that
                // used to go here was deleted in this branch's pruning along with that source.
                // Chain built with ThumbnailChoice -- not by hand -- to not drift out of sync with
                // the rest of the screens.
                val thumb = ThumbnailChoice.choose(
                    tmdbFrame,
                    tmdbStill,
                    null,
                    fallbackThumb,
                )
                AsyncImage(
                    model = thumb,
                    contentDescription = tmdbTitle ?: episode.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.Center),
                )
                if (progress != null && progress.durationMs > 0 && !watched) {
                    LinearProgressIndicator(
                        progress = { progress.positionMs.toFloat() / progress.durationMs },
                        color = ArkivRed,
                        trackColor = Color(0x66000000),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    // The file name is the fallback, not the first choice: for our uploads it's
                    // "s01e03", which says nothing about which chapter it is.
                    //
                    // But the NUMBER wins and can't disappear: with plain `tmdbTitle`, a Magis
                    // chapter went from "E5  Daima T1_5" (the displayName already carries the
                    // number) to just "Panzy", and the list lost any way to tell which was which.
                    // The rule lives in [ChapterLabel.withName], shared with the TV detail screen.
                    ChapterLabel.withName(episode, tmdbTitle),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (watched) ArkivTextSecondary else MaterialTheme.colorScheme.onBackground,
                )
                // No chapter brings a real duration when it's first saved -- MagisEntities and
                // DituEntities both write durationSeconds = 0.0 on purpose, same as the removed
                // web/torrent sources used to (see ArkivRepository.kt). Showing "0:00" there looked
                // like a bug instead of a value that simply isn't known yet, so the row omits it.
                if (episode.durationSeconds > 0) {
                    Text(
                        formatDuration((episode.durationSeconds * 1000).toLong()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArkivTextSecondary,
                    )
                }
                // What the download is doing, IN WORDS. The bar and the icon already say it in
                // colors and shapes, but that's only legible if you already know what they mean:
                // "Bajando 42%" or the actual failure reason reads without translating anything.
                DownloadLabel.of(state)?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (state) {
                            is DownloadDisplayState.Failed, DownloadDisplayState.NeedsConfirmation -> ArkivRed
                            DownloadDisplayState.Done -> NucDownloadedGreen
                            else -> ArkivTextPrimary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Chapter synopsis (TMDB): only if it could be resolved. Clipped to 2 lines -- the
                // row already competes for space with the thumbnail and the buttons, it can't grow without a limit.
                if (!tmdbOverview.isNullOrBlank()) {
                    Text(
                        tmdbOverview,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            // A single slot for "save to the device", never two things at once: the checkmark for
            // already saved, what the download is doing, or the button to save it. Offering to
            // download what's already there adds nothing, and the row doesn't have the width for
            // one more icon either (a 112dp thumbnail + 2 IconButtons already leave it tight on a
            // narrow phone).
            //
            // The checkmark is informational, not an action -- that's why it's not an IconButton
            // (not tappable, doesn't take a 48dp slot) and doesn't share the "watched" red next to it.
            //
            // There used to be TWO slots: this one (Download, to the phone) and another with
            // CloudDownload that sent it to download to the NUC. The NUC one was removed because it
            // produced something nobody could see or play anymore since remote playback was
            // disconnected.
            //
            // With no way to download it, the save button doesn't show. If there's already a
            // download of it in the table (from before), the control stays, so it can be deleted.
            if (onDownload != null || state != DownloadDisplayState.NotDownloaded) {
                DownloadControl(
                    state = state,
                    onDownload = onDownload ?: {},
                    onRetry = onRetry,
                    onRequestAction = onRequestAction,
                )
            }
            IconButton(onClick = { onToggleWatched(episode.id, !watched) }) {
                Icon(
                    if (watched) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (watched) "Marcar no visto" else "Marcar visto",
                    tint = if (watched) ArkivRed else ArkivTextSecondary,
                )
            }
        }
        // The bar sits flush against the card's bottom edge and its full width: it's the only spot
        // that doesn't compete with the thumbnail or the two buttons, and it reads at a glance
        // scanning down the list.
        DownloadBar(state)
    }
}
