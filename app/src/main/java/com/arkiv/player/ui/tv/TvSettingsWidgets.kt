package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh

// TV Settings' rows. Shared by all four tabs (TvSettings*.kt), so they live here and not inside
// any of them: if each tab had its own, focus would look different depending on where you are.

// Single style for Settings' buttons (TvActionOption): idle = black + 1dp white border;
// focused/pressed = Arkiv red background, no white border. Delegates to TV's shared
// action-button style (TvButtonStyle.kt) so Settings doesn't drift out of sync with the rest of
// the app.
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun tvButtonColors() = arkivTvSurfaceColors()

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun tvButtonBorder() = arkivTvSurfaceBorder()

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvActionOption(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        // The caller's modifier goes first so a `focusRequester` on it reaches this Surface's own focus target.
        modifier = modifier.fillMaxWidth(0.6f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        // Black surface + white idle border, Arkiv red on focus/press (shared standard in
        // TvButtonStyle.kt). Without explicit colors, tv.material3's Surface falls back to the
        // library's default light scheme and the button looked WHITE.
        colors = tvButtonColors(),
        border = tvButtonBorder(),
    ) {
        Text(label, color = Color.White, modifier = Modifier.padding(16.dp))
    }
}

/** A [TvCompactAction]'s background: the shared neutral surface, Arkiv red, or a dimmed grey. */
enum class TvCompactFill { NEUTRAL, RED, DIM }

data class TvCompactActionLook(val fill: TvCompactFill, val focusRing: Boolean, val contentAlpha: Float)

/**
 * How a [TvCompactAction] looks. Enabled: neutral idle, red when focused (pressed counts as focused).
 * Disabled: dim grey with dimmed text in every state, never the red "go" fill, since a focused
 * disabled "Listo" in red read as enabled; while focused it gets a light ring so the person still
 * sees where focus is.
 */
fun tvCompactActionLook(enabled: Boolean, focused: Boolean): TvCompactActionLook = when {
    enabled && focused -> TvCompactActionLook(TvCompactFill.RED, focusRing = false, contentAlpha = 1f)
    enabled -> TvCompactActionLook(TvCompactFill.NEUTRAL, focusRing = false, contentAlpha = 1f)
    focused -> TvCompactActionLook(TvCompactFill.DIM, focusRing = true, contentAlpha = 0.55f)
    else -> TvCompactActionLook(TvCompactFill.DIM, focusRing = false, contentAlpha = 0.4f)
}

private fun TvCompactFill.color(): Color = when (this) {
    // NEUTRAL and RED are the shared action-button colors (TvButtonStyle.kt), so an enabled button looks as before.
    TvCompactFill.NEUTRAL -> ArkivSurfaceHigh
    TvCompactFill.RED -> ArkivRed
    TvCompactFill.DIM -> ArkivSurfaceHigh.copy(alpha = 0.5f)
}

/**
 * An action button that is as wide as its label, [TAB_HEIGHT] tall, in the same style as [TvActionOption]:
 * the "Agregar" of a row of tabs and the buttons of a dialog, where a button 60% of the width would not fit.
 *
 * With [enabled] false it is dimmed in every state (see [tvCompactActionLook]) and its click is ignored,
 * but it stays FOCUSABLE, on purpose: a button
 * that could not take focus would throw it out from under the person the moment its state changes (a label
 * turning into "Revisando…" while an action runs), and D-pad focus would skip it. [icon] goes before the label.
 * [modifier] goes first, so a `focusRequester` on it reaches this Surface's own focus target.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvCompactAction(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Surface animates between its idle and focused/pressed colors itself; pressed follows focused.
    val idle = tvCompactActionLook(enabled, focused = false)
    val onFocus = tvCompactActionLook(enabled, focused = true)
    val content = Color.White.copy(alpha = tvCompactActionLook(enabled, focused).contentAlpha)
    val shape = RoundedCornerShape(8.dp)
    val focusBorder = if (onFocus.focusRing) Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)), shape = shape) else Border.None
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.height(TAB_HEIGHT).semantics { if (!enabled) disabled() },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = idle.fill.color(),
            focusedContainerColor = onFocus.fill.color(),
            pressedContainerColor = onFocus.fill.color(),
            contentColor = Color.White,
            focusedContentColor = Color.White,
            pressedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = focusBorder, pressedBorder = focusBorder),
        interactionSource = interaction,
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                Text(label, color = content, maxLines = 1)
            }
        }
    }
}
