package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.ui.plugin.AddPluginMode
import com.arkiv.player.ui.plugin.CatalogAction
import com.arkiv.player.ui.plugin.CatalogRow
import com.arkiv.player.ui.plugin.CatalogUiState
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.plugin.PluginConsentDialog
import com.arkiv.player.ui.plugin.PluginConfigDialog
import com.arkiv.player.ui.plugin.PluginUninstallDialog
import com.arkiv.player.ui.plugin.PluginsViewModel
import com.arkiv.player.ui.plugin.artForInstalled
import com.arkiv.player.ui.plugin.catalogActionOf
import com.arkiv.player.ui.plugin.catalogRefreshLine
import com.arkiv.player.ui.plugin.handleAddPluginBack
import com.arkiv.player.ui.plugin.legacyFirst
import com.arkiv.player.ui.plugin.rowMessagePluginId
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/** How long the text fields stay out of focus at most while the first recommended row takes it; see [TvAddPluginScreen]. */
private const val INITIAL_FOCUS_GRACE_MS = 1_500L

/** Recommended plugins per line of the grid. */
private const val CATALOG_COLUMNS = 3

/**
 * Room kept between a focused item and the edge of the grid when the scroll brings it into view. The
 * focused card is scaled up (see [com.arkiv.player.ui.cardFocusScale]) and draws a 3 dp border: without
 * the margin a card scrolled to the edge would be cut by the grid's bounds.
 */
private val FOCUS_MARGIN = 12.dp

/** An item of the grid that takes the whole line: everything but the cards. */
private val FULL_WIDTH: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/**
 * The "Agregar plugin" window on the TV: the phone's sections ([com.arkiv.player.ui.plugin.AddPluginScreen])
 * laid out for the D-pad. One lazy grid scrolls with the remote: the recommended plugins are cards
 * ([TvPluginCard]) in [CATALOG_COLUMNS] columns, and everything else (search, custom address, installed
 * plugins) is a full-width item.
 *
 * Back closes it in [AddPluginMode.SETTINGS]; the onboarding picker has no way out, so it swallows
 * Back. The TV has no close button, so [mode] changes nothing else.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvAddPluginScreen(mode: AddPluginMode, onClose: () -> Unit) {
    BackHandler { handleAddPluginBack(mode, onClose) }

    val graph = rememberGraph()
    // Own key: this window can be hosted next to the Plugins tab's view model on the same owner.
    val vm: PluginsViewModel = viewModel(
        key = "add-plugin",
        factory = viewModelFactory { initializer { PluginsViewModel(graph.pluginAdmin, catalogProvider = graph.pluginCatalog, artProvider = graph.catalogArt) } },
    )
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val art by vm.art.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val rowMessageId = rowMessagePluginId(state, plugins)
    val rows = legacyFirst(catalog.rows)
    val statusLines = remember(rows) { gridLinesWithStatus(rows, CATALOG_COLUMNS) }

    // Initial focus goes to the first recommended card, with no keyboard. Two things make that fragile:
    //  1. On a TV (non-touch mode) Android gives the window's Compose view focus on the first frame, and Compose
    //     hands it to the first focusable node it finds -- the search field, which sits at the top of the grid.
    //     A focused text field opens the system keyboard by itself, covering the grid. So both text fields
    //     refuse focus (canFocus = false) until [initialFocusPlaced]; the default focus then lands on another
    //     control and [FocusWhenReady] moves it to the first card a moment later.
    //  2. The rows may arrive after the window opens, and a requester on a card that is not composed throws, so
    //     the request only runs while a row exists (and [FocusWhenReady] retries until the card is attached).
    // [initialFocusPlaced] turns true, for good, as soon as that first card has focus. If that never happens
    // (no rows, or the card could not take focus) it turns true anyway after [INITIAL_FOCUS_GRACE_MS], so the
    // fields are never unreachable. Once true nothing requests focus again, so a catalog that reloads or a
    // search that filters never takes focus away from the person, and moving INTO the search field by D-pad
    // (Up from the first line of cards) works, keyboard included, as it always did.
    val firstRowFocus = remember { FocusRequester() }
    var initialFocusPlaced by remember { mutableStateOf(false) }
    if (!initialFocusPlaced && rows.isNotEmpty()) FocusWhenReady(firstRowFocus)
    LaunchedEffect(Unit) {
        delay(INITIAL_FOCUS_GRACE_MS)
        initialFocusPlaced = true
    }
    val firstRowModifier = Modifier
        .focusRequester(firstRowFocus)
        .onFocusChanged { if (it.hasFocus) initialFocusPlaced = true }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {
        Text("Agregar plugin", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        // Above the list, not inside it: what an install or an add answers must be seen wherever the
        // list is scrolled to. A message about one installed plugin shows on its own row.
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = ArkivRed)
        if (rowMessageId == null) {
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            }
        }
        // The scaled card has to be fully visible when it takes focus, so the scroll keeps a margin around it.
        val density = LocalDensity.current
        val bringIntoView = remember(density) { KeepMarginBringIntoView(with(density) { FOCUS_MARGIN.toPx() }) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(CATALOG_COLUMNS),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "search", span = FULL_WIDTH) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::onQueryChange,
                        label = { Text("Buscar plugins") },
                        singleLine = true,
                        // `Done` just leaves the field (the list filters as you type).
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .noFocusToTheRight()
                            .focusProperties { canFocus = initialFocusPlaced }
                            .dpadLeavesTheField(focusManager),
                    )
                }
                // Only while the list is still the copy shipped in the APK. The notice waits for the refresh to end
                // (it may still succeed); meanwhile the action reads "Actualizando…" and does nothing, but stays
                // focusable so focus is not thrown out from under the person when the label changes.
                // The notice's line is laid out from the first frame, invisible until there is a notice to show
                // (see [refreshNoticeSlot]): the initial focus scrolls the grid against this block, and a line
                // that appeared afterwards pushed the cards down and cut the focused first card at the bottom.
                catalogRefreshLine(catalog)?.let { line ->
                    item(key = "seed-notice", span = FULL_WIDTH) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.noFocusToTheRight()) {
                            refreshNoticeSlot(catalog)?.let { slot ->
                                val shown = line.notice != null
                                Text(
                                    slot,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (shown) ArkivTextSecondary else Color.Transparent,
                                    // The placeholder is not read out either, and it never takes focus (a Text does not).
                                    modifier = if (shown) Modifier else Modifier.clearAndSetSemantics { },
                                )
                            }
                            TvActionOption(label = line.actionLabel) { if (line.actionEnabled) vm.reloadCatalog() }
                        }
                    }
                }
                item(key = "recommended-title", span = FULL_WIDTH) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Recomendados", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        if (catalog.loading) CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    }
                }
                // One cell per plugin. The first card takes the initial focus; the last has nothing to its right
                // (see noFocusToTheRight). Up from the first line reaches the search field and Down from the last
                // one the custom field: nothing here overrides the D-pad, the geometry finds them.
                itemsIndexed(rows, key = { _, row -> "card-${row.entry.id}" }) { index, row ->
                    TvPluginCard(
                        row = row,
                        art = art[row.entry.repo],
                        modifier = Modifier
                            .then(if (index == 0) firstRowModifier else Modifier)
                            .then(if (index == rows.lastIndex) Modifier.noFocusToTheRight() else Modifier),
                        reserveStatusLine = statusLines.getOrElse(index) { false },
                        onClick = { runCatalogAction(vm, row) },
                    )
                }
                if (rows.isEmpty() && !catalog.loading) {
                    item(key = "no-match", span = FULL_WIDTH) {
                        Text("No hay plugins que coincidan.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
                    }
                }

                item(key = "custom", span = FULL_WIDTH) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp).noFocusToTheRight()) {
                        Text("Agregar uno custom", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(
                            "Escribe usuario/repositorio de GitHub. Antes de instalar vas a ver con qué sitios se conecta.",
                            style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary,
                        )
                        OutlinedTextField(
                            value = state.address,
                            onValueChange = vm::onAddressChange,
                            label = { Text("usuario/repositorio") },
                            singleLine = true,
                            // Same as the adult-lock field in TvSettingsApp: `Done` applies, and D-pad Down always
                            // leaves the field, since a closed IME otherwise traps focus in it.
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { vm.add(); focusManager.moveFocus(FocusDirection.Down) }),
                            modifier = Modifier.fillMaxWidth(0.6f).focusProperties { canFocus = initialFocusPlaced }.dpadLeavesTheField(focusManager),
                        )
                        TvActionOption(label = if (state.busy) "Revisando…" else "Agregar") { vm.add() }
                    }
                }

                item(key = "installed-title", span = FULL_WIDTH) {
                    Text("Instalados", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
                if (plugins.isEmpty()) {
                    item(key = "none-installed", span = FULL_WIDTH) {
                        Text("Todavía no tienes plugins.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
                    }
                }
                items(plugins, key = { "installed-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { p ->
                    // One item per plugin: a lazy item stacks several roots on top of each other.
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.noFocusToTheRight()) {
                        TvInstalledPluginRows(p, message = state.message.takeIf { rowMessageId == p.id }, vm = vm, art = artForInstalled(art, p.record.address))
                    }
                }
            }
        }
    }

    state.consent?.let { PluginConsentDialog(it, onInstall = vm::confirmInstall, onCancel = vm::cancelConsent) }
    state.confirmUninstall?.let { PluginUninstallDialog(it, onConfirm = vm::confirmUninstall, onCancel = vm::cancelUninstall) }
    state.configuring?.let { PluginConfigDialog(it, isTv = true, vm = vm) }
}

/**
 * What OK does on a recommended plugin's card: the action [catalogActionOf] says fits its state. An
 * installed plugin that needs nothing does nothing here (its card stays focusable, so the grid is still
 * walked one card at a time).
 */
private fun runCatalogAction(vm: PluginsViewModel, row: CatalogRow) {
    val installed = row.installed
    when (catalogActionOf(row)) {
        CatalogAction.INSTALL -> vm.installFromCatalog(row.entry)
        CatalogAction.CONFIGURE -> installed?.let { vm.openSettings(it.id) }
        CatalogAction.ENABLE -> installed?.let { vm.setEnabled(it.id, true) }
        CatalogAction.INSTALLED -> Unit
    }
}

/**
 * The text the notice line of the seed block lays out, or null when the block is not shown. It is the
 * notice itself once the refresh has failed, and while the refresh is still running (no notice yet) the
 * very text that will replace it, so the line takes the same room, wraps the same way at any font size and
 * pushes nothing when the notice appears. The window draws it transparent until then.
 */
internal fun refreshNoticeSlot(catalog: CatalogUiState): String? {
    val line = catalogRefreshLine(catalog) ?: return null
    return line.notice ?: catalogRefreshLine(catalog.copy(refreshing = false))?.notice
}

/** What the action on a recommended row says, with the plugin's name. */
internal fun catalogRowLabel(action: CatalogAction, name: String): String = when (action) {
    CatalogAction.INSTALL -> "Instalar $name"
    CatalogAction.CONFIGURE -> "Configurar $name"
    CatalogAction.ENABLE -> "Activar $name"
    CatalogAction.INSTALLED -> "$name: instalado"
}

/**
 * The direction a D-pad key takes focus out of a text field, or null when the field keeps the key.
 * A single-line field has no use for Up/Down (they only moved the caret), and a closed IME otherwise
 * traps focus in it: with rows above and below the field, both must leave.
 */
internal fun fieldExitDirection(key: Key): FocusDirection? = when (key) {
    Key.DirectionUp -> FocusDirection.Up
    Key.DirectionDown -> FocusDirection.Down
    else -> null
}

/** D-pad Up and Down always leave a text field (see [fieldExitDirection]). */
private fun Modifier.dpadLeavesTheField(focusManager: FocusManager): Modifier = onPreviewKeyEvent { e ->
    val direction = if (e.type == KeyEventType.KeyDown) fieldExitDirection(e.key) else null
    if (direction != null) {
        focusManager.moveFocus(direction)
        true
    } else {
        false
    }
}

/**
 * Compose's focus search takes a full-width item's Right key to the nearest item that lies further right,
 * even on another line: from "Agregar" (60% of the width) it would jump to a card in the last column, and
 * from the last card of a partly filled line to a card of the line above. Nothing is meant to be to the
 * right of these, so the key is consumed and focus stays where it is.
 */
private fun Modifier.noFocusToTheRight(): Modifier = focusProperties { right = FocusRequester.Cancel }

/** The scroll of the grid: the minimal one that shows an item whole, with [marginPx] of room around it. */
@OptIn(ExperimentalFoundationApi::class)
private class KeepMarginBringIntoView(private val marginPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        scrollDistanceWithMargin(offset, size, containerSize, marginPx)
}

/**
 * How far to scroll to show the item at [offset] (its leading edge, in the container's coordinates) of
 * [size], with [margin] free on both sides: the minimal scroll ([MinimalScrollBringIntoView]) for the item
 * grown by the margin. Negative scrolls back, positive forward, zero when it is already there.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun scrollDistanceWithMargin(offset: Float, size: Float, containerSize: Float, margin: Float): Float =
    MinimalScrollBringIntoView.calculateScrollDistance(offset - margin, size + 2 * margin, containerSize)
