package app.kino.demo.ui.phone

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kino.demo.data.DemoSource
import app.kino.demo.data.DemoSources
import app.kino.demo.data.DemoSession
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoTextSecondary

private const val COLUMNS = 2
private val SIDE_GUTTER = 16.dp

private enum class ExtensionsTab { RECOMMENDED, INSTALLED }

/**
 * "Plugins" on the phone: the tabs Recomendados and Instalados (n) with "Agregar" at their end.
 * Recomendados searches the catalog's cards and ends with "De la comunidad"; Instalados lists the
 * example plugins with their switch.
 */
@Composable
fun ExtensionsScreen(contentPadding: PaddingValues) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            Text("Plugins", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            ExtensionsContent(bottomInset = contentPadding.calculateBottomPadding(), modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionsContent(bottomInset: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(ExtensionsTab.RECOMMENDED) }
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = SIDE_GUTTER), verticalAlignment = Alignment.CenterVertically) {
            PrimaryScrollableTabRow(
                selectedTabIndex = tab.ordinal,
                modifier = Modifier.weight(1f),
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
                            val label = if (t == ExtensionsTab.RECOMMENDED) "Recomendados" else "Instalados (${DemoSession.installed.size})"
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
            FilledIconButton(
                onClick = { fullAppOnly(context) },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = ACTION_CONTAINER, contentColor = Color.White),
            ) { Icon(Icons.Filled.Add, contentDescription = "Agregar plugin") }
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
                    item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Buscar plugins") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val match = { p: DemoSource -> query.isBlank() || p.name.contains(query.trim(), ignoreCase = true) }
                    val recommended = DemoSources.recommended.filter(match)
                    val community = DemoSources.community.filter(match)
                    items(recommended, key = { "card-${it.id}" }) { p ->
                        CatalogCard(p, installed = DemoSession.isInstalled(p.id), onAction = { fullAppOnly(context) })
                    }
                    if (recommended.isEmpty() && community.isEmpty()) {
                        item(key = "no-match", span = { GridItemSpan(maxLineSpan) }) {
                            Text("No hay plugins que coincidan.", style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
                        }
                    }
                    communityItems(community) { fullAppOnly(context) }
                }
                ExtensionsTab.INSTALLED -> {
                    items(DemoSources.all.filter { DemoSession.isInstalled(it.id) }, key = { "installed-${it.id}" }) { p ->
                        InstalledCard(
                            plugin = p,
                            enabled = DemoSession.enabled[p.id] == true,
                            onToggle = { DemoSession.enabled[p.id] = it },
                            onManage = { fullAppOnly(context) },
                        )
                    }
                }
            }
        }
    }
}

/** "De la comunidad": a header with "Actualizar" and one card per community plugin. */
internal fun LazyGridScope.communityItems(plugins: List<DemoSource>, onAction: () -> Unit) {
    item(key = "community-header", span = { GridItemSpan(maxLineSpan) }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("De la comunidad", style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text("Actualizar", color = KinoRed) }
        }
    }
    items(plugins, key = { "community-${it.id}" }) { p ->
        CatalogCard(p, installed = DemoSession.isInstalled(p.id), onAction = onAction)
    }
}
