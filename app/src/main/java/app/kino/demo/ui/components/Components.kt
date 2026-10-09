package app.kino.demo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kino.demo.ui.theme.KinoSurfaceHigh
import app.kino.demo.ui.theme.KinoTextSecondary
import coil.compose.AsyncImage

/** Poster-shaped (2:3) cover with the title below. */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    meta: String? = null,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(KinoSurfaceHigh),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (meta != null) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = KinoTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

