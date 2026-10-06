package app.kino.tv.ui.phone

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kino.tv.data.CatalogRow
import app.kino.tv.data.KinoChannel
import app.kino.tv.data.KinoChannels
import app.kino.tv.data.KinoLive
import app.kino.tv.data.KinoLiveProviders
import app.kino.tv.data.Film
import app.kino.tv.data.allFilms
import app.kino.tv.ui.components.EmptyState
import app.kino.tv.ui.fullAppOnly
import app.kino.tv.ui.live.LiveCopy
import app.kino.tv.ui.live.RadioGlyph
import app.kino.tv.ui.live.channelPlaceholder
import app.kino.tv.ui.live.showsRadioArt
import app.kino.tv.ui.isLandscapeTablet
import app.kino.tv.ui.theme.KinoRed
import app.kino.tv.ui.theme.KinoSurfaceHigh
import app.kino.tv.ui.theme.KinoTextSecondary

private const val FAVORITES = "__favorites"
private const val RECENT = "__recent"

/**
 * "En vivo" on the phone: search box, the source chips (one per provider, the chosen one always lit),
 * the provider's category chips (Favoritos and Recientes first, "Mis canales y listas" last) and the
 * channel grid with what is on now. The channels are fictional; each one plays a catalog film,
 * and a radio station's tile shows a radio glyph.
 */
@Composable
fun LiveScreen(rows: List<CatalogRow>, contentPadding: PaddingValues, onPlay: (Film) -> Unit) {
    val context = LocalContext.current
    val films = remember(rows) { allFilms(rows) }
    var search by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf(KinoLiveProviders.TV) }
    var category by rememberSaveable { mutableStateOf(KinoChannels.categoriesOf(KinoLiveProviders.TV).first()) }
    val shown = when {
        search.isNotBlank() -> KinoChannels.search(search)
        category == FAVORITES -> KinoChannels.channels.filter { it.number in KinoLive.favorites }
        category == RECENT -> KinoLive.recent.mapNotNull { n -> KinoChannels.channels.firstOrNull { it.number == n } }
        else -> KinoChannels.channels.filter { it.provider == provider && it.category == category }
    }

    fun open(channel: KinoChannel) {
        KinoLive.open(channel)
        // A radio station has no picture to show: the full app plays its sound.
        if (channel.radio) fullAppOnly(context) else films.getOrNull(channel.filmIndex % films.size.coerceAtLeast(1))?.let(onPlay)
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text(LiveCopy.SEARCH_HINT) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, contentDescription = "Limpiar") }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // The sources: always on screen, and the chosen one always lit.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(KinoLiveProviders.all, key = { it.id }) { p ->
                CategoryChip(p.name, null, p.id == provider, dot = Color(p.color)) {
                    provider = p.id
                    if (category != FAVORITES && category != RECENT) category = KinoChannels.categoriesOf(p.id).firstOrNull() ?: FAVORITES
                }
            }
        }
        if (search.isBlank()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { CategoryChip("Favoritos", Icons.Default.Star, category == FAVORITES) { category = FAVORITES } }
                item { CategoryChip("Recientes", Icons.Default.History, category == RECENT) { category = RECENT } }
                items(KinoChannels.categoriesOf(provider), key = { it }) { cat ->
                    CategoryChip(cat, null, category == cat) { category = cat }
                }
                item { CategoryChip(LiveCopy.MY_SOURCES, Icons.Default.Settings, false) { fullAppOnly(context) } }
            }
        }
        if (shown.isEmpty()) {
            when {
                search.isNotBlank() -> EmptyState(LiveCopy.NO_RESULTS, subtitle = LiveCopy.NO_RESULTS_BODY)
                category == FAVORITES -> EmptyState("Sin favoritos todavía", subtitle = "Mantén pulsado un canal para agregarlo.")
                category == RECENT -> EmptyState(LiveCopy.NO_RECENTS, subtitle = LiveCopy.NO_RECENTS_BODY)
                else -> EmptyState(LiveCopy.NO_RESULTS, subtitle = LiveCopy.NO_RESULTS_BODY)
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
                    isFavorite = channel.number in KinoLive.favorites,
                    onClick = { open(channel) },
                    onLongClick = {
                        if (channel.number in KinoLive.favorites) KinoLive.favorites.remove(channel.number) else KinoLive.favorites.add(channel.number)
                    },
                )
            }
        }
    }
}

/** A chip of En vivo; a source's chip carries its colour as a [dot]. */
@Composable
private fun CategoryChip(label: String, icon: ImageVector?, selected: Boolean, dot: Color? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = when {
            icon != null -> { { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) } }
            dot != null -> { { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)) } }
            else -> null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = KinoRed,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
        ),
    )
}

/**
 * A channel: its logo slot (the number, or a radio glyph for a station, on its provider's tint), name,
 * what is on now and how far along.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(channel: KinoChannel, isFavorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
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
                    .background(Brush.linearGradient(listOf(Color(KinoLiveProviders.of(channel.provider).color).copy(alpha = 0.45f), Color(0xFF17171C)))),
                contentAlignment = Alignment.Center,
            ) {
                if (showsRadioArt(channel)) {
                    RadioGlyph(Modifier.size(40.dp))
                } else {
                    Text(channelPlaceholder(channel.number, channel.name), style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.6f))
                }
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
