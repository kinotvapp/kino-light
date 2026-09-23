package com.arkiv.player.ui.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Download
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Window for a Magis season.
 *
 * Also opens Caracol series, with its [sourceLabel] and its [accent]. The window saves nothing:
 * what happens on tapping a chapter is decided by whoever opens it, in [onPlay] and [onSave].
 *
 * Exists because a series result from the portal **is a whole season**, not a chapter: "Breaking
 * Bad T5" is 16 chapters under a single item. Without this screen, tapping that result played
 * chapter 1 silently, with no way to choose.
 *
 * Chapters are requested on opening (`MagisCatalog.detail`): they don't come in the search
 * result because the portal delivers them in a separate call, and requesting them for the 20
 * series of a search would spend the portal's rate limit on lists nobody's going to look at.
 */
@Composable
fun MagisSeasonDialog(
    season: GatewayResult,
    client: ContentSource,
    onDismiss: () -> Unit,
    onPlay: (List<GatewayEpisode>, GatewayEpisode, GatewaySeries?) -> Unit,
    // The [GatewaySeries] also travels in the save, not just in play: saving writes the episode's
    // entire row (REPLACE), so without it the marked chapters would lose the season play had
    // already saved correctly. See `SearchPlayback.magisEpisodeIdFor`.
    // Null = download disabled.
    //
    // BOTH lists go: the chosen chapters and the whole season the window already loaded. Magis
    // only needs the chosen ones, but Caracol saves the complete series to be able to save one
    // (`ArkivRepository.addDituSeason`), and with no row in `episodes` the download afterward
    // can't find the `ref`.
    onSave: ((all: List<GatewayEpisode>, chosen: List<GatewayEpisode>, GatewaySeries?) -> Unit)? = null,
    // The source's name and color. The window also opens Caracol series.
    sourceLabel: String = "Xuper",
    accent: Color = ArkivMagisBlue,
) {
    var chapters by remember(season.ref) { mutableStateOf<List<GatewayEpisode>?>(null) }
    // The `series` block from the same response: that's where the `tmdbId`
    // `SearchPlayback.playMagisSeason` needs to save it on the item comes from, without asking
    // for it again on tapping a chapter (see its KDoc).
    var series by remember(season.ref) { mutableStateOf<GatewaySeries?>(null) }
    var error by remember(season.ref) { mutableStateOf<String?>(null) }
    // Selection to save. Starts empty: this window's main gesture is playing, and checking all 16
    // chapters by default would invite downloading a whole season by accident.
    val checked = remember(season.ref) { mutableStateListOf<Int>() }
    val canSave = onSave != null
    // The chapter that was tapped and hasn't decided yet whether it's watched or downloaded. See
    // the dialog at the end.
    var pendingChoice by remember(season.ref) { mutableStateOf<GatewayEpisode?>(null) }

    LaunchedEffect(season.ref) {
        // What the dialog was opened with. `program_type` is what decides this is a series (see
        // MAGIS_SERIES): if the portal tagged something as a series that has no season,
        // MagisCatalog.detail responds 422 and from the UI it looks just like a network drop.
        android.util.Log.w(
            "ArkivGw",
            "season: requesting chapters title=${season.title} type=${season.extra["program_type"]} " +
                "expected=${season.extra["episode_count"]} kind=${season.kind} ref=${season.ref.take(24)}…",
        )
        try {
            val (caps, s) = client.episodesWithSeries(season.ref)
            android.util.Log.w("ArkivGw", "season: ok caps=${caps.size}")
            chapters = caps
            series = s
        } catch (e: kotlinx.coroutines.CancellationException) {
            android.util.Log.w("ArkivGw", "season: cancelled (CancellationException) ${e.message}")
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("ArkivGw", "season: failed ${e.javaClass.simpleName}: ${e.message}", e)
            error = "No se pudieron cargar los capítulos."
        }
    }

    val expected = season.extra["episode_count"]?.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canSave && checked.isNotEmpty()) {
                    val caps = chapters.orEmpty()
                    val chosen = caps.filter { it.number in checked }
                    TextButton(onClick = { onSave!!(caps, chosen, series); onDismiss() }) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = accent)
                        Spacer(Modifier.size(6.dp))
                        Text("Guardar ${chosen.size}", color = accent)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
        dismissButton = {
            val caps = chapters.orEmpty()
            if (canSave && caps.isNotEmpty()) {
                TextButton(onClick = {
                    // Toggles between "the whole season" and "none": the frequent case is wanting
                    // the complete season, and checking 16 boxes by hand would be absurd.
                    if (checked.size == caps.size) checked.clear()
                    else { checked.clear(); checked.addAll(caps.map { it.number }) }
                }) {
                    Text(
                        if (checked.size == caps.size) "Ninguno" else "Toda la temporada",
                        color = ArkivTextSecondary,
                    )
                }
            }
        },
        title = {
            Column {
                Text(season.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                    MetaChip(sourceLabel, accent)
                    if (season.year.isNotBlank()) MetaChip(season.year)
                    // The portal's count shows even before the list arrives: gives a sense of the
                    // season's size while it loads.
                    if (expected > 0) MetaChip("$expected capítulos")
                }
            }
        },
        text = {
            when {
                error != null -> Text(error!!, color = ArkivTextSecondary)

                chapters == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = accent)
                    Spacer(Modifier.size(12.dp))
                    Text("Cargando capítulos…", color = ArkivTextSecondary)
                }

                chapters!!.isEmpty() -> Text(
                    "Esta temporada no trae capítulos.",
                    color = ArkivTextSecondary,
                )

                else -> {
                    // With several seasons (a Caracol series), by season and number, and each row
                    // states its own. With no season --Magis-- it stays as it arrived. See
                    // [ChaptersBySeason].
                    val ordered = ChaptersBySeason.sorted(chapters!!)
                    val multipleSeasons = ChaptersBySeason.hasMultipleSeasons(chapters!!)
                    LazyColumn(
                        Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(ordered, key = { it.ref }) { cap ->
                            EpisodeRow(
                                cap = cap,
                                label = ChaptersBySeason.label(cap, multipleSeasons),
                                checked = cap.number in checked,
                                showCheckbox = canSave,
                                accent = accent,
                                onCheck = {
                                    if (cap.number in checked) checked.remove(cap.number)
                                    else checked.add(cap.number)
                                },
                                // Tapping a chapter plays it directly, same as a movie's card
                                // (see SearchScreen's `longPressResult`): asking on every tap got
                                // in the way when someone's just browsing to watch. Where it can
                                // be downloaded, a long press offers that choice instead; where it
                                // can't (Caracol is Widevine, see `DownloadSource`), there's
                                // nothing else to offer.
                                onPlay = { onPlay(chapters!!, cap, series) },
                                onLongPress = if (canSave) { { pendingChoice = cap } } else null,
                            )
                        }
                    }
                }
            }
        },
    )

    // Watch or download THIS chapter. It's the twin of the dialog movies already had
    // (`MagisTapDecision.ShowMovieDialog`): until now downloading a chapter could only be done by
    // checking its box, which is a gesture for several chapters at once and that nobody finds when
    // they want just one. Goes as a SIBLING Dialog and not inside the one above's `text`: one
    // nested in the other's content would inherit its width and its scroll.
    pendingChoice?.let { cap ->
        val caps = chapters.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingChoice = null },
            title = { Text(chapterName(cap), color = Color.White, fontWeight = FontWeight.SemiBold) },
            confirmButton = {
                TextButton(onClick = { pendingChoice = null; onPlay(caps, cap, series) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = accent)
                    Spacer(Modifier.size(6.dp))
                    Text("Ver", color = accent)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingChoice = null
                    onSave!!(caps, listOf(cap), series)
                    // The season closes, same as when saving several: the screen behind is what
                    // queues it, and that's where it shows whether it entered the queue or was
                    // already there.
                    onDismiss()
                }) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = ArkivTextSecondary)
                    Spacer(Modifier.size(6.dp))
                    Text("Descargar", color = ArkivTextSecondary)
                }
            },
        )
    }
}

/**
 * How a chapter is named on screen.
 *
 * The portal's number WINS: it identifies the chapter that's about to play, and if the TMDB match
 * were off for this season, it's still the reliable data. The name goes next to it, never in its
 * place. Priority: TMDB title (the real one) -> portal title (unless it only repeats the number)
 * -> "Capítulo N" as a last resort.
 */
private fun chapterName(cap: GatewayEpisode): String =
    cap.tmdbTitle?.takeIf { it.isNotBlank() }
        ?: cap.title.takeIf { it.isNotBlank() && it != cap.number.toString() }
        ?: "Capítulo ${cap.number}"

/** A chapter row: checkbox to save, number (or season and number, see
 *  [ChaptersBySeason]), name and play. [onLongPress] opens the watch-or-download choice; `null`
 *  where there's nothing to choose (Caracol has no download strategy, see the caller). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    cap: GatewayEpisode,
    label: String = cap.number.toString(),
    checked: Boolean,
    showCheckbox: Boolean = true,
    accent: Color = ArkivMagisBlue,
    onCheck: () -> Unit,
    onPlay: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ArkivSurfaceHigh)
            // Tapping the row PLAYS; the checkbox is a separate target. The other way around,
            // checking to save would run over the more common gesture.
            .combinedClickable(onLongClick = onLongPress, onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showCheckbox) {
            Box(
                Modifier.size(28.dp).clickable(onClick = onCheck),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = if (checked) "Quitar de la descarga" else "Guardar este capítulo",
                    tint = if (checked) accent else ArkivTextSecondary,
                )
            }
        }
        // "T2 · E1" doesn't fit in the 28dp box: with a season it widens. The bare number --Magis's,
        // always-- stays in the usual box.
        val withSeason = label != cap.number.toString()
        Box(
            if (withSeason) Modifier.height(28.dp).widthIn(min = 28.dp) else Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = accent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (!cap.still.isNullOrBlank()) {
            Box(
                Modifier.height(40.dp).width(40.dp * 16f / 9f)
                    .clip(RoundedCornerShape(4.dp)).background(Color.Black),
            ) {
                AsyncImage(
                    model = cap.still,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            chapterName(cap),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(20.dp).weight(1f).padding(start = 8.dp),
        )
        Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = accent)
    }
}
