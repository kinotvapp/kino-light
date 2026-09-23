package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh

/**
 * Lets focus LEAVE a text field with the D-pad.
 *
 * Compose's `TextField`s (foundation, not tv-material3) consume up/down because they use them to
 * move the cursor between lines. On the phone that doesn't get in the way -you tap the next
 * field- but on TV the only way to move is the D-pad, so once focus enters a field it never
 * leaves: in Settings you couldn't get from email to password or down to "Log in".
 *
 * `onPreviewKeyEvent` sees the key BEFORE the field, so we move focus by hand. If there's nowhere
 * to move to we return `false` and the event follows its normal course into the field.
 */
@Composable
fun Modifier.dpadFocusEscape(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}

/** Fallback for cards with no poster (any source can lack one): gradient + video icon. */
@Composable
private fun CardPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C))),
            ),
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

/** Landscape card (16:9) with full-bleed art, badge, and overlaid title. Fixed height. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLandscapeCard(
    title: String,
    imageUrl: String?,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeColor: Color = ArkivRed,
    episodeCountLabel: String? = null,
    /**
     * New chapters since the last time the detail was opened. 0 = nothing is drawn.
     * See [com.arkiv.player.data.newcontent.NewEpisodeCounter].
     */
    newEpisodes: Int = 0,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.height(cardHeight).onFocusChanged { if (it.isFocused) onFocus() },
        scale = CardDefaults.scale(focusedScale = 1.08f),
        colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .background(ArkivSurfaceHigh),
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
            // No overlaid title: the content's name shows up top in the hero on focus.
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (episodeCountLabel != null) {
                Text(
                    text = episodeCountLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // New episodes. Goes bottom-right -and not top- because up top the source badge and
            // the episode count already coexist: a third label there would cover the art right
            // where the poster's face usually is. In red so it stands out from the other two,
            // which are informational and gray.
            if (newEpisodes > 0) {
                Text(
                    text = "+$newEpisodes",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ArkivRed)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Landscape thumbnail (16:9) with the name overlaid and a progress bar. Netflix style. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvWideCard(
    title: String,
    imageUrl: String?,
    progress: Float,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(cardHeight).onFocusChanged { if (it.isFocused) onFocus() },
        scale = CardDefaults.scale(focusedScale = 1.08f),
        colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .background(ArkivSurfaceHigh),
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
            // No overlaid text: the name shows up top in the hero on focus.
            // Progress bar on the bottom edge.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x66000000)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxSize()
                        .background(ArkivRed),
                )
            }
        }
    }
}
