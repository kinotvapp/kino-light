package app.kino.tv.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kino.tv.data.CatalogRow
import app.kino.tv.data.KinoPluginUpdates
import app.kino.tv.data.Film
import app.kino.tv.data.metaLine
import app.kino.tv.ui.LocalReducedEffects
import app.kino.tv.ui.backdropFadeSpec
import app.kino.tv.ui.fullAppOnly
import app.kino.tv.ui.plugins.PluginUpdatesBellIcon
import app.kino.tv.ui.rememberHeroDrift
import app.kino.tv.ui.theme.KinoBlack
import app.kino.tv.ui.theme.KinoRed
import app.kino.tv.ui.theme.KinoTextSecondary
import coil.compose.AsyncImage
import kotlin.math.abs

/** What the hero shows: the focused card's film. */
private data class Featured(val title: String, val subtitle: String, val imageUrl: String?, val meta: String)

private fun Film.featured() = Featured(title, synopsis, posterUrl, metaLine())

/** How much the hero's background is enlarged so it can drift without a border showing. */
private const val HERO_SCALE = 1.12f

/** How long the drift takes to cross from one end to the other: slow, it should feel like breathing. */
private const val HERO_DRIFT_MS = 14_000

/**
 * Horizontal scroll that keeps the focused card at 30% of the row, so the row runs under a card that
 * stays still instead of dragging it against the edge.
 */
@OptIn(ExperimentalFoundationApi::class)
private val TvPivot = object : BringIntoViewSpec {
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec = tween<Float>(durationMillis = 125, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val initialTarget = 0.3f * containerSize
        val leftover = containerSize - initialTarget
        val target = if (size <= containerSize && leftover < size) containerSize - size else initialTarget
        return offset - target
    }
}

/** Vertical scroll that only moves when the focused row is not fully visible already. */
@OptIn(ExperimentalFoundationApi::class)
private val MinimalScrollBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val bottomEdge = offset + size
        return when {
            offset >= 0f && bottomEdge <= containerSize -> 0f
            offset < 0f && bottomEdge > containerSize -> 0f
            abs(offset) < abs(bottomEdge - containerSize) -> offset
            else -> bottomEdge - containerSize
        }
    }
}

/**
 * The TV Home: a fixed immersive hero (the focused film's backdrop, title and synopsis) over a zone
 * that scrolls exactly two rows of landscape cards, with the navigation rail on the left and, top
 * right, the plugin-updates bell and reload.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvHomeScreen(
    rows: List<CatalogRow>,
    railItems: List<TvRailItem>,
    onOpenFilm: (Film) -> Unit,
) {
    val context = LocalContext.current
    val reduced = LocalReducedEffects.current
    var featured by remember { mutableStateOf(rows.firstOrNull()?.films?.firstOrNull()?.featured()) }
    // The card a film page was opened from, so Back lands on it again.
    var returnKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Lands on the card a film page was opened from, else on the first card.
    val landing = rememberLandingFocus()
    val rowsListState = rememberLazyListState()

    // Snap the rows zone to a row boundary when it stops, so two whole rows show, never three halves.
    LaunchedEffect(rowsListState) {
        snapshotFlow { rowsListState.isScrollInProgress }.collect { inMotion ->
            if (inMotion) return@collect
            val offset = rowsListState.firstVisibleItemScrollOffset
            if (offset == 0) return@collect
            val height = rowsListState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: return@collect
            val target = rowsListState.firstVisibleItemIndex + if (offset > height / 2) 1 else 0
            runCatching { rowsListState.animateScrollToItem(target) }
        }
    }

    // Fixed-size cards and rows: the rows zone measures exactly two rows (label + card).
    val cardHeight = 92.dp
    val labelHeight = 26.dp
    val rowGap = 14.dp
    val rowsTopPad = 6.dp
    val rowUnit = labelHeight + cardHeight + rowGap
    val rowsRegionHeight = rowUnit * 2 + rowsTopPad
    val heroDrift by rememberHeroDrift(reduced, HERO_DRIFT_MS)

    Box(Modifier.fillMaxSize().background(KinoBlack)) {
        Crossfade(targetState = featured?.imageUrl, animationSpec = backdropFadeSpec(reduced), label = "bg") { url ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .fillMaxHeight()
                        .align(Alignment.TopEnd)
                        // Enlarged a bit and wandering within that slack, so a border never shows.
                        .graphicsLayer {
                            val margin = size.width * (HERO_SCALE - 1f) / 2f
                            scaleX = HERO_SCALE
                            scaleY = HERO_SCALE
                            translationX = (heroDrift * 2f - 1f) * margin
                        },
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(KinoBlack, KinoBlack, KinoBlack.copy(alpha = 0.15f), Color.Transparent)),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, KinoBlack.copy(alpha = 0.4f), KinoBlack)),
                    ),
                )
            }
        }

        Column(Modifier.fillMaxSize().padding(start = TV_RAIL_CONTENT_START)) {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.Center,
            ) {
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
                    if (f.meta.isNotBlank()) {
                        Text(
                            f.meta,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.55f),
                        )
                    }
                    if (f.subtitle.isNotBlank()) {
                        Text(
                            f.subtitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = KinoTextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.55f),
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollBringIntoView) {
                LazyColumn(
                    state = rowsListState,
                    modifier = Modifier.fillMaxWidth().height(rowsRegionHeight).padding(top = rowsTopPad),
                ) {
                    items(rows, key = { it.id }) { row ->
                        Column {
                            TvRowLabel(row.title, labelHeight)
                            CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(row.films, key = { "${row.id}-${it.id}" }) { film ->
                                        val cardKey = "${row.id}-${film.id}"
                                        val isLanding = if (returnKey != null) cardKey == returnKey else row == rows.first() && film == row.films.first()
                                        val modifier = if (isLanding) Modifier.landingFocus(landing) else Modifier
                                        TvLandscapeCard(
                                            title = film.title,
                                            imageUrl = film.posterUrl,
                                            cardHeight = cardHeight,
                                            modifier = modifier,
                                            onFocus = { featured = film.featured() },
                                            onClick = {
                                                returnKey = cardKey
                                                onOpenFilm(film)
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(rowGap))
                        }
                    }
                    item(key = "rows_bottom_pad") { Spacer(Modifier.height(rowGap)) }
                }
            }
        }

        TvSideRail(railItems, Modifier.align(Alignment.CenterStart))
        // The plugin-updates bell sits beside reload, as on the phone's Home.
        var showPluginUpdates by rememberSaveable { mutableStateOf(false) }
        if (showPluginUpdates) TvPluginUpdatesDialog(onDismiss = { showPluginUpdates = false })
        Surface(
            onClick = { showPluginUpdates = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp + 48.dp + 12.dp).size(48.dp),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = KinoBlack.copy(alpha = 0.55f),
                contentColor = Color.White,
                focusedContainerColor = KinoRed,
                focusedContentColor = Color.White,
                pressedContainerColor = KinoRed,
                pressedContentColor = Color.White,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PluginUpdatesBellIcon(KinoPluginUpdates.count) }
        }
        // Reload sits in the top right corner: an action on the screen, not a place to go.
        Surface(
            onClick = { fullAppOnly(context) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp).size(48.dp),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = KinoBlack.copy(alpha = 0.55f),
                contentColor = Color.White,
                focusedContainerColor = KinoRed,
                focusedContentColor = Color.White,
                pressedContainerColor = KinoRed,
                pressedContentColor = Color.White,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Refresh, contentDescription = "Recargar", modifier = Modifier.size(26.dp))
            }
        }
    }
}
