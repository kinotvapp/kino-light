package app.kino.demo.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.demo.data.CatalogRow
import app.kino.demo.data.DemoChannel
import app.kino.demo.data.DemoChannels
import app.kino.demo.data.DemoLive
import app.kino.demo.data.DemoLiveProviders
import app.kino.demo.data.Film
import app.kino.demo.data.allFilms
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.live.LiveCopy
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoTextSecondary

private const val FAVORITES = "\u0000favorites"
private const val RECENT = "\u0000recent"

/** Width of the left column: the category rail, or the keyboard while searching. */
private val SIDE_COLUMN = 220.dp
private val SEARCH_COLUMN = 290.dp

/** Width of the preview panel. */
private val PANEL_WIDTH = 340.dp

/**
 * "En vivo" on the TV: provider tabs on top (with Buscar and Recargar as round icons), the category
 * rail on the left (Favoritos, Recientes, the provider's categories grouped with their counts, and
 * "Mis canales y listas" last), a compact channel list in the centre and, on the right, a preview of
 * the focused channel. 🔍 swaps the rail for the remote's keyboard and searches every provider. The
 * channels are fictional; each one plays a catalog film, a radio station says it is in the full app.
 */
@Composable
fun TvLiveScreen(rows: List<CatalogRow>, onPlay: (Film) -> Unit) {
    val context = LocalContext.current
    val films = remember(rows) { allFilms(rows) }
    var provider by rememberSaveable { mutableStateOf(DemoLiveProviders.TV) }
    var category by rememberSaveable { mutableStateOf(DemoChannels.categoriesOf(DemoLiveProviders.TV).first()) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf<DemoChannel?>(null) }
    val landing = rememberLandingFocus()
    BackHandler(enabled = searchOpen) { searchOpen = false; search = "" }

    val searching = searchOpen && search.isNotBlank()
    val channels = when {
        searching -> DemoChannels.search(search)
        category == FAVORITES -> DemoChannels.channels.filter { it.number in DemoLive.favorites }
        category == RECENT -> DemoLive.recent.mapNotNull { n -> DemoChannels.channels.firstOrNull { it.number == n } }
        else -> DemoChannels.channels.filter { it.provider == provider && it.category == category }
    }
    // The provider's badge, only where providers mix.
    val mixed = searching || category == FAVORITES || category == RECENT
    fun filmOf(channel: DemoChannel) = films.getOrNull(channel.filmIndex % films.size.coerceAtLeast(1))
    fun open(channel: DemoChannel) {
        DemoLive.open(channel)
        if (channel.radio) fullAppOnly(context) else filmOf(channel)?.let(onPlay)
    }
    fun toggleFavorite(channel: DemoChannel) {
        if (channel.number in DemoLive.favorites) DemoLive.favorites.remove(channel.number) else DemoLive.favorites.add(channel.number)
    }

    Column(Modifier.fillMaxSize().background(KinoBlack).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("En vivo", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(end = 16.dp))
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                items(DemoLiveProviders.all, key = { it.id }) { p ->
                    TvProviderTab(p, selected = p.id == provider, onClick = {
                        provider = p.id
                        if (category != FAVORITES && category != RECENT) category = DemoChannels.categoriesOf(p.id).firstOrNull() ?: FAVORITES
                    })
                }
            }
            TvRoundIcon(Icons.Default.Search, "Buscar", { searchOpen = !searchOpen; search = "" })
            Spacer(Modifier.width(12.dp))
            TvRoundIcon(Icons.Default.Refresh, "Recargar", { fullAppOnly(context) })
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (searchOpen) {
                Column(Modifier.width(SEARCH_COLUMN).fillMaxHeight().padding(top = 8.dp)) {
                    val keyboardFocus = rememberDialogFocus()
                    Text(
                        search.ifBlank { LiveCopy.SEARCH_HINT },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (search.isBlank()) KinoTextSecondary else Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    TvKeyboard(text = search, onTextChange = { search = it }, firstKey = Modifier.dialogFocus(keyboardFocus), modifier = Modifier.weight(1f))
                    if (search.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        TvCompactAction(label = "Borrar búsqueda", modifier = Modifier.fillMaxWidth()) { searchOpen = false; search = "" }
                    }
                }
            } else {
                LazyColumn(Modifier.width(SIDE_COLUMN).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item(key = "fav") {
                        TvRailRow("Favoritos", Icons.Default.Star, DemoLive.favorites.size, category == FAVORITES, { category = FAVORITES })
                    }
                    item(key = "rec") {
                        TvRailRow("Recientes", Icons.Default.History, DemoLive.recent.size, category == RECENT, { category = RECENT })
                    }
                    // Grouped under their headers, in the order the groups first appear; a category with no group has none.
                    DemoChannels.categoriesOf(provider).groupBy { DemoChannels.groups[it] }.forEach { (group, categories) ->
                        if (group != null) item(key = "h:$group") { TvRailHeader(group) }
                        categories.forEach { c ->
                            item(key = "c:$c") {
                                TvRailRow(c, null, DemoChannels.channels.count { it.provider == provider && it.category == c }, category == c, { category = c })
                            }
                        }
                    }
                    item(key = "own") { TvRailRow(LiveCopy.MY_SOURCES, Icons.Default.Settings, null, false, { fullAppOnly(context) }) }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
                if (channels.isEmpty()) {
                    item {
                        val (title, body) = when {
                            searchOpen -> LiveCopy.NO_RESULTS to LiveCopy.NO_RESULTS_BODY
                            category == FAVORITES -> LiveCopy.NO_FAVORITES to "Mantén OK sobre un canal para agregarlo."
                            category == RECENT -> LiveCopy.NO_RECENTS to LiveCopy.NO_RECENTS_BODY
                            provider == DemoLiveProviders.OWN -> LiveCopy.NO_OWN to "Elige «${LiveCopy.MY_SOURCES}» (⚙) al final de la lista de categorías para agregar un canal o una lista."
                            else -> "Sin canales" to "No encontramos canales en esta categoría."
                        }
                        Column(Modifier.padding(top = 24.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(body, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
                        }
                    }
                }
                items(channels, key = { it.number }) { channel ->
                    TvChannelRow(
                        channel = channel,
                        favorite = channel.number in DemoLive.favorites,
                        badge = if (mixed) DemoLiveProviders.of(channel.provider).name else null,
                        onClick = { open(channel) },
                        onLongClick = { toggleFavorite(channel) },
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) focused = channel }
                            .then(if (channel == channels.first()) Modifier.landingFocus(landing) else Modifier),
                    )
                }
            }
            val shown = focused?.takeIf { it in channels } ?: channels.firstOrNull()
            TvLivePreview(shown, shown?.let { filmOf(it) }, Modifier.width(PANEL_WIDTH).fillMaxHeight())
        }
    }
}
