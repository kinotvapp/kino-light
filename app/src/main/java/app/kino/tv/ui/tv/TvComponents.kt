package app.kino.tv.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.tv.ui.LocalReducedEffects
import app.kino.tv.ui.cardFocusScale
import app.kino.tv.ui.theme.KinoSurfaceHigh
import app.kino.tv.ui.theme.KinoTextSecondary
import coil.compose.AsyncImage

/** Fallback for cards with no image: gradient + video icon. */
@Composable
private fun CardPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Movie,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.22f),
            modifier = Modifier.size(52.dp),
        )
    }
}

/**
 * Landscape card (16:9) with full-bleed art and no overlaid title: the focused film's name shows
 * in the hero above. Fixed height; zooms and gets a white border on focus.
 */
@Composable
fun TvLandscapeCard(
    title: String,
    imageUrl: String?,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(cardHeight).onFocusChanged { if (it.isFocused) onFocus() },
        scale = cardFocusScale(LocalReducedEffects.current),
        colors = CardDefaults.colors(containerColor = KinoSurfaceHigh),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White))),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .background(KinoSurfaceHigh),
        ) {
            if (imageUrl.isNullOrBlank()) {
                CardPlaceholder()
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Fixed-height row label, so two rows fit exactly in the scrollable zone. */
@Composable
fun TvRowLabel(text: String, height: Dp) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height).padding(start = 48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = KinoTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
