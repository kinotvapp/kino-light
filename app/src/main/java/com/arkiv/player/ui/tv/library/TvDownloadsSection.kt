package com.arkiv.player.ui.tv.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.library.DiskSpace
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.DownloadGroupPolicy
import com.arkiv.player.ui.downloads.DownloadsViewModel
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.tv.arkivTvButtonBorder
import com.arkiv.player.ui.tv.arkivTvButtonColors
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Downloads to the device, seen from the TV.
 *
 * Until now the TV had NO downloads screen at all, even though the search box's "Save whole
 * season" queues N downloads to the device's disk. On a Fire TV Stick that fills up without warning.
 *
 * One row per SERIES, not per chapter: `DownloadsViewModel` already exposes group actions and
 * the real case is a whole season queued at once. A per-chapter list would be one more nested
 * screen to navigate with the D-pad, for no gain.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDownloadsSection(onPlayEpisode: (String) -> Unit, modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val vm: DownloadsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DownloadsViewModel(graph.localDownloads, graph.repository) }
        },
    )
    val groups by vm.groups.collectAsStateWithLifecycle()
    // The itemId is saved, not the whole `DownloadGroup`: if while the dialog is open a download
    // finishes or another one gets queued, a group captured at click time would end up with stale
    // `hasActive`/`hasFailed` and old episodes. Looking it up again in `groups` on every
    // recomposition, the dialog always reads the current state.
    var actionsId by remember { mutableStateOf<String?>(null) }
    val actionsGroup = actionsId?.let { id -> groups.firstOrNull { it.itemId == id } }

    val used = DiskSpace.usedByDownloads(groups)
    // Re-measured every time what's used changes (a download finished, something got deleted).
    // `StatFs` touches the filesystem, so it goes off the main thread.
    // Nullable on purpose: before the first measurement it used to start at 0L and that 0L got
    // painted as "0 MB free" -- on a nearly full device that reads as a false alarm. With null,
    // nothing is drawn until the real value is in.
    val free by produceState<Long?>(initialValue = null, used) {
        value = withContext(Dispatchers.IO) { graph.localDownloads.freeSpaceBytes() }
    }

    // Same clearance against the edges as the rest of the library (see SAFE_H/SAFE_V): a section
    // with a different margin is noticeable the moment you switch sections with the remote.
    Column(modifier.fillMaxSize().padding(horizontal = SAFE_H, vertical = SAFE_V)) {
        Text("Descargas", style = MaterialTheme.typography.headlineSmall, color = ArkivTextPrimary)
        free?.let { freeBytes ->
            Text(
                DiskSpace.summary(freeBytes, used),
                style = MaterialTheme.typography.labelLarge,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }

        if (groups.isEmpty()) {
            Text(
                "No hay nada descargado en este aparato.\nGuardá una serie desde el buscador y va a aparecer acá.",
                style = MaterialTheme.typography.bodyLarge,
                color = ArkivTextSecondary,
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(groups, key = { it.itemId }) { group ->
                Card(
                    onClick = { actionsId = group.itemId },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            group.itemTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = ArkivTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            DownloadGroupPolicy.summarize(group.episodes),
                            style = MaterialTheme.typography.labelMedium,
                            color = ArkivTextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    // If the group disappeared from `groups` (the last download got removed while the dialog was
    // open), `actionsGroup` comes back null and the dialog simply stops being drawn -- no separate
    // effect needed to "close it".
    actionsGroup?.let { group ->
        TvDownloadActionsDialog(
            group = group,
            onCancel = { vm.cancelGroup(group); actionsId = null },
            onRetry = { vm.retryFailedGroup(group); actionsId = null },
            onRemove = { vm.removeGroup(group); actionsId = null },
            onPlayEpisode = { episodeId -> onPlayEpisode(episodeId); actionsId = null },
            onDismiss = { actionsId = null },
        )
    }
}

/** Which confirmation step is open inside [TvDownloadActionsDialog], if any. */
private enum class DownloadConfirmation { STOP, REMOVE }

/**
 * A download group's actions, in a dialog and not as buttons inside the row: with the D-pad,
 * several buttons per row multiply focus jumps and make it easy to press the wrong one —and here
 * the wrong one deletes gigabytes—.
 *
 * Only what the group's state allows gets offered: "Detener"/"Reintentar" if there's something
 * active/failed, "Reproducir" if at least one episode is already downloaded. "Quitar del
 * dispositivo" is always there: it's what frees up disk.
 *
 * Both "Detener lo que está bajando" and "Quitar del dispositivo" ask for confirmation: the
 * original spec already asked to confirm cancel-and-delete, and before only "Quitar" had it. An
 * enum ([DownloadConfirmation]) and not two separate booleans, because the two confirmation steps
 * are mutually exclusive -- with two booleans you'd have to manually make sure both were never
 * `true` at once.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDownloadActionsDialog(
    group: DownloadGroup,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onPlayEpisode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasActive = DownloadGroupPolicy.activeEpisodeIds(group).isNotEmpty()
    val hasFailed = DownloadGroupPolicy.failedEpisodeIds(group).isNotEmpty()
    val playableEpisode = DownloadGroupPolicy.firstPlayableEpisodeId(group)
    var confirming by remember { mutableStateOf<DownloadConfirmation?>(null) }
    val focus = remember { FocusRequester() }
    // Same retry pattern as `TvLibraryItemDialog`: it's the only dialog in the feature that
    // deletes gigabytes without handling focus, so the D-pad could end up with no owner at the
    // confirmation step. Keying on `confirming` makes focus jump again whenever the step changes
    // (from the action list to either confirmation, or back).
    LaunchedEffect(confirming) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { focus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ArkivSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                group.itemTitle,
                style = MaterialTheme.typography.titleMedium,
                color = ArkivTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                DownloadGroupPolicy.summarize(group.episodes),
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
            )
            when (confirming) {
                DownloadConfirmation.REMOVE -> {
                    Text(
                        "Se borran del disco los archivos ya bajados de esta serie. La serie sigue en tu biblioteca.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                    )
                    Button(onClick = onRemove, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Sí, borrar del dispositivo", maxLines = 1)
                    }
                    // Focus goes to the safe button, not the destructive one: with the remote a
                    // double OK (normal when the UI takes a frame to compose) can arrive before
                    // the user manages to read the warning, and here what gets deleted is
                    // gigabytes.
                    Button(onClick = { confirming = null }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                        Text("Cancelar", maxLines = 1)
                    }
                }
                DownloadConfirmation.STOP -> {
                    Text(
                        "Se corta lo que está bajando ahora de esta serie. Los capítulos ya descargados no se tocan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                    )
                    Button(onClick = onCancel, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Sí, detener", maxLines = 1)
                    }
                    Button(onClick = { confirming = null }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                        Text("Cancelar", maxLines = 1)
                    }
                }
                null -> {
                    if (hasActive) {
                        Button(onClick = { confirming = DownloadConfirmation.STOP }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                            Text("Detener lo que está bajando", maxLines = 1)
                        }
                    }
                    if (hasFailed) {
                        Button(onClick = onRetry, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                            Text("Reintentar lo que falló", maxLines = 1)
                        }
                    }
                    if (playableEpisode != null) {
                        Button(onClick = { onPlayEpisode(playableEpisode) }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                            Text("Reproducir", maxLines = 1)
                        }
                    }
                    Button(onClick = { confirming = DownloadConfirmation.REMOVE }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Quitar del dispositivo", maxLines = 1)
                    }
                    // Same criterion here: by default focus lands on the safe option, not the one
                    // that starts the path toward deleting.
                    Button(onClick = onDismiss, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                        Text("Volver", maxLines = 1)
                    }
                }
            }
        }
    }
}
