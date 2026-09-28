package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.ui.plugin.AddPluginMode
import com.arkiv.player.ui.plugin.CatalogAction
import com.arkiv.player.ui.plugin.CatalogRow
import com.arkiv.player.ui.plugin.CatalogUiState
import com.arkiv.player.ui.plugin.CommunityUiState
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.plugin.PluginConfigDialog
import com.arkiv.player.ui.plugin.PluginConsentDialog
import com.arkiv.player.ui.plugin.PluginUninstallDialog
import com.arkiv.player.ui.plugin.PluginsTab
import com.arkiv.player.ui.plugin.PluginsViewModel
import com.arkiv.player.ui.plugin.addModalVisible
import com.arkiv.player.ui.plugin.addRequestAfterConsent
import com.arkiv.player.ui.plugin.addressAfterDialogDismissed
import com.arkiv.player.ui.plugin.artForInstalled
import com.arkiv.player.ui.plugin.catalogRefreshLine
import com.arkiv.player.ui.plugin.handleAddPluginBack
import com.arkiv.player.ui.plugin.initialPluginsTab
import com.arkiv.player.ui.plugin.installedGridLinesReserving
import com.arkiv.player.ui.plugin.installedGridLinesWithMessage
import com.arkiv.player.ui.plugin.installedTabLabel
import com.arkiv.player.ui.plugin.legacyFirst
import com.arkiv.player.ui.plugin.rowMessagePluginId
import com.arkiv.player.ui.plugin.runCatalogAction
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/** How long the text fields (and the header row) stay out of focus at most while the first recommended card takes it; see [TvPluginsContent]. */
private const val INITIAL_FOCUS_GRACE_MS = 1_500L

/** Recommended plugins per line of the grid. */
internal const val TV_CATALOG_COLUMNS = 3

/**
 * Room kept between a focused item and the edge of the list when the scroll brings it into view. The
 * focused card is scaled up (see [com.arkiv.player.ui.cardFocusScale]) and draws a 3 dp border: without
 * the margin a card scrolled to the edge would be cut by the list's bounds.
 */
internal val FOCUS_MARGIN = 12.dp

/** An item of the grid that takes the whole line: everything but the cards. */
private val FULL_WIDTH: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/**
 * The Plugins screen on the TV, full screen: its title over [TvPluginsContent], which is the screen itself.
 * Ajustes ▸ Plugins does NOT use this host (it shows [TvPluginsContent] inside its own pane); this one is the
 * first-launch picker's ([AddPluginMode.ONBOARDING], Phase 2, not wired yet), pre-wired here so that phase adds
 * a route and nothing else.
 *
 * Back closes it in [AddPluginMode.SETTINGS]; the onboarding picker has no way out, so it swallows Back.
 * The TV has no close button, so [mode] changes nothing else here.
 */
@Composable
fun TvPluginsScreen(mode: AddPluginMode, onClose: () -> Unit) {
    BackHandler { handleAddPluginBack(mode, onClose) }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {
        Text("Plugins", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        TvPluginsContent(mode, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * What the Plugins screen shows on the TV, laid out for the D-pad: no title and no Back handling of its own
 * (the host adds them and the horizontal padding; the content takes its width and height from its parent).
 * From top to bottom: what an action answers (the progress bar and its message), ONE header row with the two
 * tabs ([TvTab]: **Recomendados** and **Instalados (n)**) and, at its end, the **Agregar** button, and the
 * selected tab's body.
 *
 * - **Recomendados**: the search field, the notice while the list is only the copy shipped in the APK (with
 *   "Reintentar"), and the recommended plugins as cards ([TvPluginCard]) in [TV_CATALOG_COLUMNS] columns of one
 *   lazy grid.
 * - **Instalados**: the installed plugins as cards ([TvInstalledPluginCard]) in one lazy grid, the same
 *   [TV_CATALOG_COLUMNS] columns as Recomendados; OK on a card opens its actions dialog
 *   ([TvInstalledActionsDialog]). With nothing installed, a line saying so and "Ver recomendados".
 * - **Agregar** opens [TvAddCustomPluginDialog] for the custom `usuario/repositorio`. Installing always goes
 *   through the consent sheet, and that sheet replaces the dialog while it is up (see [addModalVisible]).
 *
 * The header row is never inside a scrolling list (a scroll would drag it away as focus went down and
 * getting back would be a fumble). D-pad: Left and Right move among the tabs and the button, OK on a tab
 * selects it. Down enters the selected tab's body: the first card, NOT the search field (a text field that
 * takes focus opens the keyboard by itself, and going down should not), or the first installed card.
 * Up from the search field and from the first installed card returns to the SELECTED tab.
 *
 * Initial focus is the first recommended card and no keyboard when the screen is the whole screen
 * ([requestInitialFocus], see the comment on [initialFocusPlaced] below). Hosted inside another screen's
 * chrome (Ajustes ▸ Plugins) it must NOT be requested: the person is walking that screen's own tab row, and
 * a card that took focus when the tab opened would steal it. The host then links the two levels itself:
 * [entryFocus] is put on the selected tab chip so the host can send Down from its own tab row there, and
 * Up from the header row leads to [upFocus] (the host's tab row) when there is one. Up from the search field
 * and from the first installed card still leads to the selected tab chip, one level down.
 *
 * The selected tab and whether the person asked for the dialog survive recreation ([rememberSaveable]); the
 * search text and the typed address live in the view model, so they survive switching tabs and the consent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvPluginsContent(
    mode: AddPluginMode,
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = true,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
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
    val rows = legacyFirst(catalog.rows)
    val statusLines = remember(rows) { gridLinesWithStatus(rows, TV_CATALOG_COLUMNS) }

    var tab by rememberSaveable { mutableStateOf(initialPluginsTab(mode)) }
    var addRequested by rememberSaveable { mutableStateOf(false) }
    val dialogVisible = addModalVisible(addRequested, state)

    // The places focus is sent to. Each tab chip has ITS OWN requester ([tabFocus], for good: one that moved
    // between chips as the selection changed could reach the stale chip while the row recomposed), and
    // [selectedTabFocus] is just the one of the tab that is selected now, where Up from a body leads. [addFocus] is
    // on the "Agregar" button and [installedEntryFocus] on the first card of the Instalados body (or on its "Ver
    // recomendados" when nothing is installed). [firstRowFocus] (below) is on the first recommended card.
    // [entryFocus], the host's way in, is put on the selected chip only (see [PluginsHeader]).
    val tabFocus = remember { PluginsTab.entries.associateWith { FocusRequester() } }
    val selectedTabFocus = tabFocus.getValue(tab)
    val addFocus = remember { FocusRequester() }
    val installedEntryFocus = remember { FocusRequester() }

    // With [requestInitialFocus], initial focus goes to the first recommended card, with no keyboard. Two things
    // make that fragile:
    //  1. On a TV (non-touch mode) Android gives the window's Compose view focus on the first frame, and Compose
    //     hands it to the first focusable node it finds. A focused text field opens the system keyboard by itself,
    //     covering the grid, and the tabs would show a focus ring for the moment before the card takes it. So the
    //     search field AND the header row (tabs and "Agregar") refuse focus (canFocus = false) until
    //     [initialFocusPlaced]; the default focus then lands on another control ("Reintentar" or the first card)
    //     and [FocusWhenReady] moves it to the first card a moment later.
    //  2. The rows may arrive after the screen opens, and a requester on a card that is not composed throws, so
    //     the request only runs while a row exists (and [FocusWhenReady] retries until the card is attached).
    // [initialFocusPlaced] turns true, for good, as soon as that first card has focus. If that never happens
    // (no rows, or the card could not take focus) it turns true anyway after [INITIAL_FOCUS_GRACE_MS], so the
    // fields and the header are never unreachable. Once true nothing requests focus again, so a catalog that
    // reloads or a search that filters never takes focus away from the person, and moving INTO the search field
    // by D-pad (Up from the first line of cards) works, keyboard included, as it always did.
    // Without [requestInitialFocus] there is nothing to place: the host already has focus (on its own tab row,
    // which comes before this content and takes the window's default focus), so the fields and the header are
    // focusable from the start.
    val firstRowFocus = remember { FocusRequester() }
    var initialFocusPlaced by remember { mutableStateOf(!requestInitialFocus) }
    if (!initialFocusPlaced && tab == PluginsTab.RECOMMENDED && rows.isNotEmpty()) FocusWhenReady(firstRowFocus)
    LaunchedEffect(Unit) {
        if (requestInitialFocus) {
            delay(INITIAL_FOCUS_GRACE_MS)
            initialFocusPlaced = true
        }
    }
    val firstRowModifier = Modifier
        .focusRequester(firstRowFocus)
        .onFocusChanged { if (it.hasFocus) initialFocusPlaced = true }

    // The dialog is a window of its own: when it goes away (Cancelar, Back, or the consent sheet taking over)
    // the person finds the "Agregar" button that opened it focused again. A consent that did not come from
    // the dialog never raised it, so it changes no focus.
    var dialogWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(dialogVisible) {
        val returning = focusReturnsToAdd(wasShown = dialogWasShown, shown = dialogVisible)
        dialogWasShown = dialogVisible
        if (returning) requestFocusWhenReady(addFocus)
    }

    // "Ver recomendados" switches tab from inside the Instalados body, which then leaves composition and takes
    // its focus with it: the Recomendados chip is where focus is put back.
    var focusRecommendedChip by remember { mutableStateOf(false) }
    LaunchedEffect(focusRecommendedChip) {
        if (focusRecommendedChip) {
            requestFocusWhenReady(tabFocus.getValue(PluginsTab.RECOMMENDED))
            focusRecommendedChip = false
        }
    }

    Column(modifier = modifier) {
        // Above the tabs, not inside a list: what an install or an add answers must be seen wherever the
        // list is scrolled to and on either tab. A message about one installed plugin shows on its own row, and
        // while the dialog is up its message is drawn inside it.
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = ArkivRed)
        if (rowMessageId == null && !dialogVisible) {
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            }
        }
        PluginsHeader(
            tab = tab,
            installedCount = plugins.size,
            busy = state.busy,
            focusable = initialFocusPlaced,
            tabFocus = tabFocus,
            addFocus = addFocus,
            entryFocus = entryFocus,
            upFocus = upFocus,
            downTarget = when (tab) {
                // A requester on a card that is not composed throws: with no cards, Down takes its usual course.
                PluginsTab.RECOMMENDED -> firstRowFocus.takeIf { rows.isNotEmpty() }
                PluginsTab.INSTALLED -> installedEntryFocus
            },
            onSelect = { tab = it },
            onAdd = {
                // The dialog opens empty: what an earlier action said is not its news, and neither is an address
                // a confirmed install left behind when it failed. (Changing the address also drops the message.)
                vm.onAddressChange("")
                addRequested = true
            },
        )
        // The scaled card or action has to be fully visible when it takes focus, so the scroll keeps a margin around it.
        val density = LocalDensity.current
        val bringIntoView = remember(density) { KeepMarginBringIntoView(with(density) { FOCUS_MARGIN.toPx() }) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
            when (tab) {
                PluginsTab.RECOMMENDED -> RecommendedTab(
                    vm = vm,
                    query = state.query,
                    catalog = catalog,
                    community = community,
                    rows = rows,
                    statusLines = statusLines,
                    art = art,
                    selectedTabFocus = selectedTabFocus,
                    firstRowModifier = firstRowModifier,
                    fieldsCanFocus = initialFocusPlaced,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                PluginsTab.INSTALLED -> InstalledTab(
                    vm = vm,
                    plugins = plugins,
                    art = art,
                    message = state.message,
                    rowMessageId = rowMessageId,
                    selectedTabFocus = selectedTabFocus,
                    entryFocus = installedEntryFocus,
                    onBrowseRecommended = {
                        tab = PluginsTab.RECOMMENDED
                        focusRecommendedChip = true
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }

    if (dialogVisible) {
        TvAddCustomPluginDialog(
            address = state.address,
            busy = state.busy,
            message = state.message.takeIf { rowMessageId == null },
            onAddressChange = vm::onAddressChange,
            onSubmit = vm::add,
            onDismiss = {
                // Cancelar and Back forget what was typed and what the last try said: a TV keyboard types at the
                // end of the field, so a dialog that reopened with the old text made a doubled repository.
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
    state.configuring?.let { PluginConfigDialog(it, isTv = true, vm = vm) }
}

/**
 * The header row: the tabs on the left ([TvTab], the same piece and style as Ajustes' tab row) and "Agregar"
 * at the end. [focusable] is false only while the initial focus is being placed (see [TvPluginsContent]).
 * "Agregar" is ignored, not disabled, while [busy], so focus is never thrown out from under the person.
 * [downTarget] is where Down leads, or null to let the key take its usual course. [upFocus] is where Up
 * leads (the host's tab row above the header), or null when nothing lies above. [entryFocus] is the host's
 * way in: it is attached to the SELECTED tab chip, wherever the selection is (nobody uses it while the row
 * recomposes, unlike the per-chip [tabFocus]).
 *
 * The tabs are a [LazyRow], as in Ajustes: a plain `Row` would give each chip the row's whole width, since a
 * [TvTab] fills what it is given.
 */
@Composable
private fun PluginsHeader(
    tab: PluginsTab,
    installedCount: Int,
    busy: Boolean,
    focusable: Boolean,
    tabFocus: Map<PluginsTab, FocusRequester>,
    addFocus: FocusRequester,
    entryFocus: FocusRequester?,
    upFocus: FocusRequester?,
    downTarget: FocusRequester?,
    onSelect: (PluginsTab) -> Unit,
    onAdd: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            modifier = Modifier.weight(1f),
            // Room for the zoom and the focus border: without this the focused tab gets clipped against its own row's bounds.
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(PluginsTab.entries.size) { i ->
                val t = PluginsTab.entries[i]
                TvTab(
                    label = when (t) {
                        PluginsTab.RECOMMENDED -> "Recomendados"
                        PluginsTab.INSTALLED -> installedTabLabel(installedCount)
                    },
                    selected = t == tab,
                    onClick = { onSelect(t) },
                    modifier = Modifier
                        .focusRequester(tabFocus.getValue(t))
                        .then(if (t == tab && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
                        .focusProperties {
                            canFocus = focusable
                            if (upFocus != null) up = upFocus
                        }
                        .dpadDownTo(downTarget),
                )
            }
        }
        TvCompactAction(
            label = "Agregar",
            icon = Icons.Filled.Add,
            enabled = !busy,
            modifier = Modifier
                .focusRequester(addFocus)
                // Nothing lies to the right of the button, which is the last thing of the row.
                .focusProperties {
                    canFocus = focusable
                    right = FocusRequester.Cancel
                    if (upFocus != null) up = upFocus
                }
                .dpadDownTo(downTarget),
            onClick = onAdd,
        )
    }
}

/**
 * Recomendados: the search field, the notice while the list is only the copy shipped in the APK, and the
 * recommended plugins, each a [TvPluginCard] in one cell of a [TV_CATALOG_COLUMNS]-column grid. Everything but
 * the cards is a full-width item. "De la comunidad" follows the cards ([tvCommunityItems]). Up from the search field leads to the selected tab ([selectedTabFocus]);
 * the first card carries [firstRowModifier] (the initial focus, and Down from the header row).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecommendedTab(
    vm: PluginsViewModel,
    query: String,
    catalog: CatalogUiState,
    community: CommunityUiState,
    rows: List<CatalogRow>,
    statusLines: List<Boolean>,
    art: Map<String, CatalogArt>,
    selectedTabFocus: FocusRequester,
    firstRowModifier: Modifier,
    fieldsCanFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(TV_CATALOG_COLUMNS),
        modifier = modifier,
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "search", span = FULL_WIDTH) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                label = { Text("Buscar plugins") },
                singleLine = true,
                // `Done` just leaves the field (the list filters as you type).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .noFocusToTheRight()
                    // Up leaves the field for the selected tab (see dpadLeavesTheField): the header row sits right above it.
                    .focusProperties { canFocus = fieldsCanFocus; up = selectedTabFocus }
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
        // Nothing to show yet and a download pending (the rows are there from the first frame otherwise).
        if (catalog.loading) {
            item(key = "loading", span = FULL_WIDTH) {
                CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        }
        // One cell per plugin. The first card takes the initial focus; the last card, and every card of the last
        // column, have nothing to their right (see cardHasNothingToTheRight). Up from the first line reaches the
        // search field (or the "Agregar" button above the last column) and Down from a header item the first
        // card: nothing else here overrides the D-pad, the geometry finds them.
        itemsIndexed(rows, key = { _, row -> "card-${row.entry.id}" }) { index, row ->
            TvPluginCard(
                row = row,
                art = art[row.entry.repo],
                modifier = Modifier
                    .then(if (index == 0) firstRowModifier else Modifier)
                    .then(if (cardHasNothingToTheRight(index, rows.lastIndex, TV_CATALOG_COLUMNS)) Modifier.noFocusToTheRight() else Modifier),
                reserveStatusLine = statusLines.getOrElse(index) { false },
                onClick = { runCatalogAction(vm, row) },
            )
        }
        // Both lists are filtered by the same query: "no match" only when neither has a card to show.
        if (rows.isEmpty() && community.rows.isEmpty() && !catalog.loading) {
            item(key = "no-match", span = FULL_WIDTH) {
                Text("No hay plugins que coincidan.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
            }
        }
        tvCommunityItems(community, art, TV_CATALOG_COLUMNS, onRefresh = vm::refreshCommunity, onAction = { runCatalogAction(vm, it) })
    }
}

/**
 * Instalados: the installed plugins as cards, one [TvInstalledPluginCard] per plugin in a lazy grid of
 * [TV_CATALOG_COLUMNS] columns (the same [RecommendedTab] draws). With none installed it says so and offers
 * "Ver recomendados" ([onBrowseRecommended]). [message] goes to the card it is about, if any (see
 * [rowMessagePluginId]); every card of the message's own grid line reserves the room for it
 * ([installedGridLinesWithMessage]), as [RecommendedTab] does for a card's status.
 *
 * The first card (or "Ver recomendados") carries [entryFocus], where Down from the header row leads, and Up
 * from it leads back to the selected tab ([selectedTabFocus]); nothing lies to the right of the last card of a
 * line or of the very last card ([cardHasNothingToTheRight]), the same rule [RecommendedTab] follows, so
 * Right never reaches the "Agregar" button above the last column.
 *
 * OK on a card opens [TvInstalledActionsDialog] with its management actions; Back, or "Cerrar" inside it,
 * closes the dialog and sends focus back to that same card ([cardFocus]).
 */
@Composable
private fun InstalledTab(
    vm: PluginsViewModel,
    plugins: List<InstalledPlugin>,
    art: Map<String, CatalogArt>,
    message: String?,
    rowMessageId: String?,
    selectedTabFocus: FocusRequester,
    entryFocus: FocusRequester,
    onBrowseRecommended: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (plugins.isEmpty()) {
        Column(modifier.padding(top = 16.dp).noFocusToTheRight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Todavía no tienes plugins.", style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
            TvActionOption(
                label = "Ver recomendados",
                modifier = Modifier.focusRequester(entryFocus).focusProperties { up = selectedTabFocus },
                onClick = onBrowseRecommended,
            )
        }
        return
    }

    // One requester per plugin, so the dialog's caller (whichever card was OK'd) gets focus back precisely;
    // rebuilt only when the installed list itself changes (an uninstall, an install), never on every
    // recomposition, or a request already in flight would chase a requester that just got replaced.
    val cardFocus = remember(plugins.map { it.id }) { plugins.associate { it.id to FocusRequester() } }
    var actionsPluginId by remember { mutableStateOf<String?>(null) }
    var returnFocusTo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(returnFocusTo) {
        returnFocusTo?.let { id ->
            // An uninstall from inside the dialog can remove the very card focus is meant to return to
            // (found on the KALLEY TV: the key then fell through to Ajustes' first chip). [entryFocus] is
            // the fallback -- it already carries whichever the first remaining card is, or "Ver
            // recomendados" once none are left (see the empty branch above and its own `itemsIndexed`).
            val target = installedFocusReturnTarget(id, plugins.map { it.id })
            requestFocusWhenReady(target?.let { cardFocus[it] } ?: entryFocus)
            returnFocusTo = null
        }
    }

    val messageIndex = rowMessageId?.let { id -> plugins.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    val messageLines = remember(plugins, messageIndex) { installedGridLinesWithMessage(plugins.size, messageIndex, TV_CATALOG_COLUMNS) }
    // A live plugin's "Lista recortada: …" line, per card (null = none), following its current
    // provider -- the same line as the phone's card. Plain text, never focusable: D-pad order is unchanged.
    val liveModule = rememberGraph().liveModule
    val notices = plugins.map { p ->
        androidx.compose.runtime.key(p.id) {
            val flow = remember(p.id) { liveModule.noticeFor(p.id) }
            flow.collectAsStateWithLifecycle(initialValue = null).value
        }
    }
    val noticeLines = installedGridLinesReserving(notices.map { it != null }, TV_CATALOG_COLUMNS)

    LazyVerticalGrid(
        columns = GridCells.Fixed(TV_CATALOG_COLUMNS),
        modifier = modifier,
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(plugins, key = { _, p -> "installed-${p.id}" }) { index, p ->
            TvInstalledPluginCard(
                plugin = p,
                art = artForInstalled(art, p.record.address),
                message = message.takeIf { rowMessageId == p.id },
                reserveMessageLines = messageLines.getOrElse(index) { false },
                liveNotice = notices.getOrNull(index),
                reserveNoticeLines = noticeLines.getOrElse(index) { false },
                modifier = Modifier
                    .focusRequester(cardFocus.getValue(p.id))
                    .then(if (index == 0) Modifier.focusRequester(entryFocus).focusProperties { up = selectedTabFocus } else Modifier)
                    .then(if (cardHasNothingToTheRight(index, plugins.lastIndex, TV_CATALOG_COLUMNS)) Modifier.noFocusToTheRight() else Modifier),
                onClick = { actionsPluginId = p.id },
            )
        }
    }

    val dialogPlugin = plugins.firstOrNull { it.id == actionsPluginId }
    if (dialogPlugin != null) {
        TvInstalledActionsDialog(
            plugin = dialogPlugin,
            art = artForInstalled(art, dialogPlugin.record.address),
            vm = vm,
            onDismiss = {
                returnFocusTo = actionsPluginId
                actionsPluginId = null
            },
        )
    }
}

/**
 * The text the notice line of the seed block lays out, or null when the block is not shown. It is the
 * notice itself once the refresh has failed, and while the refresh is still running (no notice yet) the
 * very text that will replace it, so the line takes the same room, wraps the same way at any font size and
 * pushes nothing when the notice appears. The screen draws it transparent until then.
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
 * Whether nothing may take focus to the right of the card at [index] of [lastIndex]+1 cards in [columns]
 * columns: the very last card (a partly filled line would otherwise jump to a card of the line above) and
 * every card of the last column (the "Agregar" button of the header row lies right above that column, up and
 * to the side, and Compose would take a Right key there).
 */
internal fun cardHasNothingToTheRight(index: Int, lastIndex: Int, columns: Int): Boolean =
    index == lastIndex || index % columns == columns - 1

/**
 * Whether focus goes back to the "Agregar" button: when the dialog it opens has just gone away
 * ([wasShown] and no longer [shown]), and never otherwise.
 */
internal fun focusReturnsToAdd(wasShown: Boolean, shown: Boolean): Boolean = wasShown && !shown

/**
 * Which plugin's card focus should return to once the actions dialog for [returnId] closes, among
 * [remainingIds] (the installed list right after -- an uninstall from inside that dialog may have removed
 * [returnId] itself): [returnId] when it is still installed, the first of [remainingIds] otherwise, or null
 * with nothing left installed at all. [InstalledTab] falls back to [entryFocus] on a null answer, which by
 * then already carries the first remaining card or, with none left, "Ver recomendados".
 */
internal fun installedFocusReturnTarget(returnId: String, remainingIds: List<String>): String? =
    if (returnId in remainingIds) returnId else remainingIds.firstOrNull()

/**
 * Puts focus on [requester] as soon as it can take it. The node may not exist yet (a tab that has just been
 * selected, a window that has just gone away) and `requestFocus()` throws until it does, hence the retry, as
 * in [FocusWhenReady].
 */
internal suspend fun requestFocusWhenReady(requester: FocusRequester) {
    repeat(20) {
        if (runCatching { requester.requestFocus() }.isSuccess) return
        delay(50)
    }
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

/** D-pad Up and Down always leave a text field (see [fieldExitDirection]), by the field's own `focusProperties` if it has any. */
internal fun Modifier.dpadLeavesTheField(focusManager: FocusManager): Modifier = onPreviewKeyEvent { e ->
    val direction = if (e.type == KeyEventType.KeyDown) fieldExitDirection(e.key) else null
    if (direction != null) {
        focusManager.moveFocus(direction)
        true
    } else {
        false
    }
}

/**
 * D-pad Down puts focus on [target] when it can take it, and does nothing special otherwise (the key takes its
 * usual course). Not a `focusProperties { down = ... }`: [target] may be on an item of a lazy list that is
 * not composed, and a requester that is not attached throws while the focus search follows it, where here the
 * failure is caught and the usual search runs. A null [target] leaves the key alone.
 */
internal fun Modifier.dpadDownTo(target: FocusRequester?): Modifier =
    if (target == null) {
        this
    } else {
        onPreviewKeyEvent { e ->
            e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && runCatching { target.requestFocus() }.isSuccess
        }
    }

/**
 * Compose's focus search takes a full-width item's Right key to the nearest item that lies further right,
 * even on another line: from "Reintentar" (60% of the width) it would jump to a card in the last column, and
 * from the last card of a partly filled line to a card of the line above. Nothing is meant to be to the
 * right of these, so the key is consumed and focus stays where it is.
 */
internal fun Modifier.noFocusToTheRight(): Modifier = focusProperties { right = FocusRequester.Cancel }

/** The scroll of the lists: the minimal one that shows an item whole, with [marginPx] of room around it. */
@OptIn(ExperimentalFoundationApi::class)
internal class KeepMarginBringIntoView(private val marginPx: Float) : BringIntoViewSpec {
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
