package com.arkiv.player.ui.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The program containing [now] (epoch seconds), or null if there isn't one. Also used by Task 13
 * (TV timeline) to highlight the current cell.
 */
fun currentProgram(progs: List<LiveProgram>, now: Long): LiveProgram? =
    progs.firstOrNull { now >= it.start && now < it.end }

/** How far along a program is, from 0 to 1. Also used by Task 13. */
fun progressOf(p: LiveProgram, now: Long): Float {
    val total = (p.end - p.start).toFloat()
    if (total <= 0f) return 0f
    return ((now - p.start).toFloat() / total).coerceIn(0f, 1f)
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun timeOf(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(timeFormatter)

/**
 * Vertical programming guide for the phone: one channel per row, collapsed. Tapping it expands
 * to show the whole day -- an alternative to Task 13 (channel x hour timeline), which makes
 * sense there because the TV doesn't fight the phone's natural vertical scroll gesture the way a
 * 2D scroll would here.
 *
 * [programming] is the same map from [LiveUiState] (Task 11): the whole day per channel, already
 * cached there so it isn't lost on collapsing/expanding rows. Each row's current program is
 * DERIVED with [currentProgram] instead of receiving a separate `now` map -- they're the same
 * source of truth (the day's list's first program containing the current instant), and passing
 * both maps would only open the door to them drifting out of sync.
 *
 * [onRequestEpg] fires with the codes that enter the `LazyColumn`'s visible window -detected by
 * [rememberLazyListState]'s index, not by recomposition- and filtered against [programming]:
 * the ones that already have their day loaded aren't requested again. [LiveViewModel.requestEpg]
 * already guards itself against duplicates (see its KDoc), but filtering here also avoids
 * sending the full list of visible channels on every scroll -- only the difference.
 */
@Composable
fun LiveGuideList(
    channels: List<LiveChannel>,
    programming: Map<String, List<LiveProgram>>,
    onWatch: (LiveChannel) -> Unit,
    onRequestEpg: (List<String>) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val listState = rememberLazyListState()

    // rememberUpdatedState for programming/onRequestEpg (not as the LaunchedEffect's key): the
    // effect launches ONCE per `channels` identity and lives listening to scroll that whole
    // time. If `programming` were the key, every EPG batch that arrives (see requestEpg) would
    // restart the collection -- here it only needs to see the freshest map at the moment the
    // visible index changes, not relaunch every time that map grows.
    val latestProgramming by rememberUpdatedState(programming)
    val latestOnRequestEpg by rememberUpdatedState(onRequestEpg)

    LaunchedEffect(listState, channels) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                val missing = indices.mapNotNull { channels.getOrNull(it)?.code }
                    .filter { it !in latestProgramming }
                if (missing.isNotEmpty()) latestOnRequestEpg(missing)
            }
    }

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        items(channels, key = { it.code }) { channel ->
            GuideChannelRow(channel = channel, programs = programming[channel.code], onWatch = onWatch)
        }
    }
}

@Composable
private fun GuideChannelRow(
    channel: LiveChannel,
    programs: List<LiveProgram>?,
    onWatch: (LiveChannel) -> Unit,
) {
    // rememberSaveable (not remember): the LazyColumn with key = channel.code tears down the
    // composition of rows that leave the visible window -- without this, an expanded row would
    // collapse on its own when scrolled far away and back (same reason as DownloadGroupHeader in
    // DownloadsScreen).
    var expanded by rememberSaveable { mutableStateOf(false) }
    val now = System.currentTimeMillis() / 1000
    val current = programs?.let { currentProgram(it, now) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ArkivSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                } else {
                    Text(
                        text = channel.number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Same as the grid (ChannelCard): with no EPG yet it still looks like "arriving",
                // never like an empty hole or an error -- see the brief.
                Text(
                    text = when {
                        current != null -> current.title
                        programs != null -> "Sin programación por ahora"
                        else -> "Cargando programación…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Contraer" else "Expandir",
                tint = ArkivTextSecondary,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                Button(
                    onClick = { onWatch(channel) },
                    colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Ver ahora", modifier = Modifier.padding(start = 6.dp))
                }

                when {
                    programs == null -> Text(
                        "Cargando programación…",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                    )
                    programs.isEmpty() -> Text(
                        "Sin programación disponible",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                    )
                    else -> programs.forEach { p -> GuideProgramRow(p, isCurrent = p == current) }
                }
            }
        }
    }
}

/** A `time — title` row of the expanded programming; the current program's, in [ArkivRed]. */
@Composable
private fun GuideProgramRow(p: LiveProgram, isCurrent: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = timeOf(p.start),
            style = MaterialTheme.typography.bodySmall,
            color = if (isCurrent) ArkivRed else ArkivTextSecondary,
            modifier = Modifier.width(44.dp),
        )
        Text(
            text = p.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) ArkivRed else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
