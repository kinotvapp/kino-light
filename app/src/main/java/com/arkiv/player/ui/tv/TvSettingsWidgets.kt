package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
internal fun TvActionOption(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(0.6f),
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
