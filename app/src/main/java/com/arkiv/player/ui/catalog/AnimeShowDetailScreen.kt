package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.DownloadAction
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.local.ChapterDownloadState
import com.arkiv.player.ui.components.RowDownload
import com.arkiv.player.ui.components.DownloadConfirmDialog
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeShowDetailScreen(
    anilistId: Long,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {},
    deepLinkEpisode: Int? = null,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf<AnimeShow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The per-source download control this screen used to show (observing `downloadRows` and
    // matching them with `data.local.DescargasPorFuente`) was archive.org search UI, removed with
    // the rest of that source in this branch's pruning; `DescargasPorFuente` itself was deleted as
    // dead code in the cleanup. `pendingConfirmation` stays wired to the dialog below, but nothing
    // sets it anymore.
    var pendingConfirmation by remember { mutableStateOf<Pair<DownloadRow, DownloadAction>?>(null) }

    // Expanded episodes (key = episode number).
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    // Episodes requested by hand (outside the 1..total range), e.g. absolute numbering for long-runners.
    val manualEpisodes = remember { mutableStateListOf<Int>() }
    var manualEpText by remember { mutableStateOf("") }

    LaunchedEffect(anilistId) {
        loading = true
        show = runCatching { graph.aniListApi.details(anilistId) }.getOrNull()
        loading = false
    }

    // Per-episode source search was archive.org ([graph.api], deleted in this branch's pruning:
    // see CLAUDE.md "Cero servidor propio"); magis has no wiring here yet (see Task 6 of the
    // pruning plan, "Simplificar búsqueda a solo-Magis").

    // Optional deep link (handoff from the phased search): as soon as the show loads, expand the
    // requested episode once. Added to manualEpisodes (like the "Ir al episodio" button) so it
    // also renders if it falls outside the 1..total range (absolute numbering).
    var animeDeepLinkHandled by remember { mutableStateOf(false) }
    LaunchedEffect(show, deepLinkEpisode) {
        if (animeDeepLinkHandled) return@LaunchedEffect
        show ?: return@LaunchedEffect
        val ep = deepLinkEpisode ?: return@LaunchedEffect
        animeDeepLinkHandled = true
        if (ep !in manualEpisodes) manualEpisodes.add(ep)
        expanded[ep] = true
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        val s = show
        when {
            loading -> CircularProgressIndicator(color = ArkivRed, modifier = Modifier.align(Alignment.Center))
            s == null -> Text(
                "No se pudo cargar el anime.",
                color = ArkivTextSecondary,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ArkivSurfaceHigh)) {
                    AsyncImage(
                        model = s.bannerUrl.ifBlank { s.posterUrl },
                        contentDescription = s.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack))))
                }
                Column(Modifier.padding(16.dp)) {
                    Text(s.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(
                        buildString {
                            if (s.year > 0) append(s.year)
                            if (s.scorePct > 0) append("  ·  ★ ${s.scorePct / 10.0}")
                            if (s.episodes > 0) append("  ·  ${s.episodes} eps")
                        },
                        color = ArkivTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (s.genres.isNotEmpty()) {
                        Text(
                            s.genres.joinToString(" · "),
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (s.description.isNotBlank()) {
                        Text(
                            s.description,
                            color = ArkivTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    // MOVIE/MUSIC (or any single-episode format, e.g. OVA/SPECIAL) has no real
                    // "1..N" list: it's a single playback per title.
                    val singlePlay = s.format == "MOVIE" || s.format == "MUSIC" || s.episodes == 1

                    if (singlePlay) {
                        Text(
                            "Reproducir",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
                        )
                        // This title's source was archive.org, deleted in this branch's pruning
                        // (see CLAUDE.md "Cero servidor propio"); magis has no wiring here yet.
                        Text(
                            "No se encontraron fuentes para este título.",
                            color = ArkivTextSecondary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        val total = if (s.episodes > 0) s.episodes else 0
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = manualEpText,
                                onValueChange = { manualEpText = it.filter(Char::isDigit) },
                                label = { Text(if (total == 0) "Ir al episodio (en emisión)" else "Ir al episodio") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    val n = manualEpText.toIntOrNull()
                                    if (n != null && n > 0) {
                                        if (n !in manualEpisodes) manualEpisodes.add(n)
                                        expanded[n] = true
                                        manualEpText = ""
                                    }
                                },
                                enabled = manualEpText.toIntOrNull()?.let { it > 0 } == true,
                                colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                            ) { Text("Ir") }
                        }
                        val displayEpisodes = (if (total > 0) (1..total).toList() else emptyList()) +
                            manualEpisodes.filter { it > total }.sorted()
                        displayEpisodes.forEach { ep ->
                            val open = expanded[ep] ?: false
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded[ep] = !open }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null, tint = Color.White,
                                )
                                Text(
                                    "Episodio $ep",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (open) {
                                // This episode's source was archive.org, deleted in this branch's
                                // pruning; magis has no wiring here yet.
                                Text(
                                    "Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }

                    if (error != null) {
                        Text(error!!, color = ArkivRed, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0x88000000)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
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

    // Confirmation to cancel / remove from queue / delete. Same question and same wording as in
    // the library: it's the same action on the same queue.
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
