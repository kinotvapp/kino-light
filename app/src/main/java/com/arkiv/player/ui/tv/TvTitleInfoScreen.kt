package com.arkiv.player.ui.tv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import androidx.compose.animation.core.tween
import com.arkiv.player.ui.effectSpec
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.titleinfo.EpisodesState
import com.arkiv.player.ui.titleinfo.TitleInfo
import com.arkiv.player.ui.titleinfo.TitleInfoEvent
import com.arkiv.player.ui.titleinfo.TitleInfoViewModel
import com.arkiv.player.ui.titleinfo.TitleKind
import com.arkiv.player.ui.titleinfo.TitleOrigin
import com.arkiv.player.ui.titleinfo.chapterLine
import com.arkiv.player.ui.titleinfo.chapterName
import com.arkiv.player.ui.titleinfo.chapterNumberLabel
import com.arkiv.player.ui.titleinfo.creditLines
import com.arkiv.player.ui.titleinfo.kindLine
import com.arkiv.player.ui.titleinfo.metaLine
import com.arkiv.player.ui.titleinfo.labelSeason
import com.arkiv.player.ui.titleinfo.listKey
import com.arkiv.player.ui.titleinfo.titleInfoViewModel
import com.arkiv.player.ui.titleinfo.titleSourceFor

/** "★ 7.9 · 2026 · 1 h 43 min  ·  Drama, Romance": the metadata line plus the genres. */
private fun tvMetaLine(info: TitleInfo): String =
    listOf(info.metaLine(), info.genres.joinToString(", ")).filter { it.isNotBlank() }.joinToString("  ·  ")

/**
 * Whether the item with [key] is one the lazy list currently lays out ([visibleKeys] are those items'
 * keys). A focus requester is attached only to a composed item, and pointing `focusProperties` at a
 * detached one drops the D-pad key: Compose logs "FocusRequester is not initialized" and the focus
 * does not move (older versions throw). Measured on the TV with the episode carousel scrolled to the
 * end: Down from the first season chip did nothing.
 */
internal fun isComposedItem(visibleKeys: List<Any>, key: Any?): Boolean = key != null && key in visibleKeys

/**
 * The information page a Magis or plugin title opens on the TV, in the same visual language as
 * [TvDetailScreen] (which is for library items). Everything comes from [TitleInfoViewModel].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvTitleInfoScreen(
    item: CatalogItem,
    origin: TitleOrigin,
    onPlay: (episodeId: String) -> Unit,
    onConfigurePlugin: (pluginId: String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val graph = rememberGraph()
    val context = LocalContext.current
    val vm: TitleInfoViewModel = viewModel(
        factory = viewModelFactory { initializer { titleInfoViewModel(graph, item, titleSourceFor(graph, origin, item)) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    // Back from Configurar: ask again, with the new settings (the plugin's own Ver más does the same).
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if ((vm.state.value.episodes as? EpisodesState.Failed)?.setupPluginId != null) vm.retry()
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is TitleInfoEvent.OpenPlayer -> onPlay(event.episodeId)
                is TitleInfoEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                // Offline is a phone-only feature: nothing on this screen ever requests a download.
                is TitleInfoEvent.Downloaded -> Unit
            }
        }
    }

    val info = state.info
    val chapters = (state.episodes as? EpisodesState.Loaded)?.chapters.orEmpty()
    val primary = state.primary

    val playFR = remember { FocusRequester() }
    val resumeChipFR = remember { FocusRequester() }
    val firstSeasonFR = remember { FocusRequester() }
    val carouselState = rememberLazyListState()
    val chipsState = rememberLazyListState()
    var focusedChapter by remember(state.item.id) { mutableStateOf<GatewayEpisode?>(null) }

    // Explicit focus destinations are only safe while their target is composed (see
    // [isComposedItem]); both lists scroll, so ask at the moment of the key press, not at composition.
    fun resumeCardComposed() =
        isComposedItem(carouselState.layoutInfo.visibleItemsInfo.map { it.key }, primary?.listKey)
    fun firstChipComposed() =
        isComposedItem(chipsState.layoutInfo.visibleItemsInfo.map { it.key }, state.seasons.firstOrNull()?.contentId)

    // The carousel opens on the chapter the main button plays, once per load. Keyed on the season
    // and on whether chapters exist, NOT on the button's target: that changes every time progress
    // is saved and would yank the carousel around when the person comes back from the player.
    LaunchedEffect(state.item.id, chapters.isNotEmpty(), state.currentSeason) {
        val index = state.visibleChapters.indexOfFirst { primary?.plays(it) == true }
        carouselState.scrollToItem(index.coerceAtLeast(0))
    }
    // Focus starts on the main button as soon as it can take it: at once for a movie, when the
    // chapters arrive for a series. `runCatching` because the requester may not be attached yet. Only
    // ONCE: picking another season makes `primary` go null and back, and that must not pull focus
    // from the season chip the person just pressed.
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(primary != null) {
        if (primary != null && !focusPlaced && runCatching { playFR.requestFocus() }.isSuccess) focusPlaced = true
    }

    val focused = focusedChapter
    // With a chapter focused the background and texts describe THAT chapter, like TvDetailScreen.
    val heroImage = focused?.still ?: info.backdrop ?: info.poster

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        // Crossfade: without it, scrolling the carousel makes the whole background flicker.
        Crossfade(targetState = heroImage, animationSpec = effectSpec(tween()), label = "title-hero") { image ->
            AsyncImage(
                model = image,
                contentDescription = info.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Horizontal gradient: black on the left to read the text.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(ArkivBlack, ArkivBlack, ArkivBlack.copy(alpha = 0.15f), Color.Transparent)),
            ),
        )
        // Vertical gradient: black at the bottom to blend into the carousel.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.4f), ArkivBlack)),
            ),
        )

        Column(Modifier.fillMaxSize()) {
            // --- Info block ---
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (focused != null) {
                    Text(
                        info.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ArkivTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        listOfNotNull(info.kindLine(), state.source.badge?.label).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = ArkivTextSecondary,
                    )
                }
                Text(
                    focused?.let { chapterName(it) ?: "Episodio ${it.number}" } ?: info.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = if (focused != null) chapterNumberLabel(focused.labelSeason(info.seasonNumber), focused.number) else tvMetaLine(info)
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArkivTextSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // Only a movie has room for a tagline: on a series page the carousel takes the space.
                if (focused == null && info.kind == TitleKind.MOVIE && info.tagline.isNotBlank()) {
                    Text(
                        info.tagline,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = ArkivTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = 640.dp),
                    )
                }
                val synopsis = if (focused != null) focused.overview.orEmpty() else info.synopsis
                if (synopsis.isNotBlank()) {
                    Text(
                        synopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp).widthIn(max = 640.dp),
                    )
                }
                // One line, so it costs a series page (whose carousel takes most of the height) as
                // little as it can; what does not fit is cut with an ellipsis.
                val credits = if (focused == null) info.creditLines(maxCast = 4).joinToString("  ·  ") else ""
                if (credits.isNotBlank()) {
                    Text(
                        credits,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = 640.dp),
                    )
                }
                Button(
                    onClick = { vm.play() },
                    // Stays enabled while a play resolves (the view model ignores taps then): a
                    // disabled button cannot hold focus, and a failed play left it nowhere.
                    enabled = primary != null,
                    colors = arkivTvButtonColors(),
                    border = arkivTvButtonBorder(),
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .focusRequester(playFR)
                        // Back on the button the texts describe the title again, not the last
                        // chapter scrolled past (which is not what this button plays).
                        .onFocusChanged { if (it.isFocused) focusedChapter = null }
                        .focusProperties {
                            // Only point at a requester whose target is attached: the carousel and
                            // the season chips exist only once the chapters have loaded.
                            down = when {
                                chapters.isEmpty() -> FocusRequester.Default
                                state.showSeasonSelector && firstChipComposed() -> firstSeasonFR
                                resumeCardComposed() -> resumeChipFR
                                else -> FocusRequester.Default
                            }
                        },
                ) {
                    val label = when {
                        state.resolving -> "Preparando…"
                        primary != null -> primary.label
                        state.episodes is EpisodesState.Failed -> "No disponible"
                        else -> "Cargando…"
                    }
                    Text("▶  $label")
                }
            }

            // --- Season chips: only when the portal lists more than this season ---
            if (state.showSeasonSelector) {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    Text(
                        "Temporada",
                        style = MaterialTheme.typography.titleSmall,
                        color = ArkivTextSecondary,
                        modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                    )
                    LazyRow(
                        state = chipsState,
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(state.seasons, key = { _, season -> season.contentId }) { index, season ->
                            TvSourceChip(
                                label = season.label,
                                selected = state.isCurrentSeason(season),
                                onClick = { vm.selectSeason(season) },
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstSeasonFR).focusProperties {
                                        up = playFR
                                        down = if (resumeCardComposed()) resumeChipFR else FocusRequester.Default
                                    }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
            }

            // --- Episode carousel: series only ---
            if (info.kind == TitleKind.SERIES) {
                Column(modifier = Modifier.padding(bottom = 32.dp)) {
                    Text(
                        "Episodios",
                        style = MaterialTheme.typography.titleSmall,
                        color = ArkivTextSecondary,
                        modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                    )
                    when (val episodes = state.episodes) {
                        is EpisodesState.Failed -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 48.dp),
                        ) {
                            // The fixed line for every source; a plugin's own message (worded for the
                            // person) goes under it. Bounded and weighted so a long message can never
                            // push the button off the screen.
                            Column(Modifier.weight(1f, fill = false)) {
                                Text("No se pudieron cargar los episodios", color = Color.White)
                                if (state.source.showsFailureDetail && episodes.message.isNotBlank()) {
                                    Text(
                                        episodes.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ArkivTextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    val setup = episodes.setupPluginId
                                    if (setup != null) onConfigurePlugin(setup) else vm.retry()
                                },
                                colors = arkivTvButtonColors(),
                                border = arkivTvButtonBorder(),
                                modifier = Modifier.padding(start = 16.dp),
                            ) { Text(if (episodes.setupPluginId != null) "Configurar" else "Reintentar") }
                        }
                        is EpisodesState.Loaded -> LazyRow(
                            state = carouselState,
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.visibleChapters, key = { it.listKey }) { chapter ->
                                val id = state.chapterEpisodeId(chapter)
                                val chapterProgress = state.progress[id]
                                TvEpisodeCard(
                                    numberLabel = chapterNumberLabel(chapter.labelSeason(info.seasonNumber), chapter.number),
                                    contentDescription = chapterLine(chapter),
                                    // Magis chapters carry no duration: the minutes stay hidden.
                                    durationMin = 0,
                                    isCurrent = chapterProgress != null && !chapterProgress.watched && chapterProgress.positionMs > 0,
                                    progress = chapterProgress,
                                    onClick = { vm.play(chapter) },
                                    stillUrl = chapter.still ?: info.backdrop ?: info.poster,
                                    episodeTitle = chapterName(chapter),
                                    onFocus = { focusedChapter = chapter },
                                    modifier = if (primary?.plays(chapter) == true) {
                                        Modifier.focusRequester(resumeChipFR).focusProperties {
                                            up = if (state.showSeasonSelector && firstChipComposed()) firstSeasonFR else playFR
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                        else -> Text(
                            "Cargando…",
                            color = ArkivTextSecondary,
                            modifier = Modifier.padding(start = 48.dp),
                        )
                    }
                }
            }
        }
    }
}
