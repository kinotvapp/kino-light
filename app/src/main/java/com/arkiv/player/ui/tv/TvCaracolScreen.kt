package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.ditu.DituChannel
import com.arkiv.player.data.ditu.DituSource
import com.arkiv.player.data.ditu.DituItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.playback.DituLive
import com.arkiv.player.ui.catalog.ArkivCaracolVerde
import com.arkiv.player.ui.catalog.CaracolCatalog
import com.arkiv.player.ui.catalog.ChannelsState
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.isSeries
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Caracol's section on the TV: its catalog and its live channels.
 *
 * The catalog is cached by [DituSource.fullCatalog] for 6h; "Recargar" requests it even if it
 * hasn't expired, for when Caracol adds something. Channels are requested every time it's
 * entered.
 *
 * Opening a title goes through the SAME path as search (`playResult` from [TvSearchScreen]), not
 * its own: [DituSource.resultFrom] turns it into the same result search gives, a movie gets saved
 * and opened with [SearchPlayback.playDitu], and a series opens [TvCaracolChapters], which on
 * tapping a chapter saves the whole series. Both save with a `ditu:` id: the movie via
 * `ArkivRepository.addDituSource`, the series via `ArkivRepository.addDituSeason`.
 *
 * A live channel doesn't go through the library: it travels via [DituLive].
 */
@Composable
internal fun TvCaracolScreen(onPlay: (episodeId: String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val playback = remember { SearchPlayback(graph) }
    var titles by remember { mutableStateOf<List<DituItem>>(emptyList()) }
    var channels by remember { mutableStateOf<ChannelsState>(ChannelsState.Loading) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableStateOf(0) }
    // The opened series: its chapters cover the section until one is picked or Back is pressed.
    var openSeries by remember { mutableStateOf<GatewayResult?>(null) }
    var preparing by remember { mutableStateOf(false) }
    // What just happened (couldn't open, couldn't reload). Clears itself, like
    // [TvCatalogSections]'s notice.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(3500)
        notice = null
    }

    LaunchedEffect(reloads) {
        loading = true
        // Each thing fails on its own: no channels can't leave the screen without a catalog.
        runCatching { graph.dituSource.fullCatalog(force = reloads > 0) }
            .onSuccess { titles = it; error = null }
            .onFailure {
                // The detail goes to the log; on screen, in plain words.
                android.util.Log.w("TvCaracol", "catalog failed to load", it)
                error = com.arkiv.player.data.ditu.CaracolFailure.onLoadCatalog(it)
                notice = error
            }
        // If it fails, it's said in the tab: it can't look the same as "no channels".
        val channelsResult = runCatching { graph.dituSource.channels() }
        channelsResult.exceptionOrNull()?.let { android.util.Log.w("TvCaracol", "channels failed to load", it) }
        channels = ChannelsState.from(channelsResult)
        loading = false
    }

    fun onFinished(result: PlaybackResult) {
        preparing = false
        when (result) {
            is PlaybackResult.Ready -> onPlay(result.episodeId)
            is PlaybackResult.Failed -> notice = result.message
        }
    }

    // Same as what search does with a Caracol result.
    fun openTitle(item: DituItem) {
        if (preparing) return
        val source = PlaySource.Ditu(DituSource.resultFrom(item))
        if (source.isSeries()) {
            openSeries = source.result
            return
        }
        preparing = true
        notice = null
        scope.launch { onFinished(playback.playDitu(source.result)) }
    }

    BackHandler(enabled = openSeries != null) { openSeries = null }

    val series = openSeries
    if (series != null) {
        Box(Modifier.fillMaxSize().background(ArkivBlack)) {
            TvCaracolChapters(
                series = series,
                posterUrl = series.extra["poster"].orEmpty(),
                preparing = preparing,
                onChoose = { save ->
                    openSeries = null
                    preparing = true
                    notice = null
                    scope.launch { onFinished(save()) }
                },
            )
        }
        return
    }

    TvCaracolContent(
        titles = titles,
        channels = channels,
        loading = loading,
        error = error,
        notice = if (preparing) "Preparando…" else notice,
        onReload = { reloads++ },
        onOpenTitle = { openTitle(it) },
        onOpenChannel = { channel -> onPlay(DituLive.leave(channel)) },
    )
}

/**
 * The section with its data already loaded. Its template is [TvCatalogSections], and it uses
 * the same pieces: [TvTab] up top, horizontal rows with [TvPivot], the column with
 * [MinimalScrollBringIntoView], the focused item's name in a fixed-height block, and the rows zone
 * measured as exactly two full rows.
 *
 * Titles go in [TvPosterCard] and not the template's [TvLandscapeCard]: the art Caracol brings is
 * vertical (`DituCatalog.POSTER`). Channels go in [TvLandscapeCard], with their logo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvCaracolContent(
    titles: List<DituItem>,
    channels: ChannelsState,
    loading: Boolean,
    error: String?,
    notice: String?,
    onReload: () -> Unit,
    onOpenTitle: (DituItem) -> Unit,
    onOpenChannel: (DituChannel) -> Unit,
) {
    var live by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<Focused?>(null) }
    // On switching tabs, what was focused is no longer on screen.
    LaunchedEffect(live) { focused = null }

    val rows = remember(titles) { caracolRows(titles) }
    val channelList = (channels as? ChannelsState.Ready)?.channels.orEmpty()

    // Snap to the row edge, same as the template (see its comment): a list item is a focusable
    // row, so on stopping the scroll it rounds to the nearest boundary.
    val rowsState = rememberLazyListState()
    LaunchedEffect(rowsState) {
        snapshotFlow { rowsState.isScrollInProgress }.collect { inMotion ->
            if (inMotion) return@collect
            val offset = rowsState.firstVisibleItemScrollOffset
            if (offset == 0) return@collect
            val height = rowsState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: return@collect
            val target = rowsState.firstVisibleItemIndex + if (offset > height / 2) 1 else 0
            runCatching { rowsState.animateScrollToItem(target) }
        }
    }

    val firstTabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { firstTabFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    val posterHeight = 130.dp
    val channelHeight = 92.dp
    val rowGap = 8.dp
    val rowsTopPad = 6.dp
    // +20: the row carries 10 dp of clearance top and bottom so the focus zoom doesn't clip.
    val rowHeight = posterHeight + 20.dp
    // Same as the template: the zone measures exactly two rows and the header keeps the rest.
    val zoneHeight = (rowHeight + rowGap) * 2 + rowsTopPad

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
            Column(Modifier.fillMaxWidth().weight(1f)) {
                // Tabs are ALWAYS painted, even while the list is loading or failed: otherwise
                // there'd be nothing to switch tabs with or to reload with.
                Row(
                    Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Caracol", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            TvTab(
                                label = "Catálogo",
                                selected = !live,
                                onClick = { live = false },
                                modifier = Modifier.focusRequester(firstTabFocus),
                            )
                        }
                        item { TvTab(label = "En vivo", selected = live, onClick = { live = true }) }
                        item { TvTab(label = "Recargar", selected = false, onClick = onReload) }
                    }
                }

                val empty = if (live) channelList.isEmpty() else titles.isEmpty()
                if (empty) {
                    Message(
                        when {
                            live -> when (channels) {
                                ChannelsState.Loading, is ChannelsState.Ready -> "Cargando…"
                                ChannelsState.Empty -> "Caracol no tiene canales en vivo para mostrar."
                                // Failed: said in plain words (see ChannelsState), and "Recargar" retries it.
                                is ChannelsState.Failed -> "${channels.message}\nPrueba otra vez con «Recargar»."
                            }
                            loading -> "Cargando…"
                            else -> error ?: "Caracol no devolvió títulos."
                        },
                    )
                } else {
                    HeroText(focused, notice)
                }
            }

            if (live && channelList.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().height(zoneHeight).padding(top = rowsTopPad)) {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(channelList, key = { it.channelId }) { channel ->
                                TvLandscapeCard(
                                    title = channel.name,
                                    imageUrl = channel.logoUrl,
                                    cardHeight = channelHeight,
                                    onFocus = { focused = Focused("En vivo", channel.name, "") },
                                    onClick = { onOpenChannel(channel) },
                                )
                            }
                        }
                    }
                }
            } else if (!live && rows.isNotEmpty()) {
                // The VERTICAL pivot is the minimal-scroll one, like in the template: with the
                // 30% one, a row of this height would get clipped at the top.
                CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollBringIntoView) {
                    LazyColumn(
                        state = rowsState,
                        modifier = Modifier.fillMaxWidth().height(zoneHeight).padding(top = rowsTopPad),
                    ) {
                        items(rows, key = { it.key }) { row ->
                            Column {
                                TitleRow(
                                    titles = row.titles,
                                    posterHeight = posterHeight,
                                    onOpen = onOpenTitle,
                                    onFocus = { focused = Focused(row.section, it.title, it.year) },
                                )
                                Spacer(Modifier.height(rowGap))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A row of posters, with the same pivot as the template's. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleRow(
    titles: List<DituItem>,
    posterHeight: Dp,
    onOpen: (DituItem) -> Unit,
    onFocus: (DituItem) -> Unit,
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
        LazyRow(
            // The `vertical` is the template's: the focused card scales to 1.08 ([TvPosterCard])
            // and in a row of the exact height the focus border would get clipped.
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(titles, key = { it.ref() }) { item ->
                TvPosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    cardHeight = posterHeight,
                    // The name goes up top, in the focused block: it doesn't fit under the card.
                    showTitle = false,
                    onFocus = { onFocus(item) },
                    onClick = { onOpen(item) },
                )
            }
        }
    }
}

/**
 * The focused item's name, or the notice of what just happened, which covers it for as long as
 * it lasts. Fixed height like in the template: if it appeared and disappeared, the rows below
 * would jump.
 */
@Composable
private fun HeroText(focused: Focused?, notice: String?) {
    Column(Modifier.fillMaxWidth(0.55f).height(96.dp).padding(start = 48.dp, bottom = 12.dp)) {
        if (notice != null) {
            Text(
                notice,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            return@Column
        }
        val f = focused ?: return@Column
        Text(
            f.section,
            style = MaterialTheme.typography.labelLarge,
            color = ArkivCaracolVerde,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Text(
            f.title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (f.detail.isNotBlank()) {
            Text(
                f.detail,
                style = MaterialTheme.typography.titleSmall,
                color = ArkivTextSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = ArkivTextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** What's focused, for the block up top: which row it's from, its name, and its year (or nothing). */
private data class Focused(val section: String, val title: String, val detail: String)

/** A catalog row: the `LazyColumn`'s item. */
private data class CaracolRow(val key: String, val section: String, val titles: List<DituItem>)

/**
 * Series first and then movies, [TITLES_PER_ROW] at a time. It's about 330 titles (see
 * [com.arkiv.player.data.ditu.DituCatalog]), and a row with all of one type is gone through card
 * by card with the D-pad; split up, you go down between rows, which is the template's gesture.
 *
 * The series/movies split (deduplicated, by [CaracolCatalog]) is shared with the phone's section;
 * chunking into rows of [TITLES_PER_ROW] is only the TV's horizontal row, so it stays here.
 */
private fun caracolRows(titles: List<DituItem>): List<CaracolRow> {
    val catalog = CaracolCatalog.split(titles)
    return listOf("Series" to catalog.series, "Películas" to catalog.movies).flatMap { (name, list) ->
        list.chunked(TITLES_PER_ROW).mapIndexed { i, row -> CaracolRow("$name:$i", name, row) }
    }
}

private const val TITLES_PER_ROW = 20
