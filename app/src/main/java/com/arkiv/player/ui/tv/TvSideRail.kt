package com.arkiv.player.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.ui.KinoMark
import com.arkiv.player.ui.KinoWordmark
import com.arkiv.player.ui.LocalReducedEffects
import com.arkiv.player.ui.effectEnter
import com.arkiv.player.ui.effectExit
import com.arkiv.player.ui.effectSpec
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed

/** The rail's width while it only shows icons: a 64 dp pill around each 40 dp slot (a 32 dp icon centred in it) and 8 dp of air either side. */
internal val TV_RAIL_COLLAPSED_WIDTH = 80.dp

/**
 * Where the screen's content starts. Less than [TV_RAIL_COLLAPSED_WIDTH] on purpose: the content already carries its
 * own 48 dp inset (the old top-bar layout's margin), so starting the padding at the rail's full width left a 100 dp
 * gap. This leaves about 22 dp between the icon glyphs and the first card.
 */
internal val TV_RAIL_CONTENT_START = 28.dp

/** The rail's width while it shows icons and words: it opens OVER the content, which does not move. */
internal val TV_RAIL_EXPANDED_WIDTH = 264.dp

// Closed rail: a 32 dp icon in a 40 dp slot. Open rail: a 24 dp icon in a 32 dp slot, beside the words.
private val RAIL_CLOSED_ICON = 32.dp
private val RAIL_CLOSED_SLOT = 40.dp
private val RAIL_OPEN_ICON = 24.dp
private val RAIL_OPEN_SLOT = 32.dp

/** The strip each item owns: the closed pill's height (40 dp slot + 6 dp above and below). */
private val RAIL_STRIP = 52.dp

/**
 * `animateDpAsState`'s own default spring, named so the rail can pass it through [effectSpec]: with "Efectos
 * visuales" on the rail opens and closes exactly as before, with them off it snaps open/closed in one frame.
 */
private val dpSpring = spring(visibilityThreshold = Dp.VisibilityThreshold)

/** One destination of the rail. [modifier] is for the item that must hold a `FocusRequester` or watch its own focus. */
internal data class TvRailItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val modifier: Modifier = Modifier,
)

/**
 * The TV's navigation as a rail on the left edge: icons only while focus is elsewhere, icons and words as soon as any
 * item holds focus. Opening is an overlay (the rail draws over the content and widens, the content keeps its place), so
 * nothing on the screen jumps when the D-pad enters or leaves it.
 *
 * Place it LAST in a `Box`, so it draws on top, aligned to the start; the content beside it needs
 * `padding(start = TV_RAIL_COLLAPSED_WIDTH)`. It has no focus logic of its own: Compose's directional search finds it
 * on Left from the content (it spans the full height, so every row overlaps it) and leaves it on Right.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSideRail(items: List<TvRailItem>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) TV_RAIL_EXPANDED_WIDTH else TV_RAIL_COLLAPSED_WIDTH, effectSpec(dpSpring), label = "rail width")
    // Legible over any backdrop when collapsed, a solid panel when open.
    val scrim by animateFloatAsState(if (expanded) 0.96f else 0.55f, effectSpec(spring()), label = "rail scrim")

    Box(
        modifier = modifier
            .fillMaxHeight()
            // requiredWidth, not width: the rail must widen past the slot the content left for it.
            .requiredWidth(width)
            .background(Brush.horizontalGradient(listOf(ArkivBlack.copy(alpha = scrim), ArkivBlack.copy(alpha = scrim), Color.Transparent)))
            .onFocusChanged { expanded = it.hasFocus },
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(start = 8.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            // The brand on top: its mark while the rail is closed, the whole logo when it opens. One box of the
            // logo's height, so swapping them moves nothing.
            Box(Modifier.height(36.dp)) {
                Crossfade(targetState = expanded, animationSpec = effectSpec(tween()), label = "rail brand") { open ->
                    if (open) {
                        KinoWordmark(height = 32.dp, modifier = Modifier.padding(start = 12.dp))
                    } else {
                        // Centred over the icons below: their centre is 12 dp (button padding) + 20 dp (half an icon) in.
                        KinoMark(diameter = 36.dp, modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            items.forEach { item ->
                TvRailButton(item, expanded)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvRailButton(item: TvRailItem, expanded: Boolean) {
    // Big icons for the closed rail, where the icon IS the button; once words appear the icon comes down to sit beside
    // them, and the red pill around it gets smaller with it.
    val slot by animateDpAsState(if (expanded) RAIL_OPEN_SLOT else RAIL_CLOSED_SLOT, effectSpec(dpSpring), label = "rail icon slot")
    val iconSize by animateDpAsState(if (expanded) RAIL_OPEN_ICON else RAIL_CLOSED_ICON, effectSpec(dpSpring), label = "rail icon size")
    val reduced = LocalReducedEffects.current
    // Every item owns the same fixed strip, whatever its pill measures: the buttons never move while the rail opens.
    Box(Modifier.fillMaxWidth().height(RAIL_STRIP), contentAlignment = Alignment.CenterStart) {
        Surface(
            onClick = item.onClick,
            modifier = item.modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = ArkivRed,
                focusedContentColor = Color.White,
                pressedContainerColor = ArkivRed,
                pressedContentColor = Color.White,
            ),
        ) {
            // The icon's centre stays put in both states: the slot shrinks, so the start padding grows by half of it.
            Row(
                modifier = Modifier.padding(start = 12.dp + (RAIL_CLOSED_SLOT - slot) / 2, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(slot), contentAlignment = Alignment.Center) {
                    Icon(item.icon, contentDescription = if (expanded) null else item.label, modifier = Modifier.size(iconSize))
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = effectEnter(reduced, expandHorizontally() + fadeIn()),
                    exit = effectExit(reduced, shrinkHorizontally() + fadeOut()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(16.dp))
                        Text(item.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    }
                }
            }
        }
    }
}
