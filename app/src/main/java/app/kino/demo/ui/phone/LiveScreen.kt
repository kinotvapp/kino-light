package app.kino.demo.ui.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kino.demo.data.CatalogRow
import app.kino.demo.data.DemoChannel
import app.kino.demo.data.DemoChannels
import app.kino.demo.data.DemoLive
import app.kino.demo.data.Film
import app.kino.demo.data.allFilms
import app.kino.demo.ui.components.EmptyState
import app.kino.demo.ui.isLandscapeTablet
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurfaceHigh
import app.kino.demo.ui.theme.KinoTextSecondary

private const val FAVORITES = "__favorites"
private const val RECENT = "__recent"

/**
 * "En vivo" on the phone: search box, category chips (Favoritos and Recientes first) and the channel
 * grid with what is on now. The demo's channels are fictional; each one plays a catalog film.
 */
@Composable
fun LiveScreen(rows: List<CatalogRow>, contentPadding: PaddingValues, onPlay: (Film) -> Unit) {
    val films = remember(rows) { allFilms(rows) }
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(DemoChannels.categories.first()) }
    val shown = when {
        search.isNotBlank() -> DemoChannels.channels.filter { it.name.contains(search.trim(), ignoreCase = true) || it.number.toString() == search.trim() }
        category == FAVORITES -> DemoChannels.channels.filter { it.number in DemoLive.favorites }
        category == RECENT -> DemoLive.recent.mapNotNull { n -> DemoChannels.channels.firstOrNull { it.number == n } }
        else -> DemoChannels.channels.filter { it.category == category }
    }

    fun open(channel: DemoChannel) {
        DemoLive.open(channel)
        films.getOrNull(channel.filmIndex % films.size.coerceAtLeast(1))?.let(onPlay)
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Buscar por nombre o número…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, contentDescription = "Limpiar") }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (search.isBlank()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { CategoryChip("Favoritos", Icons.Default.Star, category == FAVORITES) { category = FAVORITES } }
                item { CategoryChip("Recientes", Icons.Default.History, category == RECENT) { category = RECENT } }
                items(DemoChannels.categories, key = { it }) { cat ->
                    CategoryChip(cat, null, category == cat) { category = cat }
                }
            }
        }
        if (shown.isEmpty()) {
            if (category == FAVORITES && search.isBlank()) {
                EmptyState("Sin favoritos todavía", subtitle = "Mantén pulsado un canal para agregarlo.")
            } else {
                EmptyState("Sin resultados", subtitle = "Prueba con otro nombre o número de canal.")
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isLandscapeTablet()) 4 else 2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.number }) { channel ->
                ChannelCard(
                    channel = channel,
                    isFavorite = channel.number in DemoLive.favorites,
                    onClick = { open(channel) },
                    onLongClick = {
                        if (channel.number in DemoLive.favorites) DemoLive.favorites.remove(channel.number) else DemoLive.favorites.add(channel.number)
                    },
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) } },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = KinoRed,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
        ),
    )
}

/** A channel: its logo slot (the number on a dark gradient), name, what is on now and how far along. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(channel: DemoChannel, isFavorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(KinoSurfaceHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(channel.number.toString(), style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.6f))
            }
            if (isFavorite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favorito",
                    tint = KinoRed,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
        }
        Text(channel.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("Ahora: ${channel.now}", style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x33FFFFFF)),
        ) {
            Box(Modifier.fillMaxWidth(channel.progress).fillMaxSize().background(KinoRed))
        }
    }
}
