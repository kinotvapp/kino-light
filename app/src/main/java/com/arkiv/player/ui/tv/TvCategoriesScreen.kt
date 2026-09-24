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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import coil.compose.AsyncImage
import com.arkiv.player.ui.home.CategoriesViewModel
import com.arkiv.player.ui.home.CategorySpec
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivTextSecondary

private const val HERO_SCALE = 1.12f
private const val HERO_DRIFT_MS = 14_000

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvCategoriesScreen(
    onBrowseRow: (rowId: String, title: String) -> Unit,
    onOpenSearchRoute: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val graph = rememberGraph()
    val vm: CategoriesViewModel = viewModel(
        factory = viewModelFactory { initializer { CategoriesViewModel(graph.magisHomeCatalog, graph.homeReloads) } },
    )
    val rows by vm.rows.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    var featured by remember { mutableStateOf<Featured?>(null) }
    val navSound = rememberNavSound()

    // Restore the scroll saved in the VM (survives navigate → back).
    val rowsListState = rememberLazyListState(
        initialFirstVisibleItemIndex = vm.tvScrollIndex,
        initialFirstVisibleItemScrollOffset = vm.tvScrollOffset,
    )
    LaunchedEffect(rowsListState) {
        snapshotFlow { rowsListState.firstVisibleItemIndex to rowsListState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                vm.tvScrollIndex = index
                vm.tvScrollOffset = offset
            }
    }

    val cardHeight = 92.dp
    val labelHeight = 26.dp
    val rowGap = 14.dp
    val rowsTopPad = 6.dp
    // A group = label + row + spacer. Fixed height so exactly 2 groups fit visibly.
    val rowUnit = labelHeight + cardHeight + rowGap
    val rowsRegionHeight = rowUnit * 2 + rowsTopPad

    val heroDrift by rememberInfiniteTransition(label = "heroDrift").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = HERO_DRIFT_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heroDriftX",
    )

    if (loading && rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Group sections once to pass them as atomic items to the LazyColumn.
    data class Section(val key: String, val label: String, val suffix: String, val specs: List<CategorySpec>)
    val sections = buildList {
        val fixed = rows.filter { it.id.startsWith("magis_new_") || it.id.startsWith("magis_top_") }
        if (fixed.isNotEmpty()) add(Section("destacadas", "Destacadas", "", fixed))
        val movies = rows.filter { it.id.startsWith("magis_g_peliculas_") }
        if (movies.isNotEmpty()) add(Section("pelis", "Géneros · Películas", " · Películas", movies))
        val series = rows.filter { it.id.startsWith("magis_g_series_") }
        if (series.isNotEmpty()) add(Section("series", "Géneros · Series", " · Series", series))
        val anime = rows.filter { it.id.startsWith("magis_g_anime_") }
        if (anime.isNotEmpty()) add(Section("anime", "Géneros · Anime", " · Anime", anime))
        val kids = rows.filter { it.id.startsWith("magis_g_infantil_") }
        if (kids.isNotEmpty()) add(Section("infantil", "Géneros · Infantil", " · Infantil", kids))
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {

        // Immersive background: the focused category's preview + gradients.
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
                            val margin = size.width * (HERO_SCALE - 1f) / 2f
                            scaleX = HERO_SCALE
                            scaleY = HERO_SCALE
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

            // ── Fixed hero ──────────────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 48.dp, vertical = 28.dp),
            ) {
                Text(
                    "Categorías",
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

            // ── Category rows (exactly 2 sections visible) ─────────────────────────
            // Each section is ONE LazyColumn item (label + horizontal row + spacer in a Column)
            // so the height is atomic and fits without clipping the second row.
            CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollBringIntoView) {
                LazyColumn(
                    state = rowsListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowsRegionHeight)
                        .padding(top = rowsTopPad),
                ) {
                    items(sections, key = { it.key }) { section ->
                        Column {
                            TvRowLabel(section.label, labelHeight)
                            CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(section.specs, key = { it.id }) { spec ->
                                        val imageUrl = spec.previewUrl
                                        val label = spec.title.removeSuffix(section.suffix)
                                        TvLandscapeCard(
                                            title = label,
                                            imageUrl = imageUrl,
                                            cardHeight = cardHeight,
                                            onFocus = {
                                                navSound()
                                                featured = Featured(title = label, subtitle = "", imageUrl = imageUrl)
                                            },
                                            onClick = { onBrowseRow(spec.id, spec.title) },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(rowGap))
                        }
                    }

                    item(key = "bottom_pad") { Spacer(Modifier.height(rowGap)) }
                }
            }
        }
    }
}
