package com.arkiv.player.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.DownloadGroupPolicy
import com.arkiv.player.data.local.EpisodeDownloadStatus
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.data.local.FileSizeFormat
import com.arkiv.player.data.model.Episode
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.readingWidth
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

@Composable
fun DownloadsScreen(
    contentPadding: PaddingValues,
    onPlayEpisode: (String) -> Unit,
) {
    val graph = rememberGraph()
    val vm: DownloadsViewModel = viewModel(
        factory = viewModelFactory { initializer { DownloadsViewModel(graph.localDownloads, graph.repository) } },
    )
    val groups by vm.groups.collectAsStateWithLifecycle()

    // One-time notice from the ViewModel. It's the ONLY output this screen has when the queue
    // skips a download as a duplicate: in that case no row gets created, so the chapter keeps
    // showing as "not downloaded" and the tap would leave no visible trace.
    // Goes before the empty-list `return` so it holds on both paths.
    val context = androidx.compose.ui.platform.LocalContext.current
    val message by vm.message.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
        vm.messageShown()
    }

    if (groups.isEmpty()) {
        EmptyState(
            title = "Descargas",
            subtitle = "Todavía no has descargado ningún episodio. Usa el ícono de descarga en un episodio.",
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .readingWidth()
                .fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                Text(
                    "Descargas",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(groups, key = { it.itemId }) { group ->
                DownloadGroupSection(
                    group = group,
                    onPlay = onPlayEpisode,
                    onConfirm = vm::confirm,
                    onRetry = vm::retry,
                    onCancel = vm::cancel,
                    onRemove = vm::remove,
                    onDownload = { episodeId -> vm.download(episodeId, group.source) },
                    onCancelAll = { vm.cancelGroup(group) },
                    onRemoveAll = { vm.removeGroup(group) },
                    onRetryFailed = { vm.retryFailedGroup(group) },
                )
            }
        }
    }
}

/**
 * A row in the Downloads list. A single-episode item (a movie) has nothing to collapse and
 * shows flat, as before. One with several shows the header with cover + summary and, when
 * expanded, ALL of the item's chapters -- not just the ones that went through the queue: the
 * ones not downloaded yet carry their own download button.
 */
@Composable
private fun DownloadGroupSection(
    group: DownloadGroup,
    onPlay: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancelAll: () -> Unit,
    onRemoveAll: () -> Unit,
    onRetryFailed: () -> Unit,
) {
    if (group.isSingleEpisode) {
        // See DownloadGroupPolicy.buildGroups: an itemId only enters `groups` if it has at least
        // one row in `downloads`, so a movie's single episode is always tracked.
        val row = (group.episodes.firstOrNull()?.status as? EpisodeDownloadStatus.Tracked)?.row ?: return
        DownloadItem(
            row = row,
            onPlay = { if (row.state == LocalDownloadState.COMPLETED) onPlay(row.episodeId) },
            onConfirm = { onConfirm(row.episodeId) },
            onRetry = { onRetry(row.episodeId) },
            onCancel = { onCancel(row.episodeId) },
            onRemove = { onRemove(row.episodeId) },
        )
        return
    }

    // `rememberSaveable` (not `remember`): the `LazyColumn` with `key = { it.itemId }` tears down
    // the composition of groups that scroll out of the visible window, and without this a group
    // would lose its "expanded" state every time it scrolled out of view and back.
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        DownloadGroupHeader(
            group = group,
            expanded = expanded,
            onToggleExpanded = { expanded = !expanded },
            onCancelAll = onCancelAll,
            onRemoveAll = onRemoveAll,
            onRetryFailed = onRetryFailed,
        )
        if (expanded) {
            group.episodes.forEach { grouped ->
                Box(Modifier.padding(start = 20.dp)) {
                    when (val status = grouped.status) {
                        is EpisodeDownloadStatus.Tracked -> DownloadItem(
                            row = status.row,
                            onPlay = { if (status.row.state == LocalDownloadState.COMPLETED) onPlay(status.row.episodeId) },
                            onConfirm = { onConfirm(status.row.episodeId) },
                            onRetry = { onRetry(status.row.episodeId) },
                            onCancel = { onCancel(status.row.episodeId) },
                            onRemove = { onRemove(status.row.episodeId) },
                        )
                        EpisodeDownloadStatus.NotDownloaded -> NotDownloadedRow(
                            episode = grouped.episode,
                            itemId = group.itemId,
                            itemThumbnailUrl = group.itemThumbnailUrl,
                            onDownload = { onDownload(grouped.episode.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Collapsible header of a group: item cover, title, summary, and series-level actions. */
@Composable
private fun DownloadGroupHeader(
    group: DownloadGroup,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCancelAll: () -> Unit,
    onRemoveAll: () -> Unit,
    onRetryFailed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ArkivSurfaceHigh),
            ) {
                AsyncImage(
                    model = group.itemThumbnailUrl,
                    contentDescription = group.itemTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.itemTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    SourceBadge(group.source)
                }
                Text(
                    DownloadGroupPolicy.summarize(group.episodes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Contraer" else "Expandir",
                tint = ArkivTextSecondary,
            )
        }
        val activeIds = DownloadGroupPolicy.activeEpisodeIds(group)
        val failedIds = DownloadGroupPolicy.failedEpisodeIds(group)
        val trackedIds = DownloadGroupPolicy.trackedEpisodeIds(group)
        if (activeIds.isNotEmpty() || failedIds.isNotEmpty() || trackedIds.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (failedIds.isNotEmpty()) TextButton(onClick = onRetryFailed) { Text("Reintentar fallidos") }
                if (activeIds.isNotEmpty()) TextButton(onClick = onCancelAll) { Text("Cancelar todos") }
                if (trackedIds.isNotEmpty()) TextButton(onClick = onRemoveAll) { Text("Quitar todos") }
            }
        }
    }
}

/** Row for a chapter not downloaded yet: comes from the item's full catalog, not from `downloads`. */
@Composable
private fun NotDownloadedRow(
    episode: Episode,
    itemId: String,
    itemThumbnailUrl: String,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ArkivSurfaceHigh),
        ) {
            // The episode's own thumb, falling back to the item poster (see DownloadGroupPolicy.rowThumbnail).
            val thumb = DownloadGroupPolicy.rowThumbnail(episode.thumbPath, itemThumbnailUrl)
            AsyncImage(
                model = thumb,
                contentDescription = episode.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                episode.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "No descargado",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDownload) {
            Icon(Icons.Default.Download, contentDescription = "Descargar", tint = ArkivTextSecondary)
        }
    }
}

@Composable
private fun DownloadItem(
    row: DownloadRow,
    onPlay: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ArkivSurfaceHigh),
            ) {
                // The episode's own thumb, falling back to the item poster (see DownloadGroupPolicy.rowThumbnail).
                val thumb = DownloadGroupPolicy.rowThumbnail(row.thumbPath, row.itemThumbnailUrl)
                AsyncImage(
                    model = thumb,
                    contentDescription = row.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    SourceBadge(row.source)
                }
                Text(
                    row.itemTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stateLabel(row),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.state == LocalDownloadState.FAILED || row.state == LocalDownloadState.NEEDS_CONFIRMATION) {
                        ArkivRed
                    } else {
                        ArkivTextSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // STAGING renders with an INDETERMINATE bar, not `row.progress`. Nothing creates a
                // STAGING row anymore -- the NUC/arkiv-offline backend that used to own this phase
                // was removed with the rest of NUC downloads (Task 8), and today's only strategy
                // (Magis) writes DOWNLOADING directly (see `LocalDownloadWorker`). This branch is
                // only reachable for a row already sitting in the local `downloads` table from an
                // install that predates that removal. It's kept indeterminate rather than switched
                // to `row.progress` because that's what made the old NUC math (`items done / items
                // total`, one item per job) honest: with a single item the count could only ever
                // read 0/1 or 1/1, so a whole multi-minute HLS download looked stuck at 0%.
                when (row.state) {
                    LocalDownloadState.STAGING -> LinearProgressIndicator(
                        color = ArkivRed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(top = 6.dp),
                    )
                    LocalDownloadState.DOWNLOADING -> LinearProgressIndicator(
                        progress = { row.progress },
                        color = ArkivRed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(top = 6.dp),
                    )
                    else -> Unit
                }
            }
            if (row.state == LocalDownloadState.COMPLETED) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = ArkivRed)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            when (row.state) {
                LocalDownloadState.NEEDS_CONFIRMATION -> {
                    TextButton(onClick = onConfirm) { Text("Descargar igual") }
                    TextButton(onClick = onRemove) { Text("Descartar") }
                }
                LocalDownloadState.FAILED -> {
                    TextButton(onClick = onRetry) { Text("Reintentar") }
                    TextButton(onClick = onRemove) { Text("Quitar") }
                }
                // What's in flight or queued can be CANCELED (stops the download and keeps the
                // partial, so "Retry" resumes) or REMOVED (stops and deletes everything).
                LocalDownloadState.QUEUED, LocalDownloadState.STAGING, LocalDownloadState.DOWNLOADING -> {
                    TextButton(onClick = onCancel) { Text("Cancelar") }
                    TextButton(onClick = onRemove) { Text("Quitar") }
                }
                else -> TextButton(onClick = onRemove) { Text("Quitar") }
            }
        }
    }
}

/** Text shown to the user for each queue state. */
private fun stateLabel(row: DownloadRow): String = when (row.state) {
    LocalDownloadState.QUEUED -> "En cola"
    // No percentage: the backend can only report 0% or 100% for this phase (see the indeterminate
    // bar comment above), so a number here would lie.
    LocalDownloadState.STAGING -> "Preparando en el servidor…"
    // The error with the row still in `downloading` is a transient failure that WorkManager will
    // retry on its own (see DownloadRetryPolicy): showing it avoids it looking stuck.
    LocalDownloadState.DOWNLOADING ->
        row.error?.let { "Reintentando · $it" } ?: "Bajando ${(row.progress * 100).toInt()}%"
    LocalDownloadState.NEEDS_CONFIRMATION -> "Necesita confirmación · ${FileSizeFormat.formatSize(row.bytes)}"
    // The "error" on a completed row is not a failure: it's the reason nothing needed to be
    // downloaded (see DuplicateDownloadPolicy.ADOPTED_REASON, "Ya estaba descargado"). Showing it
    // avoids making it look like 461 MB got downloaded when they were already on disk under
    // another item.
    LocalDownloadState.COMPLETED -> row.error?.let { "Listo · $it" } ?: "Listo"
    LocalDownloadState.FAILED -> row.error ?: "Falló"
    else -> row.state
}

/**
 * File-origin badge. "torrent" and "web" are legacy `source` values from rows saved before this
 * branch's pruning. "magis" is today's real value (`DownloadSource.sourceFor`); any other value falls
 * through to "ARCHIVE".
 */
private fun sourceBadge(source: String): String = when (source) {
    "torrent" -> "TORRENT"
    "web" -> "WEB"
    "magis" -> "XUPER"
    else -> "ARCHIVE"
}

@Composable
private fun SourceBadge(source: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(ArkivSurfaceHigh)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            sourceBadge(source),
            style = MaterialTheme.typography.labelSmall,
            color = ArkivTextSecondary,
        )
    }
}
