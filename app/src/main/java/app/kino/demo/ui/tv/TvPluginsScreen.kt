package app.kino.demo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.demo.data.DemoSession
import app.kino.demo.data.DemoSources
import app.kino.demo.data.PluginCategory
import app.kino.demo.data.categoryChips
import app.kino.demo.data.filterPlugins
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.plugins.COMMUNITY_NOTE
import app.kino.demo.ui.plugins.COMMUNITY_TITLE
import app.kino.demo.ui.plugins.NO_MATCH_LINE
import app.kino.demo.ui.plugins.installedTabLabel
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoTextSecondary

/** Cards per line of every tab's grid. */
private const val TV_PLUGIN_COLUMNS = 4

private enum class TvPluginsTab { RECOMMENDED, COMMUNITY, INSTALLED }

/** "Plugins" on the TV, from the Home rail: the title and [TvPluginsContent], full screen. */
@Composable
fun TvPluginsScreen() {
    val landing = rememberLandingFocus()
    Column(Modifier.fillMaxSize().background(KinoBlack).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text("Plugins", style = MaterialTheme.typography.titleLarge, color = Color.White)
        TvPluginsContent(landing, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * The TV's plugins: ONE header row with the tabs Recomendados, De la comunidad and Instalados (n),
 * "Buscar plugins" (opens the search field under the row; one text for every tab) and "Agregar" (the
 * "Agregar un plugin" dialog); then
 * the selected tab's category chips (only the categories its cards have) and its grid of cards.
 * [landing], when given, goes on the selected tab.
 */
@Composable
fun TvPluginsContent(landing: LandingFocus? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(TvPluginsTab.RECOMMENDED) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    if (adding) TvAddPluginDialog(onDismiss = { adding = false })
    var query by rememberSaveable { mutableStateOf("") }
    var recommendedChip by rememberSaveable { mutableStateOf<PluginCategory?>(null) }
    var communityChip by rememberSaveable { mutableStateOf<PluginCategory?>(null) }
    var installedChip by rememberSaveable { mutableStateOf<PluginCategory?>(null) }
    val installed = DemoSources.all.filter { DemoSession.isInstalled(it.id) }
    val (source, chip, setChip) = when (tab) {
        TvPluginsTab.RECOMMENDED -> Triple(DemoSources.recommended, recommendedChip) { c: PluginCategory? -> recommendedChip = c }
        TvPluginsTab.COMMUNITY -> Triple(DemoSources.community, communityChip) { c: PluginCategory? -> communityChip = c }
        TvPluginsTab.INSTALLED -> Triple(installed, installedChip) { c: PluginCategory? -> installedChip = c }
    }
    val chips = categoryChips(source)
    val shown = filterPlugins(source, query, chip)

    Column(modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(TvPluginsTab.entries.toList()) { t ->
                    TvTab(
                        label = when (t) {
                            TvPluginsTab.RECOMMENDED -> "Recomendados"
                            TvPluginsTab.COMMUNITY -> COMMUNITY_TITLE
                            TvPluginsTab.INSTALLED -> installedTabLabel(installed.size)
                        },
                        selected = t == tab,
                        onClick = { tab = t },
                        modifier = if (t == tab && landing != null) Modifier.landingFocus(landing) else Modifier,
                    )
                }
            }
            TvCompactAction(label = "Buscar plugins", icon = Icons.Default.Search) { searching = !searching }
            Spacer(Modifier.width(10.dp))
            TvCompactAction(label = "Agregar", icon = Icons.Default.Add) { adding = true }
        }
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { androidx.compose.material3.Text("Buscar plugins") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.5f).padding(horizontal = 4.dp),
            )
        }
        if (tab == TvPluginsTab.COMMUNITY) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(COMMUNITY_NOTE, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary, modifier = Modifier.weight(1f))
                TvCompactAction(label = "Actualizar") { fullAppOnly(context) }
            }
        }
        if (chips.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(chips, key = { it?.name ?: "all" }) { c ->
                    TvChoiceChip(label = c?.label ?: "Todos", selected = c == chip, onClick = { setChip(c) })
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(TV_PLUGIN_COLUMNS),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(NO_MATCH_LINE, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
                }
            }
            when (tab) {
                TvPluginsTab.RECOMMENDED, TvPluginsTab.COMMUNITY -> items(shown, key = { "card-${it.id}" }) { p ->
                    val isInstalled = DemoSession.isInstalled(p.id)
                    TvCatalogCard(p, if (isInstalled) "Instalado ✓" else "Instalar", isInstalled, onClick = { if (!isInstalled) fullAppOnly(context) })
                }
                TvPluginsTab.INSTALLED -> items(shown, key = { "inst-${it.id}" }) { p ->
                    val on = DemoSession.enabled[p.id] == true
                    TvCatalogCard(
                        p,
                        if (on) "Activo · OK para desactivar" else "Desactivado · OK para activar",
                        actionIsQuiet = on,
                        onClick = { DemoSession.enabled[p.id] = !on },
                    )
                }
            }
        }
    }
}
