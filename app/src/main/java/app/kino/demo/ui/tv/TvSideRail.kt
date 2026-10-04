package app.kino.demo.ui.tv

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
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kino.demo.ui.LocalReducedEffects
import app.kino.demo.ui.brand.KinoMark
import app.kino.demo.ui.brand.KinoWordmark
import app.kino.demo.ui.effectEnter
import app.kino.demo.ui.effectExit
import app.kino.demo.ui.effectSpec
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoRed

/** The rail's width while it only shows icons. */
internal val TV_RAIL_COLLAPSED_WIDTH = 80.dp

/**
 * Where the screen's content starts. Less than [TV_RAIL_COLLAPSED_WIDTH] on purpose: the content
 * carries its own 48 dp inset.
 */
internal val TV_RAIL_CONTENT_START = 28.dp

/** The rail's width while it shows icons and words: it opens over the content, which does not move. */
private val TV_RAIL_EXPANDED_WIDTH = 264.dp

private val RAIL_ICON = 24.dp
private val RAIL_CLOSED_SLOT = 40.dp
private val RAIL_OPEN_SLOT = 32.dp

/** The strip each item owns, so the buttons never move while the rail opens. */
private val RAIL_STRIP = 52.dp

private val dpSpring = spring(visibilityThreshold = Dp.VisibilityThreshold)

/** One destination of the rail. [modifier] is for an item that holds a `FocusRequester`. */
data class TvRailItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val modifier: Modifier = Modifier,
)

/**
 * The TV's navigation as a rail on the left edge: icons only while focus is elsewhere, icons and
 * words as soon as an item holds focus. It opens over the content, so nothing on the screen jumps.
 * Place it last in a `Box` so it draws on top.
 */
@Composable
fun TvSideRail(items: List<TvRailItem>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) TV_RAIL_EXPANDED_WIDTH else TV_RAIL_COLLAPSED_WIDTH, effectSpec(dpSpring), label = "rail width")
    // Legible over any backdrop when collapsed, a solid panel when open.
    val scrim by animateFloatAsState(if (expanded) 0.96f else 0.55f, effectSpec(spring()), label = "rail scrim")

    Box(
        modifier = modifier
            .fillMaxHeight()
            .requiredWidth(width)
            .background(Brush.horizontalGradient(listOf(KinoBlack.copy(alpha = scrim), KinoBlack.copy(alpha = scrim), Color.Transparent)))
            .onFocusChanged { expanded = it.hasFocus },
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(start = 8.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            // The brand on top: its mark while closed, the whole logo when open.
            Box(Modifier.height(36.dp)) {
                Crossfade(targetState = expanded, animationSpec = effectSpec(tween()), label = "rail brand") { open ->
                    if (open) {
                        KinoWordmark(height = 32.dp, modifier = Modifier.padding(start = 12.dp))
                    } else {
                        KinoMark(diameter = 36.dp, modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            items.forEach { item -> TvRailButton(item, expanded) }
        }
    }
}

@Composable
private fun TvRailButton(item: TvRailItem, expanded: Boolean) {
    val slot by animateDpAsState(if (expanded) RAIL_OPEN_SLOT else RAIL_CLOSED_SLOT, effectSpec(dpSpring), label = "rail icon slot")
    val reduced = LocalReducedEffects.current
    Box(Modifier.fillMaxWidth().height(RAIL_STRIP), contentAlignment = Alignment.CenterStart) {
        Surface(
            onClick = item.onClick,
            modifier = item.modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = KinoRed,
                focusedContentColor = Color.White,
                pressedContainerColor = KinoRed,
                pressedContentColor = Color.White,
            ),
        ) {
            // The icon's centre stays put in both states: the slot shrinks, the start padding grows by half of it.
            Row(
                modifier = Modifier.padding(start = 12.dp + (RAIL_CLOSED_SLOT - slot) / 2, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(slot), contentAlignment = Alignment.Center) {
                    Icon(item.icon, contentDescription = if (expanded) null else item.label, modifier = Modifier.size(RAIL_ICON))
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
