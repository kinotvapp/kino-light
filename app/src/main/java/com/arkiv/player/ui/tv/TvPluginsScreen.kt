package com.arkiv.player.ui.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.data.plugin.sync.PeerPluginOffer
import com.arkiv.player.ui.plugin.CatalogAction
import com.arkiv.player.ui.plugin.PEER_PLUGINS_TITLE
import com.arkiv.player.ui.plugin.peerOfferInstallEnabled
import com.arkiv.player.ui.plugin.peerOfferStatusText
import com.arkiv.player.ui.plugin.CatalogRow
import com.arkiv.player.ui.plugin.CatalogUiState
import com.arkiv.player.ui.plugin.CommunityUiState
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

/**
 * Installed plugins per line of the Instalados grid (Recomendados lays compact cards: [tvPickerColumns]). Four
 * fill the pane of a 960 dp wide TV (Fire TV at density 2) without leaving a column empty with a few plugins.
 */
internal const val TV_INSTALLED_COLUMNS = 4

/**
 * Room kept between a focused item and the edge of the list when the scroll brings it into view. The
 * focused card is scaled up (see [com.arkiv.player.ui.cardFocusScale]) and draws a 3 dp border: without
 * the margin a card scrolled to the edge would be cut by the list's bounds.
 */
internal val FOCUS_MARGIN = 12.dp

/** An item of the grid that takes the whole line: everything but the cards. */
private val FULL_WIDTH: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/**
 * What the Plugins screen shows on the TV, laid out for the D-pad: no title and no Back handling of its own
 * (the host adds them and the horizontal padding; the content takes its width and height from its parent).
 * From top to bottom: what an action answers (the progress bar and its message), ONE header row with the two
 * tabs ([TvTab]: **Recomendados** and **Instalados (n)**), on Recomendados the **Buscar plugins** button
 * ([TvPluginSearch], which opens the search field in its place), and at its end the **Agregar** button, and
 * the selected tab's body.
 *
 * - **Recomendados**: the notice while the list is only the copy shipped in the APK (with "Reintentar"), and
 *   the recommended plugins as compact cards ([TvPluginCard], as many per line as [tvPickerColumns] fits, the
 *   same as "Elige tus fuentes") of one lazy grid, then "De la comunidad".
 * - **Instalados**: the installed plugins as cards ([TvInstalledPluginCard]) in one lazy grid of
 *   [TV_INSTALLED_COLUMNS] columns; OK on a card opens its actions dialog
 *   ([TvInstalledActionsDialog]). With nothing installed, a line saying so and "Ver recomendados".
 * - **Agregar** opens [TvAddCustomPluginDialog] for the custom `usuario/repositorio`. Installing always goes
 *   through the consent sheet, and that sheet replaces the dialog while it is up (see [addModalVisible]). That
 *   it takes a Kino or a Nuvio plugin's repo is said inside the dialog, not on a line of its own here: the
 *   screen's height goes to the cards.
 *
 * The header row is never inside a scrolling list (a scroll would drag it away as focus went down and
 * getting back would be a fumble). D-pad: Left and Right move among the tabs and the button, OK on a tab
 * selects it. Down enters the selected tab's body: the first card, or the first installed card.
 * Up from the first installed card returns to the SELECTED tab.
 *
 * Focus is not placed on a card when it opens: the person is walking Ajustes' own tab row. [entryFocus] is
 * put on the selected tab chip of this content's header row, so the host can send Down from its own tab row
 * there, and Up from the header row (the search field included) leads to [upFocus] (the host's tab row). Up
 * from the first installed card leads to the selected tab chip, one level down.
 *
 * The selected tab and whether the person asked for the dialog survive recreation ([rememberSaveable]); the
 * search text and the typed address live in the view model, so they survive switching tabs and the consent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvPluginsContent(
    vm: PluginsViewModel,
    addRequested: Boolean,
    onAddRequestedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
) {
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val peerOffers by vm.peerOffers.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val community by vm.community.collectAsStateWithLifecycle()
    val art by vm.art.collectAsStateWithLifecycle()
    val rowMessageId = rowMessagePluginId(state, plugins)
    val rows = legacyFirst(catalog.rows)

    var tab by rememberSaveable { mutableStateOf(PluginsTab.RECOMMENDED) }
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

    // Hosted in Ajustes' pane, the content never places focus itself: the person is walking Ajustes' tab row
    // (see the host). [firstRowFocus] is where Down from the header row leads.
    val firstRowFocus = remember { FocusRequester() }
    val firstRowModifier = Modifier.focusRequester(firstRowFocus)

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

    // The Nuvio picker (when open) and the consent/Configurar/uninstall dialogs are the CALLER's job now
    // ([TvPluginsHost]): it can show the picker as its own full screen, never nested in this content's own
    // container (the earlier double-inset bug). This function only ever draws the tabs.
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
        val downTarget = when (tab) {
            // A requester on a card that is not composed throws: with no cards, Down takes its usual course.
            PluginsTab.RECOMMENDED -> firstRowFocus.takeIf { rows.isNotEmpty() }
            PluginsTab.INSTALLED -> installedEntryFocus
        }
        PluginsHeader(
            tab = tab,
            installedCount = plugins.size,
            busy = state.busy,
            tabFocus = tabFocus,
            addFocus = addFocus,
            entryFocus = entryFocus,
            upFocus = upFocus,
            downTarget = downTarget,
            search = if (tab == PluginsTab.RECOMMENDED) {
                { TvPluginSearch(query = state.query, onQueryChange = vm::onQueryChange, upFocus = upFocus, downTarget = downTarget) }
            } else {
                null
            },
            onSelect = { tab = it },
            onAdd = {
                // The dialog opens empty: what an earlier action said is not its news, and neither is an address
                // a confirmed install left behind when it failed. (Changing the address also drops the message.)
                vm.onAddressChange("")
                onAddRequestedChange(true)
            },
        )
        // The scaled card or action has to be fully visible when it takes focus, so the scroll keeps a margin around it.
        val density = LocalDensity.current
        val bringIntoView = remember(density) { KeepMarginBringIntoView(with(density) { FOCUS_MARGIN.toPx() }) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
            when (tab) {
                PluginsTab.RECOMMENDED -> RecommendedTab(
                    vm = vm,
                    catalog = catalog,
                    community = community,
                    rows = rows,
                    art = art,
                    firstRowModifier = firstRowModifier,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                PluginsTab.INSTALLED -> InstalledTab(
                    vm = vm,
                    plugins = plugins,
                    offers = peerOffers,
                    busy = state.busy,
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
                onAddRequestedChange(false)
                vm.onAddressChange(addressAfterDialogDismissed())
            },
        )
    }
}

/**
 * Hosts the Plugins view model for [TvSettingsScreen]'s Plugins tab (reached from Ajustes, and from the Home
 * rail's "Plugins", which opens Ajustes on that tab):
 * the full-screen Nuvio picker when [com.arkiv.player.ui.plugin.PluginsUiState.nuvioPicker] is open, drawn
 * as its OWN screen (never nested inside [chrome]'s own padded container -- the earlier double-inset bug:
 * the picker used to draw inside [TvPluginsContent]'s host, which already padded the "Plugins"/"Ajustes"
 * title, and then added its own padding on top); [chrome] otherwise, with `vm` and the hoisted
 * `addRequested` flag (see [TvPluginsContent]'s KDoc for why it can't just live inside that composable:
 * it has to survive being unmounted while the picker, not it, is on screen).
 *
 * The consent, Configurar and uninstall-confirmation dialogs are drawn here, OUTSIDE both branches: any of
 * the three can open from the picker (a fresh Nuvio install's required settings, an update's consent) or
 * from the ordinary tabs (Instalados), so neither branch alone is the right place for them.
 */
@Composable
internal fun TvPluginsHost(chrome: @Composable (vm: PluginsViewModel, addRequested: Boolean, onAddRequestedChange: (Boolean) -> Unit) -> Unit) {
    val vm = rememberPluginsViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var addRequested by rememberSaveable { mutableStateOf(false) }
    val picker = state.nuvioPicker
    if (picker != null) {
        val plugins by vm.plugins.collectAsStateWithLifecycle()
        // Replaces the Plugins screen entirely: the full-screen picker (Task: "see the scrapers properly"),
        // not a dialog stacked over it. It stays up through the consent sheet below (install or cancel), so
        // several scrapers of the same repo can be added in a row without retyping the address.
        TvNuvioScraperPickerScreen(
            picker = picker,
            installed = plugins,
            busy = state.busy,
            message = state.message,
            consentOpen = state.consent != null || state.configuring != null,
            onPick = vm::pickNuvioScraper,
            onBack = {
                // Leaves all the way to the Plugins screen, not back to the "Agregar" dialog that opened
                // the picker: without this, an "Agregar" that never reached a confirmed/cancelled consent
                // (addRequested still true) left addModalVisible true once nuvioPicker cleared, and Back
                // on the picker reopened the dialog with the old address instead of leaving.
                addRequested = false
                vm.onAddressChange(addressAfterDialogDismissed())
                vm.cancelNuvioPicker()
            },
        )
    } else {
        chrome(vm, addRequested) { addRequested = it }
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
    state.confirmUninstall?.let { PluginUninstallDialog(it, isTv = true, onConfirm = vm::confirmUninstall, onCancel = vm::cancelUninstall) }
    state.configuring?.let { PluginConfigDialog(it, isTv = true, vm = vm) }
}

/** The Plugins view model of [TvPluginsHost], scoped by a fixed key. */
@Composable
private fun rememberPluginsViewModel(): PluginsViewModel {
    val graph = rememberGraph()
    return viewModel(
        key = "plugins",
        factory = viewModelFactory {
            initializer {
                PluginsViewModel(
                    graph.pluginAdmin,
                    catalogProvider = graph.pluginCatalog,
                    artProvider = graph.catalogArt,
                    discovery = graph.pluginDiscovery,
                    nuvioPluginInstaller = graph.nuvioPluginInstaller,
                    peerOffers = graph.peerPluginOffers,
                )
            }
        },
    )
}

/**
 * The header row: the tabs on the left ([TvTab], the same piece and style as Ajustes' tab row) and "Agregar"
 * at the end. "Agregar" is ignored, not disabled, while [busy], so focus is never thrown out from under the
 * person. [downTarget] is where Down leads, or null to let the key take its usual course. [upFocus] is where
 * Up leads (the host's tab row above the header), or null when nothing lies above. [entryFocus] is the host's
 * way in: it is attached to the SELECTED tab chip, wherever the selection is (nobody uses it while the row
 * recomposes, unlike the per-chip [tabFocus]). [search] (the Recomendados search, [TvPluginSearch]) goes
 * between the tabs and "Agregar", or nothing on Instalados.
 *
 * The tabs are a [LazyRow], as in Ajustes: a plain `Row` would give each chip the row's whole width, since a
 * [TvTab] fills what it is given.
 */
@Composable
private fun PluginsHeader(
    tab: PluginsTab,
    installedCount: Int,
    busy: Boolean,
    tabFocus: Map<PluginsTab, FocusRequester>,
    addFocus: FocusRequester,
    entryFocus: FocusRequester?,
    upFocus: FocusRequester?,
    downTarget: FocusRequester?,
    search: (@Composable () -> Unit)?,
    onSelect: (PluginsTab) -> Unit,
    onAdd: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            modifier = Modifier.weight(1f),
            // Room for the zoom and the focus border on every side, the FIRST chip's left edge included: a
            // LazyRow clips to its own bounds, and with no horizontal padding the first chip's focus ring
            // bled past x=0 of the row (the screen's own edge, once this header sits in the full-screen
            // "Plugins" route with nothing left of it) and got clipped there. Measured on the KALLEY TV.
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
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
                            if (upFocus != null) up = upFocus
                        }
                        .dpadDownTo(downTarget),
                )
            }
        }
        if (search != null) {
            search()
            Spacer(Modifier.width(10.dp))
        }
        TvCompactAction(
            label = "Agregar",
            icon = Icons.Filled.Add,
            enabled = !busy,
            modifier = Modifier
                .focusRequester(addFocus)
                // Nothing lies to the right of the button, which is the last thing of the row.
                .focusProperties {
                    right = FocusRequester.Cancel
                    if (upFocus != null) up = upFocus
                }
                .dpadDownTo(downTarget),
            onClick = onAdd,
        )
    }
}

/**
 * Recomendados: the notice while the list is only the copy shipped in the APK, and the recommended plugins,
 * each a compact [TvPluginCard] in one cell of a grid of [tvPickerColumns] columns (the rule and the card of
 * "Elige tus fuentes"). Everything but the cards is a full-width item. "De la comunidad" follows the cards
 * ([tvCommunityItems]). The search lives in the header row ([TvPluginSearch]). The first card carries
 * [firstRowModifier] (Down from the header row).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecommendedTab(
    vm: PluginsViewModel,
    catalog: CatalogUiState,
    community: CommunityUiState,
    rows: List<CatalogRow>,
    art: Map<String, CatalogArt>,
    firstRowModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val gridFocus = rememberTvGridFocus(gridState)
    BoxWithConstraints(modifier) {
        val columns = tvPickerColumns(maxWidth.value)
        val statusLines = remember(rows, columns) { gridLinesWithStatus(rows, columns) }
        // Up/Down among the cards, "Actualizar" and the community cards go by line (see TvGridFocus): the geometric
        // search let Down from some columns skip "De la comunidad". Above the first card line the key takes its usual course.
        gridFocus.update(listOf(GridBlock.Cards(rows.map { "card-${it.entry.id}" })) + communityFocusBlocks(community), columns)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            // 8 dp on top: the room the focused first line's zoom ([TV_CARD_FOCUS_SCALE]) needs, and no more.
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(PICKER_CARD_GAP_DP.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            // One cell per plugin. The first card is where Down from the header row leads; the last card, and every
            // card of the last column, have nothing to their right (see cardHasNothingToTheRight). Up from the first
            // line reaches the header row by the geometry.
            itemsIndexed(rows, key = { _, row -> "card-${row.entry.id}" }) { index, row ->
                TvPluginCard(
                    row = row,
                    art = art[row.entry.repo],
                    modifier = gridFocus.stop("card-${row.entry.id}")
                        .then(if (index == 0) firstRowModifier else Modifier)
                        .then(if (cardHasNothingToTheRight(index, rows.lastIndex, columns)) Modifier.noFocusToTheRight() else Modifier),
                    reserveStatusLine = statusLines.getOrElse(index) { false },
                    compact = true,
                    onClick = { runCatalogAction(vm, row) },
                )
            }
            // Both lists are filtered by the same query: "no match" only when neither has a card to show.
            if (rows.isEmpty() && community.rows.isEmpty() && !catalog.loading) {
                item(key = "no-match", span = FULL_WIDTH) {
                    Text("No hay plugins que coincidan.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
                }
            }
            tvCommunityItems(
                community,
                art,
                columns,
                onRefresh = vm::refreshCommunity,
                onAction = { runCatalogAction(vm, it) },
                focus = gridFocus,
                compact = true,
            )
        }
    }
}

/**
 * Instalados: the installed plugins as cards, one [TvInstalledPluginCard] per plugin in a lazy grid of
 * [TV_INSTALLED_COLUMNS] columns. With none installed it says so and offers
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
    offers: List<PeerPluginOffer>,
    busy: Boolean,
    art: Map<String, CatalogArt>,
    message: String?,
    rowMessageId: String?,
    selectedTabFocus: FocusRequester,
    entryFocus: FocusRequester,
    onBrowseRecommended: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // "Plugins de tus otros aparatos" comes first, so Down from the header lands on its first "Instalar".
    val onInstallOffer: (PeerPluginOffer) -> Unit = { offer -> if (peerOfferInstallEnabled(offer.status, busy)) vm.installFromPeer(offer) }
    if (plugins.isEmpty()) {
        Column(modifier.padding(top = 16.dp).noFocusToTheRight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvPeerOffers(offers, entryFocus, selectedTabFocus, onInstallOffer)
            Text("Todavía no tienes plugins.", style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
            TvActionOption(
                label = "Ver recomendados",
                modifier = (if (offers.isEmpty()) Modifier.focusRequester(entryFocus).focusProperties { up = selectedTabFocus } else Modifier),
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
    val messageLines = remember(plugins, messageIndex) { installedGridLinesWithMessage(plugins.size, messageIndex, TV_INSTALLED_COLUMNS) }
    // A live plugin's "Lista recortada: …" line, per card (null = none), following its current
    // provider -- the same line as the phone's card. Plain text, never focusable: D-pad order is unchanged.
    val liveModule = rememberGraph().liveModule
    val notices = plugins.map { p ->
        androidx.compose.runtime.key(p.id) {
            val flow = remember(p.id) { liveModule.noticeFor(p.id) }
            flow.collectAsStateWithLifecycle(initialValue = null).value
        }
    }
    val noticeLines = installedGridLinesReserving(notices.map { it != null }, TV_INSTALLED_COLUMNS)

    LazyVerticalGrid(
        columns = GridCells.Fixed(TV_INSTALLED_COLUMNS),
        modifier = modifier,
        // 8 dp on top: a focused card grows by 4% of its height on each side ([TV_CARD_FOCUS_SCALE]), about 7 dp.
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (offers.isNotEmpty()) {
            item(key = "peer-offers", span = FULL_WIDTH) {
                Column(Modifier.noFocusToTheRight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvPeerOffers(offers, entryFocus, selectedTabFocus, onInstallOffer)
                }
            }
        }
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
                    .then(if (index == 0 && offers.isEmpty()) Modifier.focusRequester(entryFocus).focusProperties { up = selectedTabFocus } else Modifier)
                    .then(if (cardHasNothingToTheRight(index, plugins.lastIndex, TV_INSTALLED_COLUMNS)) Modifier.noFocusToTheRight() else Modifier),
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
 * "Plugins de tus otros aparatos" on the TV: one "Instalar <nombre>" per plugin the person has on another
 * device that did not install here by itself, with what happened under it. The first button carries
 * [entryFocus] (Down from the header row) and Up from it returns to the selected tab. OK while an action
 * runs, or while that plugin is installing by itself, does nothing (never disabled: focus stays put).
 */
@Composable
private fun TvPeerOffers(
    offers: List<PeerPluginOffer>,
    entryFocus: FocusRequester,
    selectedTabFocus: FocusRequester,
    onInstall: (PeerPluginOffer) -> Unit,
) {
    if (offers.isEmpty()) return
    Text(PEER_PLUGINS_TITLE, style = MaterialTheme.typography.titleMedium, color = Color.White)
    offers.forEachIndexed { index, offer ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TvActionOption(
                label = "Instalar ${offer.name}",
                modifier = if (index == 0) Modifier.focusRequester(entryFocus).focusProperties { up = selectedTabFocus } else Modifier,
                onClick = { onInstall(offer) },
            )
            Text(peerOfferStatusText(offer.status), style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        }
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
 * in [com.arkiv.player.ui.plugin.FocusWhenReady].
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

