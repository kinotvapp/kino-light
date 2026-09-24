package com.arkiv.player.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.thumbnails.ThumbnailChoice
import com.arkiv.player.ui.components.ContinueCard
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.components.PosterCard
import com.arkiv.player.ui.components.SectionHeader
import com.arkiv.player.ui.home.HomeViewModel
import com.arkiv.player.ui.gridColumns
import com.arkiv.player.ui.isLandscapeTablet
import com.arkiv.player.ui.libraryMeta
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class LibFilter(val label: String) { ALL("Todas"), MOVIES("Películas"), SERIES("Series") }

private val SeriesBadgeColor = Color(0xE6444444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val graph = rememberGraph()
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(graph.repository, graph.settings, graph.magisHomeCatalog, graph.hasInternet, graph.homeReloads) } },
    )
    // Ordered by what you last watched: what you're currently watching comes first, no need to
    // scroll down for it.
    val library by vm.orderedLibrary.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Shows "you already have that downloaded" when the queue skips a duplicate download (see
    // DuplicateDownloadPolicy): otherwise the menu would look like it does nothing.
    val notifyDuplicates = com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice()

    // Items with (at least) one episode already saved on the device, for the card's checkmark.
    val savedIds by graph.localDownloads.observeRows()
        .map { rows -> rows.filter { it.state == LocalDownloadState.COMPLETED }.map { it.itemId }.toSet() }
        .collectAsStateWithLifecycle(initialValue = emptySet())

    // Item with the contextual (long-press) menu open.
    var menuRow by remember { mutableStateOf<LibraryRow?>(null) }
    // Item pending delete confirmation.
    var confirmDeleteRow by remember { mutableStateOf<LibraryRow?>(null) }

    // On tapping an item: if it's a movie, play it directly; if it's a series, open the list.
    fun open(row: LibraryRow) {
        if (row.isMovie) {
            scope.launch {
                val ep = graph.repository.firstEpisodeId(row.identifier)
                if (ep != null) onPlayEpisode(ep) else onOpenItem(row.identifier)
            }
        } else {
            onOpenItem(row.identifier)
        }
    }

    if (library.isEmpty() && continueWatching.isEmpty()) {
        EmptyState(
            title = "Tu biblioteca está vacía",
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    var filter by remember { mutableStateOf(LibFilter.ALL) }
    val hasMovies = library.any { it.isMovie }
    val hasSeries = library.any { !it.isMovie }
    val filtered = when (filter) {
        LibFilter.ALL -> library
        LibFilter.MOVIES -> library.filter { it.isMovie }
        LibFilter.SERIES -> library.filter { !it.isMovie }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns(3, isLandscapeTablet())),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (continueWatching.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("Continuar viendo")
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(continueWatching, key = { it.episodeId }) { row ->
                        val progress = if (row.durationMs > 0) {
                            row.positionMs.toFloat() / row.durationMs
                        } else 0f
                        // The archive.org thumb that used to go in the middle was deleted in this branch's pruning.
                        val thumb = ThumbnailChoice.choose(
                            row.framePath,
                            null,
                            row.itemThumbnailUrl,
                        )
                        ContinueCard(
                            title = row.itemTitle,
                            subtitle = row.displayName,
                            imageUrl = thumb,
                            progress = progress,
                            modifier = Modifier.width(240.dp),
                            onClick = { onPlayEpisode(row.episodeId) },
                        )
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader("Mi biblioteca")
        }
        // Filter chips (only if there's both types, so as not to get in the way).
        if (hasMovies && hasSeries) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibFilter.values().forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArkivRed,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }
        }
        items(filtered, key = { it.identifier }) { row ->
            PosterCard(
                title = row.title,
                imageUrl = row.thumbnailUrl,
                badge = if (row.isMovie) "PELÍCULA" else "SERIE",
                badgeColor = if (row.isMovie) ArkivRed else SeriesBadgeColor,
                meta = libraryMeta(row.isMovie, row.durationSeconds, row.episodeCount),
                saved = row.identifier in savedIds,
                // Long-press opens the menu (detail/download + change category).
                onLongClick = { menuRow = row },
                onClick = { open(row) },
            )
        }
    }

    // Which sources something can be downloaded with (today, only Magis). See `DownloadSource.hasStrategy`.
    val strategies = remember { graph.downloadStrategies.keys }
    menuRow?.let { row ->
        ModalBottomSheet(onDismissRequest = { menuRow = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Text(
                    if (row.isMovie) "Ahora es: Película" else "Ahora es: Serie",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SheetAction("Ver detalle / descargar") { onOpenItem(row.identifier); menuRow = null }
                // Only if there's something to download it with: with no strategy for its source
                // (Caracol, or an old archive.org row) the download used to end up FAILED with
                // "Fuente no soportada" after accepting it. An option that's going to fail isn't shown.
                if (com.arkiv.player.data.local.DownloadSource.hasStrategy(row.source, strategies)) SheetAction("Guardar en el dispositivo") {
                    scope.launch {
                        // A movie is a single-episode item; a series is saved from its detail
                        // screen, chapter by chapter (queuing 200 chapters from a contextual menu
                        // with no way to say which makes no sense).
                        val episodes = graph.repository.episodesOf(row.identifier)
                        val single = episodes.singleOrNull()
                        if (single != null) {
                            // `row.source` comes straight from `items.source` ("archive" | "magis" |
                            // "ditu", or the legacy "torrent"/"web" from items saved before this
                            // branch, which no longer have a download strategy — see
                            // `AppGraph.downloadStrategies`): it's the real stored value, not a
                            // heuristic.
                            notifyDuplicates(listOf(graph.localDownloads.enqueue(single.id, row.source)))
                        } else {
                            onOpenItem(row.identifier)
                        }
                    }
                    menuRow = null
                }
                if (row.isMovie) {
                    SheetAction("Marcar como serie") {
                        scope.launch { graph.repository.setCategory(row.identifier, false) }
                        menuRow = null
                    }
                } else {
                    SheetAction("Marcar como película") {
                        scope.launch { graph.repository.setCategory(row.identifier, true) }
                        menuRow = null
                    }
                }
                if (row.categoryOverride != null) {
                    SheetAction("Volver a detección automática") {
                        scope.launch { graph.repository.setCategory(row.identifier, null) }
                        menuRow = null
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SheetAction("Quitar de mi biblioteca", color = ArkivRed) {
                    confirmDeleteRow = row
                    menuRow = null
                }
            }
        }
    }

    confirmDeleteRow?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDeleteRow = null },
            title = { Text("¿Quitar de mi biblioteca?") },
            text = { Text(row.title) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { graph.repository.removeItem(row.identifier) }
                    confirmDeleteRow = null
                }) {
                    Text("Quitar", color = ArkivRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteRow = null }) { Text("Cancelar") }
            },
        )
    }
}

/** Action row inside the contextual bottom sheet. */
@Composable
private fun SheetAction(text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
