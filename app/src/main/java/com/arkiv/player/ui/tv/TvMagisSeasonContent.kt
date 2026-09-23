package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.ui.catalog.ChaptersBySeason
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/**
 * RESULTS phase · chosen Magis season.
 *
 * Chapters do NOT come in the search result —the portal delivers them in a separate call—, so
 * they're requested on opening. While they load, the count the result DOES bring is shown, to
 * give a sense of the season's size.
 *
 * No checkboxes, unlike the phone: on the remote, checking 16 boxes is torture. "Guardar toda la
 * temporada" downloads everything and picking a chapter plays it.
 *
 * Also opens Caracol series, through [TvCaracolChapters]: with no save button ([onSaveAll] null)
 * and with its [label].
 */
@Composable
internal fun TvMagisSeasonContent(
    season: com.arkiv.player.data.gateway.GatewayResult,
    client: com.arkiv.player.data.gateway.ContentSource,
    posterUrl: String,
    preparing: Boolean,
    onPlayOne: (List<com.arkiv.player.data.gateway.GatewayEpisode>, com.arkiv.player.data.gateway.GatewayEpisode, com.arkiv.player.data.gateway.GatewaySeries?) -> Unit,
    // Null = no save button (Caracol: doesn't download to the device, see `DownloadSource`).
    onSaveAll: ((List<com.arkiv.player.data.gateway.GatewayEpisode>, com.arkiv.player.data.gateway.GatewaySeries?) -> Unit)?,
    // The source's name, in the data line above.
    label: String = "Xuper",
) {
    var chapters by remember(season.ref) { mutableStateOf<List<com.arkiv.player.data.gateway.GatewayEpisode>?>(null) }
    // The `series` block from the same response: that's where the `tmdbId` comes from that
    // `SearchPlayback.playMagisSeason` needs to save it on the item, without requesting it again
    // on tapping a chapter (see its KDoc).
    var series by remember(season.ref) { mutableStateOf<com.arkiv.player.data.gateway.GatewaySeries?>(null) }
    var error by remember(season.ref) { mutableStateOf<String?>(null) }
    val saveAllFocus = remember { FocusRequester() }

    LaunchedEffect(season.ref) {
        // Same diagnostic as the phone's dialog (see MagisSeasonDialog): without this the
        // real reason for the failure doesn't reach anywhere.
        android.util.Log.w(
            "ArkivGw",
            "season TV: requesting chapters title=${season.title} type=${season.extra["program_type"]} " +
                "expected=${season.extra["episode_count"]} kind=${season.kind} ref=${season.ref.take(24)}…",
        )
        runCatching { client.episodesWithSeries(season.ref) }
            .onSuccess { (caps, s) -> chapters = caps; series = s }
            .onFailure {
                android.util.Log.w("ArkivGw", "season TV: failed ${it.javaClass.simpleName}: ${it.message}", it)
                error = "No se pudieron cargar los capítulos."
            }
    }
    LaunchedEffect(chapters) {
        if (!chapters.isNullOrEmpty()) {
            delay(150)
            runCatching { saveAllFocus.requestFocus() }
        }
    }

    val expected = season.extra["episode_count"]?.toIntOrNull() ?: 0

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row {
                    Box(
                        modifier = Modifier.height(140.dp).width(140.dp * 2f / 3f)
                            .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
                    ) {
                        if (posterUrl.isNotBlank()) {
                            AsyncImage(
                                model = posterUrl,
                                contentDescription = season.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 20.dp).align(Alignment.CenterVertically)) {
                        Text(
                            season.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = ArkivTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                label,
                                season.year.ifBlank { null },
                                (chapters?.size ?: expected).takeIf { it > 0 }?.let { "$it capítulos" },
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelMedium,
                            color = ArkivTextSecondary,
                        )
                    }
                }
            }

            val loadedChapters = chapters
            when {
                error != null -> item {
                    Text(error!!, color = ArkivTextSecondary, modifier = Modifier.padding(top = 24.dp))
                }
                loadedChapters == null -> item {
                    Text("Cargando capítulos…", color = ArkivTextSecondary, modifier = Modifier.padding(top = 24.dp))
                }
                loadedChapters.isEmpty() -> item {
                    Text("Esta temporada no trae capítulos.", color = ArkivTextSecondary, modifier = Modifier.padding(top = 24.dp))
                }
                else -> {
                    onSaveAll?.let { save ->
                        item {
                            Button(
                                onClick = { save(loadedChapters, series) },
                                enabled = !preparing,
                                colors = arkivTvButtonColors(),
                                border = arkivTvButtonBorder(),
                                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).focusRequester(saveAllFocus),
                            ) { Text("Guardar toda la temporada") }
                        }
                    }
                    // With several seasons (a Caracol series), by season and number, and each row
                    // says its own. With no season —Magis— it stays as it arrived. See [ChaptersBySeason].
                    val ordered = ChaptersBySeason.sorted(loadedChapters)
                    val multipleSeasons = ChaptersBySeason.hasMultipleSeasons(loadedChapters)
                    items(ordered, key = { it.ref }) { chapter ->
                        // With no save button, initial focus goes to the first chapter: if nobody
                        // requested it, the remote would have nowhere to start.
                        val focus = if (onSaveAll == null && chapter === ordered.first()) {
                            Modifier.focusRequester(saveAllFocus)
                        } else {
                            Modifier
                        }
                        TvMagisEpisodeRow(
                            chapter = chapter,
                            label = ChaptersBySeason.label(chapter, multipleSeasons, noSeason = "E"),
                            enabled = !preparing,
                            modifier = focus,
                            onClick = { onPlayOne(loadedChapters, chapter, series) },
                        )
                    }
                }
            }
        }

        if (preparing) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Text("Preparando…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
