package app.kino.demo.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurface
import app.kino.demo.ui.theme.KinoSurfaceHigh

/** A settings row button: dark surface, red on focus, 60% of the width. */
@Composable
internal fun TvActionOption(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(0.6f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = KinoSurfaceHigh,
            focusedContainerColor = KinoRed,
            pressedContainerColor = KinoRed,
            contentColor = Color.White,
            focusedContentColor = Color.White,
            pressedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
    ) {
        Text(label, color = Color.White, modifier = Modifier.padding(16.dp))
    }
}

