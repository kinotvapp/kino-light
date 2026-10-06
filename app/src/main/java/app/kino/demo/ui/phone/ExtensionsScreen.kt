package app.kino.demo.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kino.demo.data.DemoCollection
import app.kino.demo.data.DemoPluginLists
import app.kino.demo.data.DemoSession
import app.kino.demo.data.DemoSource
import app.kino.demo.data.DemoSources
import app.kino.demo.data.PluginCategory
import app.kino.demo.data.categoryChips
import app.kino.demo.data.filterPlugins
import app.kino.demo.ui.components.KinoChip
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.plugins.AddPluginDialog
import app.kino.demo.ui.plugins.NUVIO_REPOS_TITLE
import app.kino.demo.ui.plugins.PluginSettingsDialog
import app.kino.demo.ui.plugins.STREMIO_COLLECTIONS_TITLE
import app.kino.demo.ui.plugins.collectionLine
import app.kino.demo.ui.plugins.COMMUNITY_NOTE
import app.kino.demo.ui.plugins.COMMUNITY_TITLE
import app.kino.demo.ui.plugins.NO_MATCH_LINE
import app.kino.demo.ui.plugins.installedTabLabel
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoTextSecondary

private const val COLUMNS = 2
private val SIDE_GUTTER = 16.dp

/** An item of the grid that takes the whole line: everything but the cards. */
private val FULL_WIDTH: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

private enum class ExtensionsTab { RECOMMENDED, COMMUNITY, INSTALLED }

/**
 * "Plugins" on the phone: the heading with the round "Agregar" at its end (the "Agregar un plugin"
 * dialog), then ONE row with the tabs
 * Recomendados, De la comunidad and Instalados (n). Every tab has the shared "Buscar plugins" field, its
 * own category chips (only the categories its cards have) and its cards. "Gestionar" opens a plugin's
 * settings ("Modo debug", its Registro); Instalados ends with the person's Stremio collections
 * ("Explorar" shows one in place of the tabs) and Nuvio repositories.
 */
@Composable
fun ExtensionsScreen(contentPadding: PaddingValues) {
    var adding by rememberSaveable { mutableStateOf(false) }
    if (adding) AddPluginDialog(onDismiss = { adding = false })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = SIDE_GUTTER, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Plugins", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                FilledIconButton(
                    onClick = { adding = true },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = ACTION_CONTAINER, contentColor = Color.White),
                ) { Icon(Icons.Filled.Add, contentDescription = "Agregar plugin") }
            }
            ExtensionsContent(bottomInset = contentPadding.calculateBottomPadding(), modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionsContent(bottomInset: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(ExtensionsTab.RECOMMENDED) }
    // One search text for every tab; each tab keeps its own chip.
    var query by rememberSaveable { mutableStateOf("") }
    var recommendedChip by rememberSaveable { mutableStateOf<PluginCategory?>(null) }
    var communityChip by rememberSaveable { mutableStateOf<PluginCategory?>(null) }
    var installedChip by rememberSaveable { mutableStateOf<PluginCategory?>(null) }
    var managing by rememberSaveable { mutableStateOf<String?>(null) }
    var browsing by rememberSaveable { mutableStateOf<String?>(null) }
    val installed = DemoSources.all.filter { DemoSession.isInstalled(it.id) }
    installed.firstOrNull { it.id == managing }?.let { PluginSettingsDialog(it, onDismiss = { managing = null }) }

    DemoPluginLists.collections.firstOrNull { it.name == browsing }?.let { collection ->
        BackHandler { browsing = null }
        StremioCollectionScreen(collection, bottomInset, onBack = { browsing = null }, modifier = modifier)
        return
    }

    Column(modifier) {
        PrimaryScrollableTabRow(
            selectedTabIndex = tab.ordinal,
            modifier = Modifier.fillMaxWidth().padding(horizontal = SIDE_GUTTER),
            containerColor = KinoBlack,
            contentColor = Color.White,
            edgePadding = 0.dp,
        ) {
            ExtensionsTab.entries.forEach { t ->
                Tab(
                    selected = t == tab,
                    onClick = { tab = t },
                    selectedContentColor = Color.White,
                    unselectedContentColor = KinoTextSecondary,
                    text = {
                        val label = when (t) {
                            ExtensionsTab.RECOMMENDED -> "Recomendados"
                            ExtensionsTab.COMMUNITY -> COMMUNITY_TITLE
                            ExtensionsTab.INSTALLED -> installedTabLabel(installed.size)
                        }
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = SIDE_GUTTER, end = SIDE_GUTTER, top = 8.dp, bottom = bottomInset + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                ExtensionsTab.RECOMMENDED -> {
                    searchItem(query) { query = it }
                    chipsItem(DemoSources.recommended, recommendedChip) { recommendedChip = it }
                    val shown = filterPlugins(DemoSources.recommended, query, recommendedChip)
                    noMatchItem(shown.isEmpty())
                    items(shown, key = { "card-${it.id}" }) { p ->
                        CatalogCard(p, installed = DemoSession.isInstalled(p.id), onAction = { fullAppOnly(context) })
                    }
                }
                ExtensionsTab.COMMUNITY -> {
                    item(key = "community-note", span = FULL_WIDTH) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(COMMUNITY_NOTE, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary, modifier = Modifier.weight(1f))
                            TextButton(onClick = { fullAppOnly(context) }) { Text("Actualizar", color = KinoRed) }
                        }
                    }
                    searchItem(query) { query = it }
                    chipsItem(DemoSources.community, communityChip) { communityChip = it }
                    val shown = filterPlugins(DemoSources.community, query, communityChip)
                    noMatchItem(shown.isEmpty())
                    items(shown, key = { "community-${it.id}" }) { p ->
                        CatalogCard(p, installed = DemoSession.isInstalled(p.id), onAction = { fullAppOnly(context) })
                    }
                }
                ExtensionsTab.INSTALLED -> {
                    searchItem(query) { query = it }
                    chipsItem(installed, installedChip) { installedChip = it }
                    val shown = filterPlugins(installed, query, installedChip)
                    noMatchItem(shown.isEmpty())
                    items(shown, key = { "installed-${it.id}" }) { p ->
                        InstalledCard(
                            plugin = p,
                            enabled = DemoSession.enabled[p.id] == true,
                            onToggle = { DemoSession.enabled[p.id] = it },
                            onManage = { managing = p.id },
                        )
                    }
                    item(key = "collections", span = FULL_WIDTH) {
                        PluginListSection(STREMIO_COLLECTIONS_TITLE, DemoPluginLists.collections.map { it.name }, "Explorar", onOpen = { browsing = it }, onRemove = { fullAppOnly(context) })
                    }
                    item(key = "nuvio-repos", span = FULL_WIDTH) {
                        PluginListSection(NUVIO_REPOS_TITLE, DemoPluginLists.nuvioRepos, "Ver scrapers", onOpen = { fullAppOnly(context) }, onRemove = { DemoPluginLists.nuvioRepos.remove(it) })
                    }
                }
            }
        }
    }
}

/** The "Buscar plugins" field, a full-width item of the grid; its text is shared by the three tabs. */
private fun LazyGridScope.searchItem(query: String, onQueryChange: (String) -> Unit) {
    item(key = "search", span = FULL_WIDTH) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Buscar plugins") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The tab's category chips on one line that scrolls sideways: "Todos", then each category of [plugins]. */
private fun LazyGridScope.chipsItem(plugins: List<DemoSource>, selected: PluginCategory?, onSelect: (PluginCategory?) -> Unit) {
    val chips = categoryChips(plugins)
    if (chips.size <= 1) return
    item(key = "category-chips", span = FULL_WIDTH) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips, key = { it?.name ?: "all" }) { c ->
                KinoChip(c?.label ?: "Todos", selected = c == selected) { onSelect(c) }
            }
        }
    }
}

private fun LazyGridScope.noMatchItem(empty: Boolean) {
    if (!empty) return
    item(key = "no-match", span = FULL_WIDTH) {
        Text(NO_MATCH_LINE, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
    }
}

/**
 * One of the lists that close Instalados ("Tus colecciones de Stremio", "Tus repositorios de Nuvio"):
 * the title and a row per entry with [openLabel] and "Quitar". Nothing at all when there are none.
 */
@Composable
private fun PluginListSection(title: String, entries: List<String>, openLabel: String, onOpen: (String) -> Unit, onRemove: (String) -> Unit) {
    if (entries.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
        entries.forEach { entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { onOpen(entry) }) { Text(openLabel, color = KinoRed) }
                TextButton(onClick = { onRemove(entry) }) { Text("Quitar", color = KinoTextSecondary) }
            }
        }
    }
}

/**
 * A Stremio collection, in place of the tabs: back arrow and its name, how many addons it offers, and
 * its addons as cards, each with "Instalar". Nothing installs by itself.
 */
@Composable
private fun StremioCollectionScreen(collection: DemoCollection, bottomInset: Dp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        modifier = modifier,
        contentPadding = PaddingValues(start = SIDE_GUTTER, end = SIDE_GUTTER, top = 0.dp, bottom = bottomInset + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "collection-header", span = FULL_WIDTH) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                    }
                    Text(collection.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(collectionLine(collection.addons.size), style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
            }
        }
        items(collection.addons, key = { "addon-${it.id}" }) { p ->
            CatalogCard(p, installed = false, onAction = { fullAppOnly(context) })
        }
    }
}

/** "De la comunidad" ("Elige tus fuentes"): a header with "Actualizar", the note, and one card per community plugin. */
internal fun LazyGridScope.communityItems(plugins: List<DemoSource>, onAction: () -> Unit) {
    item(key = "community-header", span = FULL_WIDTH) {
        Column(Modifier.padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(COMMUNITY_TITLE, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = onAction) { Text("Actualizar", color = KinoRed) }
            }
            Text(COMMUNITY_NOTE, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
        }
    }
    items(plugins, key = { "community-${it.id}" }) { p ->
        CatalogCard(p, installed = DemoSession.isInstalled(p.id), onAction = onAction)
    }
}
