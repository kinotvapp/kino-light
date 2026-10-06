package app.kino.demo.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kino.demo.data.DemoChannel
import app.kino.demo.data.DemoLiveProvider
import app.kino.demo.data.DemoLiveProviders
import app.kino.demo.data.Film
import app.kino.demo.ui.live.LiveCopy
import app.kino.demo.ui.live.RadioGlyph
import app.kino.demo.ui.live.channelPlaceholder
import app.kino.demo.ui.live.showsRadioArt
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurface
import app.kino.demo.ui.theme.KinoSurfaceHigh
import app.kino.demo.ui.theme.KinoTextSecondary
import coil.compose.AsyncImage

// The TV En vivo screen's pieces: provider tabs, category rail rows, compact channel rows and the
// preview panel. Focus is shown one way on this screen -- a lifted fill and a white ring -- never a
// red fill: red means "live" and "selected" here.

private val ROW_SHAPE = RoundedCornerShape(12.dp)

@Composable
private fun liveSurfaceColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent,
    focusedContainerColor = KinoSurfaceHigh,
    pressedContainerColor = KinoSurfaceHigh,
    contentColor = Color.White,
    focusedContentColor = Color.White,
    pressedContentColor = Color.White,
)

@Composable
private fun liveBorder(shape: androidx.compose.ui.graphics.Shape = ROW_SHAPE) =
    ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape))

/** A provider tab: its colour dot and name, a 3 dp underline in its colour while [selected]. */
@Composable
internal fun TvProviderTab(provider: DemoLiveProvider, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(ROW_SHAPE),
        colors = liveSurfaceColors(),
        border = liveBorder(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(provider.color)))
                Text(
                    provider.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) Color.White else KinoTextSecondary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) Color(provider.color) else Color.Transparent))
        }
    }
}

/** A round icon button of the tabs row (Buscar, Recargar). */
@Composable
internal fun TvRoundIcon(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = KinoSurface,
            focusedContainerColor = KinoSurfaceHigh,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = liveBorder(CircleShape),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
    }
}

/** A plain header of the rail (a group of categories): never focusable. */
@Composable
internal fun TvRailHeader(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = KinoTextSecondary,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
    )
}

/**
 * A row of the rail: an optional icon, the label and, right-aligned, its [count]. The selected row
 * carries the red accent bar and semibold text.
 */
@Composable
internal fun TvRailRow(label: String, icon: ImageVector?, count: Int?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = ClickableSurfaceDefaults.shape(ROW_SHAPE),
        colors = liveSurfaceColors(),
        border = liveBorder(),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).fillMaxHeight(0.6f).background(if (selected) KinoRed else Color.Transparent, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                style = if (icon == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count != null) Text("$count", style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

/** A channel's logo slot: its number (or a radio glyph for a station) on its provider's tint. */
@Composable
internal fun TvChannelTile(channel: DemoChannel, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(Color(DemoLiveProviders.of(channel.provider).color).copy(alpha = 0.6f), Color(0xFF17171C)))),
        contentAlignment = Alignment.Center,
    ) {
        if (showsRadioArt(channel)) {
            RadioGlyph(Modifier.size(22.dp))
        } else {
            Text(channelPlaceholder(channel.number, channel.name), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

/**
 * One compact channel row: tile, name, favourite star, the provider's name in a mixed list ([badge]),
 * and the number. OK plays it; a long OK adds or removes the favourite.
 */
@Composable
internal fun TvChannelRow(
    channel: DemoChannel,
    favorite: Boolean,
    badge: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(60.dp),
        shape = ClickableSurfaceDefaults.shape(ROW_SHAPE),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = KinoSurface,
            focusedContainerColor = KinoSurfaceHigh,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = liveBorder(),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvChannelTile(channel, Modifier.size(40.dp))
            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (favorite) Icon(Icons.Default.Star, contentDescription = "Favorito", tint = Color(0xFFFFC94D), modifier = Modifier.size(18.dp))
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(DemoLiveProviders.of(channel.provider).color).copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text("${channel.number}", style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
        }
    }
}

/**
 * The preview panel: a 16:9 picture (the film it airs, or a radio glyph) with its provider's glow and
 * "● EN VIVO", the channel, its provider and number, what is on now and what OK does.
 */
@Composable
internal fun TvLivePreview(channel: DemoChannel?, film: Film?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val glow = channel?.let { Color(DemoLiveProviders.of(it.provider).color) } ?: KinoSurfaceHigh
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .border(2.dp, glow.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(KinoSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            when {
                channel == null -> Text("Elige un canal de la lista", style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
                showsRadioArt(channel) -> RadioGlyph(Modifier.size(64.dp))
                film != null -> AsyncImage(model = film.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (channel == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(KinoRed))
            Text("EN VIVO", style = MaterialTheme.typography.labelSmall, color = KinoRed, fontWeight = FontWeight.Bold)
        }
        Text(channel.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${DemoLiveProviders.of(channel.provider).name} · Canal ${channel.number}", style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
        Text(LiveCopy.now(channel.now), style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(LiveCopy.watchRow(channel.name), style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary, maxLines = 2)
    }
}
