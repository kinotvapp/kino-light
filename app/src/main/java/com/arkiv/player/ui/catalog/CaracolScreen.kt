package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.ditu.DituChannel
import com.arkiv.player.data.ditu.DituSource
import com.arkiv.player.data.ditu.DituItem
import com.arkiv.player.data.ditu.CaracolFailure
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.playback.DituLive
import com.arkiv.player.ui.gridColumns
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.components.PosterCard
import com.arkiv.player.ui.isLandscapeTablet
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/** Which Caracol catalog section is shown. Starts at "Series" -- see the brief. */
private enum class CaracolSection(val label: String) {
    SERIES("Series"),
    MOVIES("Películas"),
    LIVE("En vivo"),
}

/**
 * The Caracol section on the phone: its catalog and its live channels.
 *
 * Same data as the TV screen ([com.arkiv.player.ui.tv.TvCaracolScreen]): the catalog is cached 6 h
 * by [DituSource.fullCatalog] ("Recargar" forces it anyway), channels are fetched every time
 * the screen opens, and the series/movies split is the same [CaracolCatalog] the TV screen uses.
 *
 * Opening a title goes through the SAME path as search ([SearchPlayback], see
 * `SearchScreen.playDituResult`): a movie is saved and played with [SearchPlayback.playDitu], and
 * a series opens [MagisSeasonDialog] -- which saves the whole season with
 * [SearchPlayback.playDituSeason] once a chapter is picked. Caracol is Widevine (can't be
 * downloaded), so the dialog opens without save checkboxes (`onSave = null`), same as search.
 *
 * A live channel never touches the library: it travels through [DituLive.leave], same as the TV
 * screen.
 */
@Composable
fun CaracolScreen(onPlay: (episodeId: String) -> Unit, contentPadding: PaddingValues) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val playback = remember { SearchPlayback(graph) }

    var titles by remember { mutableStateOf<List<DituItem>>(emptyList()) }
    var channels by remember { mutableStateOf<ChannelsState>(ChannelsState.Loading) }
    var loading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableStateOf(0) }
    var section by remember { mutableStateOf(CaracolSection.SERIES) }

    // The Caracol series that's open: chapters are picked before playing, same as search -- see
    // the KDoc above.
    var dituSeason by remember { mutableStateOf<GatewayResult?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    // Notification permission (API 33+) is only asked when triggering a download, which is the
    // only thing on this screen that notifies. Same criterion as in search.
    val askNotifications = com.arkiv.player.ui.offline.rememberPostNotificationsRequest()
    val caracolDownloadable = remember {
        com.arkiv.player.data.local.DownloadSource.hasStrategy("ditu", graph.downloadStrategies.keys)
    }

    LaunchedEffect(reloads) {
        loading = true
        // Each one fails on its own: missing channels can't leave the screen without a catalog.
        runCatching { graph.dituSource.fullCatalog(force = reloads > 0) }
            .onSuccess { titles = it; catalogError = null }
            .onFailure {
                // The detail goes to the log; on screen, in plain words.
                android.util.Log.w("CaracolScreen", "catalog failed to load", it)
                catalogError = CaracolFailure.onLoadCatalog(it)
            }
        val channelsResult = runCatching { graph.dituSource.channels() }
        channelsResult.exceptionOrNull()
            ?.let { android.util.Log.w("CaracolScreen", "channels failed to load", it) }
        channels = ChannelsState.from(channelsResult)
        loading = false
    }

    fun applyResult(result: PlaybackResult) {
        preparing = false
        when (result) {
            is PlaybackResult.Ready -> onPlay(result.episodeId)
            is PlaybackResult.Failed -> playError = result.message
        }
    }

    // Same as what search does with a Caracol result (SearchScreen.playDituResult).
    fun openTitle(item: DituItem) {
        if (preparing) return
        val source = PlaySource.Ditu(DituSource.resultFrom(item))
        if (source.isSeries()) {
            dituSeason = source.result
            return
        }
        preparing = true
        playError = null
        scope.launch { applyResult(playback.playDitu(source.result)) }
    }

    val catalog = remember(titles) { CaracolCatalog.split(titles) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CaracolSection.entries.forEach { entry ->
                        FilterChip(
                            selected = section == entry,
                            onClick = { section = entry },
                            label = { Text(entry.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArkivCaracolVerde,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
                IconButton(onClick = { reloads++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Recargar", tint = ArkivTextSecondary)
                }
            }

            if (playError != null) {
                Text(
                    playError!!,
                    color = ArkivRed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            val gridPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ArkivRed)
                }
                section == CaracolSection.LIVE -> CaracolChannels(
                    channels = channels,
                    contentPadding = gridPadding,
                    onOpen = { channel -> onPlay(DituLive.leave(channel)) },
                )
                else -> CaracolGrid(
                    titles = if (section == CaracolSection.SERIES) catalog.series else catalog.movies,
                    emptyLabel = if (section == CaracolSection.SERIES) "series" else "películas",
                    error = catalogError,
                    contentPadding = gridPadding,
                    onOpen = ::openTitle,
                )
            }
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

    dituSeason?.let { season ->
        MagisSeasonDialog(
            season = season,
            client = graph.contentSource,
            onDismiss = { dituSeason = null },
            onPlay = { chapters, chapter, series ->
                dituSeason = null
                preparing = true
                playError = null
                scope.launch { applyResult(playback.playDituSeason(season, chapters, chapter, series)) }
            },
            // Caracol can be downloaded since 2026-09-13 -- not the way Magis is. What lands on
            // the device are its ENCRYPTED segments, and opening them still asks the licence server
            // for a few KB over the network, because Caracol grants no persistent licences. See
            // `CaracolStore`. Offered only when a strategy is registered, the same gate the
            // rest of the app uses.
            onSave = if (!caracolDownloadable) null else { all, chosen, series ->
                askNotifications()
                scope.launch {
                    val queued = playback.enqueueCaracolDownload(season, all, chosen, series)
                    playError = when {
                        queued == 0 -> "Esos capítulos ya estaban guardados."
                        queued == chosen.size -> null
                        else -> "Se encolaron $queued de ${chosen.size} (el resto ya estaba)."
                    }
                }
            },
            sourceLabel = "Caracol",
            accent = ArkivCaracolVerde,
        )
    }
}

/** The 3-column grid of series/movies, or that section's empty/error state. */
@Composable
private fun CaracolGrid(
    titles: List<DituItem>,
    emptyLabel: String,
    error: String?,
    contentPadding: PaddingValues,
    onOpen: (DituItem) -> Unit,
) {
    if (titles.isEmpty()) {
        EmptyState(
            title = error ?: "Caracol no tiene $emptyLabel para mostrar.",
            subtitle = if (error != null) "Prueba otra vez con «Recargar»." else null,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns(3, isLandscapeTablet())),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(titles, key = { it.ref() }) { item ->
            PosterCard(title = item.title, imageUrl = item.posterUrl, onClick = { onOpen(item) })
        }
    }
}

/** The "En vivo" tab: the channel list, or the empty/error state from [ChannelsState]. */
@Composable
private fun CaracolChannels(
    channels: ChannelsState,
    contentPadding: PaddingValues,
    onOpen: (DituChannel) -> Unit,
) {
    when (channels) {
        ChannelsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ArkivRed)
        }
        ChannelsState.Empty -> EmptyState(title = "Caracol no tiene canales en vivo para mostrar.")
        is ChannelsState.Failed -> EmptyState(
            title = channels.message,
            subtitle = "Prueba otra vez con «Recargar».",
        )
        is ChannelsState.Ready -> LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            items(channels.channels, key = { it.channelId }) { channel ->
                CaracolChannelRow(channel = channel, onClick = { onOpen(channel) })
            }
        }
    }
}

/** A channel row: logo + name, same visual treatment as `GuideChannelRow` (ui/live). */
@Composable
private fun CaracolChannelRow(channel: DituChannel, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                )
            } else {
                Icon(Icons.Default.LiveTv, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
            }
        }
        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = ArkivCaracolVerde)
    }
}
