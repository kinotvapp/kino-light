package com.arkiv.player.ui.tv

// Single style for action buttons on TV: idle = black + 1dp white border; focused/pressed =
// Arkiv red background with no white border; disabled = dimmed dark surface. Single source of
// truth so no button ever falls back to tv.material3's default colors (white idle / black
// focused, exactly the opposite of what we want).
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh

/** Colors for `androidx.tv.material3.Button(...)`. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun arkivTvButtonColors() = ButtonDefaults.colors(
    containerColor = ArkivSurfaceHigh,
    contentColor = Color.White,
    focusedContainerColor = ArkivRed,
    focusedContentColor = Color.White,
    pressedContainerColor = ArkivRed,
    pressedContentColor = Color.White,
    disabledContainerColor = ArkivSurfaceHigh,
    disabledContentColor = Color.White.copy(alpha = 0.4f),
)

/** Border for `androidx.tv.material3.Button(...)`. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun arkivTvButtonBorder() = ButtonDefaults.border(
    border = Border.None,
    focusedBorder = Border.None,
    pressedBorder = Border.None,
)

/** Colors for action buttons made with `androidx.tv.material3.Surface(...)`. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun arkivTvSurfaceColors() = ClickableSurfaceDefaults.colors(
    containerColor = ArkivSurfaceHigh,
    focusedContainerColor = ArkivRed,
    pressedContainerColor = ArkivRed,
    contentColor = Color.White,
    focusedContentColor = Color.White,
    pressedContentColor = Color.White,
)

/** Border for action buttons made with `androidx.tv.material3.Surface(...)`. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun arkivTvSurfaceBorder() = ClickableSurfaceDefaults.border(
    border = Border.None,
    focusedBorder = Border.None,
    pressedBorder = Border.None,
)
