package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.AppGraph
import com.arkiv.player.ui.home.RowBrowseViewModel
import com.arkiv.player.ui.home.searchShortcutRoute
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

private const val BROWSE_HERO_SCALE = 1.12f
private const val BROWSE_HERO_DRIFT_MS = 14_000

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TvRowBrowseScreen(
    rowId: String,
    title: String,
    onOpenSearchRoute: (String) -> Unit,
    onBack: () -> Unit,
    graph: AppGraph,
) {
    BackHandler { onBack() }

    val vm: RowBrowseViewModel = viewModel(
        key = rowId,
        factory = viewModelFactory { initializer { RowBrowseViewModel(rowId, graph.tmdbApi, graph.aniListApi) } },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val canLoadMore by vm.canLoadMore.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadMore() }

    val gridState = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= items.size - 10 && canLoadMore && !isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) vm.loadMore() }

    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(items) {
        if (items.isNotEmpty()) runCatching { firstItemFocus.requestFocus() }
    }

    val navSound = rememberNavSound()
    var featured by remember { mutableStateOf<Featured?>(null) }

    val heroDrift by rememberInfiniteTransition(label = "heroDrift").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BROWSE_HERO_DRIFT_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heroDriftX",
    )

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {

        // Immersive background: the focused item's backdrop.
        Crossfade(targetState = featured?.imageUrl, animationSpec = tween(450), label = "bg") { url ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .fillMaxHeight()
                        .align(Alignment.TopEnd)
                        .graphicsLayer {
                            val margin = size.width * (BROWSE_HERO_SCALE - 1f) / 2f
                            scaleX = BROWSE_HERO_SCALE
                            scaleY = BROWSE_HERO_SCALE
                            translationX = (heroDrift * 2f - 1f) * margin
                        },
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(ArkivBlack, ArkivBlack, ArkivBlack.copy(alpha = 0.15f), Color.Transparent)),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.4f), ArkivBlack)),
                    ),
                )
            }
        }

        Column(Modifier.fillMaxSize()) {

            // ── Fixed hero (1/3 of the screen) ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 48.dp, vertical = 28.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = ArkivTextSecondary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                featured?.let { f ->
                    Text(
                        f.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.55f),
                    )
                }
            }

            // ── Content grid (2/3 of the screen) ────────────────────────────────────────
            CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollBringIntoView) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(2f),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val cardHeight = 110.dp
                        itemsIndexed(
                            items,
                            key = { _, card -> "${card.kind}-${card.tmdbId}-${card.anilistId}" },
                        ) { index, card ->
                            val art = card.backdropUrl.ifBlank { card.posterUrl }
                            TvLandscapeCard(
                                title = card.title,
                                imageUrl = art,
                                cardHeight = cardHeight,
                                modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
                                onFocus = {
                                    navSound()
                                    featured = Featured(title = card.title, subtitle = "", imageUrl = art)
                                },
                                onClick = { onOpenSearchRoute(searchShortcutRoute(card)) },
                            )
                        }

                        if (isLoading) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = ArkivRed,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Empty state
                    if (items.isEmpty() && !isLoading && !canLoadMore) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "No se pudo cargar el contenido",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Reintentar",
                                    color = ArkivRed,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier
                                        .clickable { vm.resetAndLoad() }
                                        .padding(horizontal = 24.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
