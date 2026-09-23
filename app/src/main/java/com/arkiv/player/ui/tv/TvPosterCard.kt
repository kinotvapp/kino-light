package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Poster card (2:3, width = height x 2/3) with a title below, up to 2 lines.
 * Same focus/color pattern as [TvLandscapeCard].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPosterCard(
    title: String,
    posterUrl: String?,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    /** Second line under the title ("24 ep.", "12 capítulos vistos"). Null = not drawn. */
    subtitle: String? = null,
    onFocus: () -> Unit = {},
    /** Long press. Null = the card offers no context menu. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.width(cardHeight * 2f / 3f),
    ) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFocus() },
            scale = CardDefaults.scale(focusedScale = 1.08f),
            colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
            border = CardDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
            ),
        ) {
            if (posterUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(ArkivSurfaceHigh),
                )
            } else {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        }
        if (showTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // 16 dp and not 6: on focus, the card scales 1.08 from its center, so a 200 dp
                // cover grows ~8 dp downward and with 6 dp of clearance it covered the title.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
        }
        if (showTitle && subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
