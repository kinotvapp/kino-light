package com.arkiv.player.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.tv.gridLinesWithStatus

/** Recommended plugins per line of the grid. */
internal const val PHONE_CATALOG_COLUMNS = 2

/** Space between the cards, in both directions, and between the full-width items. */
private val GRID_SPACING = 12.dp

/** The phone's side gutter; the message above the tabs uses it too so both line up. */
private val SIDE_GUTTER = 16.dp

/** An item of the grid that takes the whole line: everything but the cards. */
private val FULL_WIDTH: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/** The least a touch target measures (Material's and Android's accessibility minimum). */
private val MIN_TARGET = 48.dp

/** The "Agregar" button's container: the brand red at low strength, as the cards' action, so it reads as an action without shouting. */
private val ADD_CONTAINER = ArkivRed.copy(alpha = 0.30f)

/**
 * What the Plugins screen shows on the phone, hosted by Ajustes ▸ Plugins under its own header: no title,
 * no back arrow and no Back handling of its own, and no insets (the host pads the top and the keyboard;
 * [bottomInset] is the system bar it leaves at the bottom, which the lists keep clear). From top to bottom:
 * what an action answers (the progress bar and, on its own line, the message), ONE row with the two tabs
 * **Recomendados** and **Instalados (n)** and, at its end, the **Agregar** button, and the selected tab's
 * body. **Recomendados**: search the recommended plugins (cards in a two-column grid) and install one.
 * **Instalados**: manage what is installed. **Agregar** opens a modal to add one by `usuario/repositorio`
 * ([AddCustomPluginModal]). Installing always goes through the consent sheet, whichever tab or modal it
 * starts from.
 *
 * "Agregar" is icon-only (a plus, no label): hosted in Ajustes ▸ Plugins this content has very little
 * height to spare (Ajustes' own chip row sits above it), so every dp the header can give back to the lists
 * matters. The tab row scrolls ([PrimaryScrollableTabRow]) rather than splitting the width evenly, so
 * "Recomendados" and "Instalados (n)" never wrap or clip at a large font, whatever `n` is.
 *
 * The message sits on its own line, full width, ONLY while there is one: no space is reserved for it when
 * there is none, which is the common case.
 *
 * Each tab is its own scrolling list, so either can grow: the body takes the height its host leaves (the
 * host must bound it). What must be seen wherever the person is stays above the tabs. The dialogs (consent,
 * uninstall, Configurar) are drawn over whichever tab is showing, and the Configurar that an install needing
 * setup opens likewise. The search text lives in the view model, so it survives switching tabs.
 */
@Composable
internal fun PluginsContent(bottomInset: Dp, modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val vm: PluginsViewModel = viewModel(
        key = "plugins",
        factory = viewModelFactory { initializer { PluginsViewModel(graph.pluginAdmin, catalogProvider = graph.pluginCatalog, artProvider = graph.catalogArt, discovery = graph.pluginDiscovery) } },
    )
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val community by vm.community.collectAsStateWithLifecycle()
    val art by vm.art.collectAsStateWithLifecycle()
    val rowMessageId = rowMessagePluginId(state, plugins)

    // The screen's own state, kept across rotation: the selected tab and whether the person asked for the
    // modal. Hoisted list states keep each tab's scroll position while the other tab is showing.
    var tab by rememberSaveable { mutableStateOf(PluginsTab.RECOMMENDED) }
    var addRequested by rememberSaveable { mutableStateOf(false) }
    val recommendedGrid = rememberLazyGridState()
    val installedGrid = rememberLazyGridState()
    val modalVisible = addModalVisible(addRequested, state)

    Column(modifier) {
        // Above the tabs, not inside them: what an install or an add answers must be seen on either tab,
        // wherever its list is scrolled to. A message about one installed plugin shows on its own row, and
        // while the modal is up its message is drawn inside it. No space is reserved when there is none.
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArkivRed)
        if (rowMessageId == null && !modalVisible) {
            state.message?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = Color.White,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SIDE_GUTTER, vertical = 8.dp),
                )
            }
        }
        PluginsTabRow(
            selected = tab,
            installedCount = plugins.size,
            addEnabled = !state.busy,
            onSelect = { tab = it },
            onAdd = {
                // The modal opens empty: what an earlier action said is not its news, and neither is an address
                // a confirmed install left behind when it failed. (Changing the address also drops the message.)
                vm.onAddressChange("")
                addRequested = true
            },
        )
        when (tab) {
            PluginsTab.RECOMMENDED -> RecommendedTab(
                vm = vm, query = state.query, busy = state.busy, catalog = catalog, community = community, art = art,
                gridState = recommendedGrid, bottomInset = bottomInset,
                modifier = Modifier.weight(1f),
            )
            PluginsTab.INSTALLED -> InstalledTab(
                vm = vm, plugins = plugins, art = art, busy = state.busy,
                message = state.message, rowMessageId = rowMessageId,
                gridState = installedGrid, bottomInset = bottomInset,
                onBrowseRecommended = { tab = PluginsTab.RECOMMENDED },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (modalVisible) {
        AddCustomPluginModal(
            address = state.address,
            busy = state.busy,
            message = state.message.takeIf { rowMessageId == null },
            onAddressChange = vm::onAddressChange,
            onSubmit = vm::add,
            onDismiss = {
                // Cancelar, a tap outside and Back forget what was typed and what the last try said.
                addRequested = false
                vm.onAddressChange(addressAfterDialogDismissed())
            },
        )
    }
    state.consent?.let {
        PluginConsentDialog(
            it,
            onInstall = {
                addRequested = addRequestAfterConsent(addRequested, confirmed = true)
                vm.confirmInstall()
            },
            onCancel = {
                addRequested = addRequestAfterConsent(addRequested, confirmed = false)
                vm.cancelConsent()
            },
        )
    }
    state.confirmUninstall?.let { PluginUninstallDialog(it, onConfirm = vm::confirmUninstall, onCancel = vm::cancelUninstall) }
    state.configuring?.let { PluginConfigDialog(it, isTv = false, vm = vm) }
}

/**
 * The "Agregar" button: icon-only, [MIN_TARGET] square, dimmed and inert while an action runs. It used to
 * carry the word "Agregar" on a line of its own above the tabs; both cost more height than Ajustes ▸ Plugins
 * can spare, so it moved into the tab row and dropped its label (the plus is enough, and the content
 * description keeps it named for accessibility).
 */
@Composable
private fun AddPluginIconButton(enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(MIN_TARGET),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = ADD_CONTAINER, contentColor = Color.White),
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Agregar plugin")
    }
}

/**
 * The two tabs and, at the row's end, the "Agregar" button. The Instalados tab carries how many plugins are
 * installed. The tabs scroll ([PrimaryScrollableTabRow], `edgePadding = 0.dp`) instead of splitting the
 * remaining width evenly: at a phone's width, split evenly with a large font, "Instalados (n)" could wrap or
 * clip; a tab sized to its own text never does, whatever `n` is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginsTabRow(
    selected: PluginsTab,
    installedCount: Int,
    addEnabled: Boolean,
    onSelect: (PluginsTab) -> Unit,
    onAdd: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = SIDE_GUTTER), verticalAlignment = Alignment.CenterVertically) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selected.ordinal,
            modifier = Modifier.weight(1f),
            containerColor = ArkivBlack,
            contentColor = Color.White,
            edgePadding = 0.dp,
        ) {
            PluginsTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    selectedContentColor = Color.White,
                    unselectedContentColor = ArkivTextSecondary,
                    text = {
                        val label = when (tab) {
                            PluginsTab.RECOMMENDED -> "Recomendados"
                            PluginsTab.INSTALLED -> installedTabLabel(installedCount)
                        }
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
        AddPluginIconButton(enabled = addEnabled, onClick = onAdd)
    }
}

/**
 * Recomendados: the search box, the notice while the list is only the copy shipped in the APK, and the
 * recommended plugins, each a [PluginCard] in one cell of a two-column grid, then "De la comunidad"
 * ([communityItems]). Everything but the cards is a full-width item.
 */
@Composable
private fun RecommendedTab(
    vm: PluginsViewModel,
    query: String,
    busy: Boolean,
    catalog: CatalogUiState,
    community: CommunityUiState,
    art: Map<String, CatalogArt>,
    gridState: LazyGridState,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val rows = legacyFirst(catalog.rows)
    val statusLines = remember(rows) { gridLinesWithStatus(rows, PHONE_CATALOG_COLUMNS) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(PHONE_CATALOG_COLUMNS),
        modifier = modifier,
        state = gridState,
        contentPadding = PaddingValues(start = SIDE_GUTTER, end = SIDE_GUTTER, top = 8.dp, bottom = bottomInset + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
    ) {
        item(key = "search", span = FULL_WIDTH) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                label = { Text("Buscar plugins") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Only while the list is still the copy shipped in the APK. The notice waits for the refresh to
        // end (it may still succeed); meanwhile the action reads "Actualizando…" and does nothing.
        catalogRefreshLine(catalog)?.let { line ->
            item(key = "seed-notice", span = FULL_WIDTH) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        line.notice.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::reloadCatalog, enabled = line.actionEnabled) {
                        Text(line.actionLabel, color = if (line.actionEnabled) ArkivRed else ArkivTextSecondary)
                    }
                }
            }
        }
        // Nothing to show yet and a download pending: the heading that used to carry this spinner is the tab now.
        if (catalog.loading) {
            item(key = "loading", span = FULL_WIDTH) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
        }
        // One cell per plugin, the one of what the person already used first (see legacyFirst).
        itemsIndexed(rows, key = { _, row -> "card-${row.entry.id}" }) { index, row ->
            PluginCard(
                row = row,
                art = art[row.entry.repo],
                reserveStatusLine = statusLines.getOrElse(index) { false },
                enabled = !busy,
                onAction = { runCatalogAction(vm, row) },
            )
        }
        // Both lists are filtered by the same query: "no match" only when neither has a card to show.
        if (rows.isEmpty() && community.rows.isEmpty() && !catalog.loading) {
            item(key = "no-match", span = FULL_WIDTH) {
                Text("No hay plugins que coincidan.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
            }
        }
        communityItems(
            community, art, busy = busy, columns = PHONE_CATALOG_COLUMNS,
            onRefresh = vm::refreshCommunity, onAction = { runCatalogAction(vm, it) },
        )
    }
}

/**
 * Instalados: the installed plugins as cards, the same 2-column grid [RecommendedTab] draws (one
 * [InstalledPluginCard] per plugin), with none installed it says so and offers the way to the recommended
 * ones ([onBrowseRecommended]). [message] goes to the card it is about, if any (see [rowMessagePluginId]);
 * every card of the message's own grid line reserves the room for it so the line ends at one height
 * (see [installedGridLinesWithMessage]).
 */
@Composable
private fun InstalledTab(
    vm: PluginsViewModel,
    plugins: List<InstalledPlugin>,
    art: Map<String, CatalogArt>,
    busy: Boolean,
    message: String?,
    rowMessageId: String?,
    gridState: LazyGridState,
    bottomInset: Dp,
    onBrowseRecommended: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (plugins.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(horizontal = SIDE_GUTTER, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Todavía no tienes plugins.", style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
            TextButton(onClick = onBrowseRecommended) { Text("Ver recomendados", color = ArkivRed) }
        }
        return
    }
    val messageIndex = rowMessageId?.let { id -> plugins.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    val messageLines = remember(plugins, messageIndex) { installedGridLinesWithMessage(plugins.size, messageIndex, PHONE_CATALOG_COLUMNS) }
    // A live plugin's "Lista recortada: …" line, per card (null = none), following its current provider.
    val liveModule = rememberGraph().liveModule
    val notices = plugins.map { p ->
        androidx.compose.runtime.key(p.id) {
            val flow = remember(p.id) { liveModule.noticeFor(p.id) }
            flow.collectAsStateWithLifecycle(initialValue = null).value
        }
    }
    val noticeLines = installedGridLinesReserving(notices.map { it != null }, PHONE_CATALOG_COLUMNS)
    LazyVerticalGrid(
        columns = GridCells.Fixed(PHONE_CATALOG_COLUMNS),
        modifier = modifier,
        state = gridState,
        contentPadding = PaddingValues(start = SIDE_GUTTER, end = SIDE_GUTTER, top = 8.dp, bottom = bottomInset + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
    ) {
        itemsIndexed(plugins, key = { _, p -> "installed-${p.id}" }) { index, p ->
            InstalledPluginCard(
                plugin = p,
                art = artForInstalled(art, p.record.address),
                busy = busy,
                message = message.takeIf { rowMessageId == p.id },
                reserveMessageLines = messageLines.getOrElse(index) { false },
                vm = vm,
                liveNotice = notices.getOrNull(index),
                reserveNoticeLines = noticeLines.getOrElse(index) { false },
            )
        }
    }
}
