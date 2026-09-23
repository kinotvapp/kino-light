package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.DownloadAction
import com.arkiv.player.ui.components.DownloadConfirmDialog
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.catalog.TmdbEpisode
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/**
 * A TMDB movie/series card (browse-only): title, synopsis, seasons and chapters.
 *
 * No longer offers playing from here -- "Buscar fuentes" opened a panel that only listed
 * archive.org results, deleted in this branch's pruning; Magis never got hooked up to this panel
 * (it always stayed empty, see the finding from the final review of
 * `docs/superpowers/specs/2026-09-08-arkiv-light-magis-poda-design.md`). The real path to play
 * Magis from TMDB already exists and stays intact: the search (`SearchScreen`/
 * `SearchViewModel.runSourceSearch`), reached from "Categorías" → a row → a card.
 */
@Composable
fun CineDetailScreen(
    tmdbId: Int,
    type: String,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {},
    deepLinkSeason: Int? = null,
    deepLinkEpisode: Int? = null,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    // Notification permission (API 33+): requested when a download to the device is triggered, so
    // the local downloads worker's "download complete" notification isn't silently dropped. See
    // rememberPostNotificationsRequest.
    val askNotifications = com.arkiv.player.ui.offline.rememberPostNotificationsRequest()
    // Shows "you already have that downloaded" when the queue skips a duplicate download (see
    // DuplicateDownloadPolicy): otherwise the button would look like it does nothing.
    val notifyDuplicates = com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice()
    // The per-source download control this screen used to show (observing `downloadRows` and
    // matching them with `data.local.DescargasPorFuente`) was archive.org search UI, removed with
    // the rest of that source in this branch's pruning; `DescargasPorFuente` itself was deleted as
    // dead code in the cleanup. `pendingConfirmation` stays wired to the dialog below, but nothing
    // sets it anymore.
    var pendingConfirmation by remember { mutableStateOf<Pair<DownloadRow, DownloadAction>?>(null) }

    var detail by remember { mutableStateOf<TmdbDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var episodes by remember { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var loadingEps by remember { mutableStateOf(false) }

    LaunchedEffect(tmdbId, type) {
        loading = true
        val d = runCatching { graph.tmdbApi.detail(type, tmdbId) }.getOrNull()
        detail = d
        // If a season deep link comes in, honor it instead of overwriting it with the default
        // season (otherwise this effect runs after LaunchedEffect(deepLinkSeason) and clobbers it).
        selectedSeason = deepLinkSeason
            ?: d?.seasons?.firstOrNull { it.seasonNumber > 0 }?.seasonNumber
            ?: d?.seasons?.firstOrNull()?.seasonNumber
        loading = false
    }

    // Load the chosen season's chapters (on demand).
    LaunchedEffect(selectedSeason, detail) {
        val s = selectedSeason
        val d = detail
        if (s == null || d == null || !d.isSeries) { episodes = emptyList(); return@LaunchedEffect }
        loadingEps = true
        // `.orEmpty()`: only a list gets painted here, so "couldn't query" (null) and "TMDB had no
        // chapters" look the same. The distinction only matters to whoever caches to the database.
        episodes = runCatching { graph.tmdbApi.seasonEpisodes(d.id, s) }.getOrNull().orEmpty()
        loadingEps = false
    }

    LaunchedEffect(deepLinkSeason) {
        if (deepLinkSeason != null && selectedSeason != deepLinkSeason) selectedSeason = deepLinkSeason
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        val d = detail
        when {
            loading -> CircularProgressIndicator(color = ArkivRed, modifier = Modifier.align(Alignment.Center))
            d == null -> Text("No se pudo cargar.", color = ArkivTextSecondary, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ArkivSurfaceHigh)) {
                    AsyncImage(
                        model = d.backdropUrl.ifBlank { d.posterUrl },
                        contentDescription = d.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack))))
                }
                Column(Modifier.padding(16.dp)) {
                    Text(d.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    if (d.year.isNotBlank()) {
                        Text(d.year, color = ArkivTextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (d.overview.isNotBlank()) {
                        Text(d.overview, color = ArkivTextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }

                    if (d.isSeries) {
                        Text("Temporadas", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            d.seasons.forEach { s ->
                                FilterChip(
                                    selected = selectedSeason == s.seasonNumber,
                                    onClick = { selectedSeason = s.seasonNumber },
                                    label = { Text(if (s.seasonNumber == 0) "Especiales" else "T${s.seasonNumber}") },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                                )
                            }
                        }
                        if (loadingEps) {
                            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                                Text("Cargando capítulos…", color = ArkivTextSecondary)
                            }
                        }
                        episodes.forEach { ep ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${ep.episode}. ${ep.name}", color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (ep.air.isNotBlank()) Text(ep.air, color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0x88000000)),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White) }
    }

    // Same question and same wording as in the library: it's the same action on the same queue.
    DownloadConfirmDialog(
        action = pendingConfirmation?.second,
        chapterName = pendingConfirmation?.first?.displayName,
        onConfirm = {
            pendingConfirmation?.let { (row, action) ->
                scope.launch {
                    when (action) {
                        DownloadAction.CANCEL -> graph.localDownloads.cancel(row.episodeId)
                        DownloadAction.REMOVE_FROM_QUEUE, DownloadAction.DELETE ->
                            graph.localDownloads.remove(row.episodeId)
                    }
                }
            }
            pendingConfirmation = null
        },
        onClose = { pendingConfirmation = null },
    )
}
