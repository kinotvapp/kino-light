package app.kino.demo.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kino.demo.data.CatalogRow
import app.kino.demo.data.DemoChannel
import app.kino.demo.data.DemoChannels
import app.kino.demo.data.Film
import app.kino.demo.data.allFilms
import app.kino.demo.data.DemoLive
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurface
import app.kino.demo.ui.theme.KinoSurfaceHigh
import app.kino.demo.ui.theme.KinoTextSecondary
import coil.compose.AsyncImage

private const val FAVORITES = "Favoritos"
private const val RECENT = "Recientes"

/**
 * "En vivo" on the TV: the category rail on the left, the channel list in the middle and, on the
 * right, a preview of the focused channel. The channels are fictional; each one plays a catalog film.
 */
@Composable
fun TvLiveScreen(rows: List<CatalogRow>, onPlay: (Film) -> Unit) {
    val films = remember(rows) { allFilms(rows) }
    var category by rememberSaveable { mutableStateOf(DemoChannels.categories.first()) }
    var focused by remember { mutableStateOf<DemoChannel?>(null) }
    val landing = rememberLandingFocus()
    val channels = when (category) {
        FAVORITES -> DemoChannels.channels.filter { it.number in DemoLive.favorites }
        RECENT -> DemoLive.recent.mapNotNull { n -> DemoChannels.channels.firstOrNull { it.number == n } }
        else -> DemoChannels.channels.filter { it.category == category }
    }
    fun filmOf(channel: DemoChannel) = films.getOrNull(channel.filmIndex % films.size.coerceAtLeast(1))

    Row(Modifier.fillMaxSize().background(KinoBlack).padding(horizontal = 48.dp, vertical = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(200.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("En vivo", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            (listOf(FAVORITES, RECENT) + DemoChannels.categories).forEach { c ->
                TvChoiceChip(label = c, selected = c == category, onClick = { category = c }, modifier = Modifier.fillMaxWidth())
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (channels.isEmpty()) {
                item {
                    Column(Modifier.padding(top = 24.dp)) {
                        Text("Aún no tienes favoritos", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text("Mantén OK sobre un canal para agregarlo.", style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
                    }
                }
            }
            items(channels, key = { it.number }) { channel ->
                Surface(
                    onClick = {
                        DemoLive.open(channel)
                        filmOf(channel)?.let(onPlay)
                    },
                    onLongClick = {
                        if (channel.number in DemoLive.favorites) DemoLive.favorites.remove(channel.number) else DemoLive.favorites.add(channel.number)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) focused = channel }
                        .then(if (channel == channels.first()) Modifier.landingFocus(landing) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = KinoSurface,
                        focusedContainerColor = KinoSurfaceHigh,
                        contentColor = Color.White,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White))),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(channel.number.toString(), style = MaterialTheme.typography.titleMedium, color = KinoTextSecondary, modifier = Modifier.width(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                channel.name + if (channel.number in DemoLive.favorites) "  ★" else "",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            Text("Ahora: ${channel.now}", style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                                Box(Modifier.fillMaxWidth(channel.progress).fillMaxHeight().background(KinoRed))
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            val channel = focused ?: channels.firstOrNull()
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(KinoSurfaceHigh)) {
                channel?.let { filmOf(it) }?.let { film ->
                    AsyncImage(model = film.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(
                    "EN VIVO",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(KinoRed).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (channel != null) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                Text("OK para ver · mantén OK para favoritos", style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
            }
        }
    }
}
