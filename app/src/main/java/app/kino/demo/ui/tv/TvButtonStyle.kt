package app.kino.demo.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurfaceHigh

// One style for every TV action button: idle = dark surface, focused/pressed = Kino red, disabled =
// dimmed. Without it tv-material3 falls back to white idle / black focused.

/** Colors for `androidx.tv.material3.Button(...)`. */
@Composable
fun kinoTvButtonColors() = ButtonDefaults.colors(
    containerColor = KinoSurfaceHigh,
    contentColor = Color.White,
    focusedContainerColor = KinoRed,
    focusedContentColor = Color.White,
    pressedContainerColor = KinoRed,
    pressedContentColor = Color.White,
    disabledContainerColor = KinoSurfaceHigh,
    disabledContentColor = Color.White.copy(alpha = 0.4f),
)

/** Border for `androidx.tv.material3.Button(...)`. */
@Composable
fun kinoTvButtonBorder() = ButtonDefaults.border(
    border = Border.None,
    focusedBorder = Border.None,
    pressedBorder = Border.None,
)
