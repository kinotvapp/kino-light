package com.arkiv.player.ui.titleinfo

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.local.DownloadAction
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.ui.components.DownloadConfirmDialog
import com.arkiv.player.ui.components.DownloadControl
import com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice
import com.arkiv.player.ui.offline.rememberPostNotificationsRequest
import com.arkiv.player.ui.readingWidth
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/** A download row action waiting for the person's confirmation (all three are expensive to undo by accident). */
private data class PendingAction(val episodeId: String, val chapterName: String?, val action: DownloadAction)

/**
 * The information page a Magis or plugin title opens on the phone: backdrop and title, the main button, a
 * download button (movies), genres and synopsis, and for a series the season header and its
 * chapters. Everything comes from [TitleInfoViewModel]; this only draws it.
 *
 * Full screen, no tab bar (the app's top bar only shows on tab routes). One `LazyColumn` so the
 * backdrop scrolls away and a 150-chapter series is not built eagerly.
 */
@Composable
fun TitleInfoScreen(
    item: CatalogItem,
    origin: TitleOrigin,
    onBack: () -> Unit,
    onPlay: (episodeId: String) -> Unit,
    onConfigurePlugin: (pluginId: String) -> Unit,
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: TitleInfoViewModel = viewModel(
        factory = viewModelFactory { initializer { titleInfoViewModel(graph, item, titleSourceFor(graph, origin, item)) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    // Back from Configurar: ask again, with the new settings (the plugin's own Ver más does the same).
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if ((vm.state.value.episodes as? EpisodesState.Failed)?.setupPluginId != null) vm.retry()
    }
    val askNotifications = rememberPostNotificationsRequest()
    val notifyDuplicates = rememberDuplicateDownloadNotice()
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var seasonMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is TitleInfoEvent.OpenPlayer -> onPlay(event.episodeId)
                is TitleInfoEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                is TitleInfoEvent.Downloaded -> {
                    if (event.noticeDuplicates) notifyDuplicates(event.outcomes)
                    event.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // The same executor DetailScreen uses for a row's download actions: `cancel` keeps the
    // partial file, `remove` deletes the row and the file.
    fun retryDownload(episodeId: String) {
        scope.launch { graph.localDownloads.retry(episodeId) }
    }
    fun runAction(p: PendingAction) {
        scope.launch {
            when (p.action) {
                DownloadAction.CANCEL -> graph.localDownloads.cancel(p.episodeId)
                DownloadAction.REMOVE_FROM_QUEUE, DownloadAction.REMOVE_REFUSED, DownloadAction.DELETE -> graph.localDownloads.remove(p.episodeId)
            }
        }
    }

    val info = state.info
    val movieDownloadId = state.movieEpisodeId

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // `readingWidth()` caps the width on a landscape tablet and must come BEFORE fillMaxSize.
            LazyColumn(Modifier.readingWidth().fillMaxSize()) {
                item(key = "hero") { Hero(info) }
                item(key = "actions") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        info.metaLine().takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
                        }
                        state.source.badge?.let { badge ->
                            Text(
                                "Desde ${badge.label}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(badge.colorArgb),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton(state = state, onClick = { vm.play() })
                        if (info.kind == TitleKind.MOVIE && vm.canDownload) {
                            Spacer(Modifier.height(10.dp))
                            MovieDownloadRow(
                                download = state.downloads[movieDownloadId] ?: DownloadDisplayState.NotDownloaded,
                                onDownload = { askNotifications(); vm.downloadMovie() },
                                onRetry = { retryDownload(movieDownloadId) },
                                onRequestAction = { action -> pending = PendingAction(movieDownloadId, info.title, action) },
                            )
                        }
                        if (info.genres.isNotEmpty()) {
                            Text(
                                info.genres.joinToString("  ·  "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArkivTextSecondary,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                        if (info.tagline.isNotBlank()) {
                            Text(
                                info.tagline,
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                color = ArkivTextSecondary,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                        Synopsis(info.synopsis)
                        info.creditLines(maxCast = 6).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = ArkivTextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
                if (info.kind == TitleKind.SERIES) {
                    item(key = "season-header") {
                        SeasonHeader(
                            state = state,
                            canDownload = vm.canDownload,
                            menuOpen = seasonMenuOpen,
                            onMenu = { seasonMenuOpen = it },
                            onSelect = vm::selectSeason,
                            onDownloadSeason = { askNotifications(); vm.downloadSeason() },
                        )
                    }
                    when (val episodes = state.episodes) {
                        EpisodesState.None -> Unit
                        EpisodesState.Loading -> items(3) { SkeletonRow() }
                        is EpisodesState.Failed -> item(key = "failed") {
                            FailedBlock(episodes.message, episodes.setupPluginId, onRetry = vm::retry, onConfigure = onConfigurePlugin)
                        }
                        is EpisodesState.Loaded -> items(state.visibleChapters, key = { it.listKey }) { chapter ->
                            val id = state.chapterEpisodeId(chapter)
                            EpisodeRow(
                                chapter = chapter,
                                fallbackImage = info.backdrop ?: info.poster,
                                progress = state.progress[id],
                                download = state.downloads[id] ?: DownloadDisplayState.NotDownloaded,
                                downloadEnabled = vm.canDownload,
                                onPlay = { vm.play(chapter) },
                                onDownload = { askNotifications(); vm.downloadChapters(listOf(chapter.number)) },
                                onRetry = { retryDownload(id) },
                                onRequestAction = { action -> pending = PendingAction(id, chapterName(chapter), action) },
                            )
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(32.dp)) }
            }
        }
        BackButton(onBack, Modifier.align(Alignment.TopStart))
    }

    DownloadConfirmDialog(
        action = pending?.action,
        chapterName = pending?.chapterName,
        onConfirm = { pending?.let { runAction(it) }; pending = null },
        onClose = { pending = null },
    )
}

@Composable
private fun Hero(info: TitleInfo) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        AsyncImage(
            model = info.backdrop ?: info.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack))))
        Text(
            info.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onBack,
        modifier = modifier
            .statusBarsPadding()
            .padding(8.dp)
            .size(40.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
    }
}

/** "Reproducir" / "Continuar" / "Reproducir episodio N"; a spinner while the play resolves. */
@Composable
private fun PrimaryButton(state: TitleInfoState, onClick: () -> Unit) {
    val primary = state.primary
    val label = primary?.label ?: if (state.episodes is EpisodesState.Failed) "No disponible" else "Cargando…"
    Button(
        onClick = onClick,
        enabled = primary != null && !state.resolving,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = Color.White.copy(alpha = 0.35f),
            disabledContentColor = Color.Black.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (state.resolving) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The movie's download: a grey bar that says what the download is doing, with the shared control at its end. */
@Composable
private fun MovieDownloadRow(
    download: DownloadDisplayState,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onRequestAction: (DownloadAction) -> Unit,
) {
    val idle = download == DownloadDisplayState.NotDownloaded
    Surface(
        color = ArkivSurfaceHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (idle) Modifier.clickable(onClick = onDownload) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Text(
                downloadLabel(download),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            DownloadControl(state = download, onDownload = onDownload, onRetry = onRetry, onRequestAction = onRequestAction)
        }
    }
}

/** Three lines and "Más" to expand; nothing at all when there is no synopsis. */
@Composable
private fun Synopsis(text: String) {
    if (text.isBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        // Without measuring overflow: a short synopsis never needs the toggle.
        if (text.length > 140) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "Menos" else "Más", color = Color.White)
            }
        }
    }
}

/** "Temporada 1 · 12 episodios" with, when the portal lists more seasons, a "▾" selector; and "Descargar temporada". */
@Composable
private fun SeasonHeader(
    state: TitleInfoState,
    canDownload: Boolean,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onSelect: (SeasonRef) -> Unit,
    onDownloadSeason: () -> Unit,
) {
    val current = state.currentSeason
    val header = if (current != null) {
        seasonHeader(current, state.visibleChapters.size)
    } else {
        seasonHeader(state.info.seasonNumber, state.info.episodeCount)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 4.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (state.showSeasonSelector) {
                TextButton(onClick = { onMenu(true) }, contentPadding = PaddingValues(0.dp)) {
                    Text("${header.ifBlank { "Temporadas" }} ▾", color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
                    state.seasons.forEach { season ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    season.label,
                                    fontWeight = if (state.isCurrentSeason(season)) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = { onMenu(false); onSelect(season) },
                        )
                    }
                }
            } else {
                Text(header, color = Color.White, style = MaterialTheme.typography.titleSmall)
            }
        }
        if (canDownload && state.episodes is EpisodesState.Loaded) {
            TextButton(onClick = onDownloadSeason) { Text("Descargar temporada") }
        }
    }
}

@Composable
private fun EpisodeRow(
    chapter: GatewayEpisode,
    fallbackImage: String?,
    progress: PlaybackEntity?,
    download: DownloadDisplayState,
    downloadEnabled: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onRequestAction: (DownloadAction) -> Unit,
) {
    val fraction = if (progress != null && progress.durationMs > 0 && !progress.watched) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .width(120.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(ArkivSurfaceHigh),
        ) {
            AsyncImage(
                model = chapter.still ?: fallbackImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (fraction > 0f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = ArkivRed,
                    trackColor = Color.Black.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                chapterLine(chapter),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            chapter.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (progress?.watched == true) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Vista", tint = ArkivTextSecondary)
        }
        if (downloadEnabled) {
            DownloadControl(
                state = download,
                onDownload = onDownload,
                onRetry = onRetry,
                onRequestAction = onRequestAction,
            )
        }
    }
}

@Composable
private fun SkeletonRow() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(68.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(ArkivSurfaceHigh),
    )
}

@Composable
private fun FailedBlock(message: String, setupPluginId: String?, onRetry: () -> Unit, onConfigure: (String) -> Unit) {
    val fixed = "No se pudieron cargar los episodios"
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(fixed, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        message.takeIf { it.isNotBlank() && it != fixed }?.let {
            Text(it, color = ArkivTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { if (setupPluginId != null) onConfigure(setupPluginId) else onRetry() }) {
            Text(if (setupPluginId != null) "Configurar" else "Reintentar")
        }
    }
}
