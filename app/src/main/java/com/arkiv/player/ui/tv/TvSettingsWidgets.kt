package com.arkiv.player.ui.tv

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

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

/**
 * An action button that is as wide as its label, [TAB_HEIGHT] tall, in the same style as [TvActionOption]:
 * the "Agregar" of a row of tabs and the buttons of a dialog, where a button 60% of the width would not fit.
 *
 * With [enabled] false it is dimmed and its click is ignored, but it stays FOCUSABLE, on purpose: a button
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
    val content = Color.White.copy(alpha = if (enabled) 1f else 0.4f)
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.height(TAB_HEIGHT).semantics { if (!enabled) disabled() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = tvButtonColors(),
        border = tvButtonBorder(),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                Text(label, color = content, maxLines = 1)
            }
        }
    }
}
