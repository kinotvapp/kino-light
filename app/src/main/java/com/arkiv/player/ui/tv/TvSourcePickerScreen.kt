package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.ui.player.WAIT_BETWEEN_FOCUS_ATTEMPTS_MS
import com.arkiv.player.ui.player.retryFocus
import com.arkiv.player.ui.plugin.CommunityUiState
import com.arkiv.player.ui.plugin.PluginConfigDialog
import com.arkiv.player.ui.plugin.PICKER_INSTALLED_TITLE
import com.arkiv.player.ui.plugin.PluginConsentDialog
import com.arkiv.player.ui.plugin.RECOMMENDED_TITLE
import com.arkiv.player.ui.plugin.SOURCE_PICKER_DONE
import com.arkiv.player.ui.plugin.SOURCE_PICKER_LINE
import com.arkiv.player.ui.plugin.SOURCE_PICKER_TITLE
import com.arkiv.player.ui.plugin.installedCardArt
import com.arkiv.player.ui.plugin.legacyFirst
import com.arkiv.player.ui.plugin.pickerInstalledRows
import com.arkiv.player.ui.plugin.pickerCanFinish
import com.arkiv.player.ui.plugin.pickerNeedsRefocus
import com.arkiv.player.ui.plugin.runCatalogAction
import com.arkiv.player.ui.plugin.sourcePickerViewModel
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How long the first card has to show up for the initial focus before "Listo" takes it instead. */
private const val PICKER_FOCUS_GRACE_MS = 1_500L

/** Attempts per focus target, [WAIT_BETWEEN_FOCUS_ATTEMPTS_MS] apart: about a second each. */
private const val PICKER_FOCUS_ATTEMPTS = 30

/** Grid item keys of the picker's headers and cards (also the stops of its D-pad routing, see [TvGridFocus]). */
internal const val PICKER_INSTALLED_HEADER_KEY = "installed-header"
internal const val PICKER_RECOMMENDED_HEADER_KEY = "recommended-header"
internal fun pickerInstalledKey(id: String) = "installed-$id"
internal fun pickerRecommendedKey(id: String) = "card-$id"

/**
 * The D-pad order of the picker's grid ([GridBlock]): "Tus plugins" ([installedIds]), Recomendados
 * ([recommendedIds]), then "De la comunidad" ("Actualizar", then its cards). Down from the last line
 * goes to "Listo".
 */
internal fun pickerFocusBlocks(installedIds: List<String>, recommendedIds: List<String>, community: CommunityUiState): List<GridBlock> =
    listOf(
        GridBlock.Cards(installedIds.map(::pickerInstalledKey), headerKey = PICKER_INSTALLED_HEADER_KEY),
        GridBlock.Cards(recommendedIds.map(::pickerRecommendedKey), headerKey = PICKER_RECOMMENDED_HEADER_KEY),
    ) + communityFocusBlocks(community)

/**
 * "Elige tus fuentes" on the TV. Laid out like [com.arkiv.player.ui.plugin.SourcePickerScreen] ("Tus plugins" for
 * the person's switched-off or damaged ones first), for the D-pad:
 * - Initial focus is the first recommended card (its action is "Instalar"); if there is no card within
 *   [PICKER_FOCUS_GRACE_MS], or it will not take focus, "Listo" does. "Listo" and not the community
 *   "Actualizar": it sits outside the lazy grid, so it is always composed and on screen whatever the
 *   community list is doing (loading, empty offline, scrolled away), and it stays focusable while disabled.
 * - Success is each target's OWN focus state, never `requestFocus()`'s return (it reports nothing; see
 *   [retryFocus]).
 * - The cards stay focusable in every state (an installed one reads "Instalado"), and "Listo" is a
 *   [TvCompactAction] that stays focusable while disabled, so a state change never throws focus out.
 * - If the rows change under the focused card, or a dialog closes, and nothing here holds focus, it goes
 *   back to the first card (or "Listo") ([pickerNeedsRefocus]).
 * - Up/Down are routed explicitly ([TvGridFocus], [pickerFocusBlocks]): "Tus plugins", Recomendados,
 *   "Actualizar", the community cards, then "Listo"; Up from "Listo" returns to the card Down left. The
 *   cards are compact ([tvPickerColumns]: 4 on a 1280 dp TV) so a new person sees "De la comunidad" on
 *   the first screen.
 * - The picker is mandatory: "Listo" ([pickerCanFinish]) calls [onFinish]; there is no skip. Back calls
 *   [onBack] ([com.arkiv.player.ui.plugin.onSourcePickerBack]: leaves the app when opened at start).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvSourcePickerScreen(onFinish: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val vm = sourcePickerViewModel()
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val community by vm.community.collectAsStateWithLifecycle()
    val art by vm.art.collectAsStateWithLifecycle()
    val rows = legacyFirst(catalog.rows)
    val installedRows = pickerInstalledRows(plugins, rows + community.rows)

    val firstCardFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    var firstCardFocused by remember { mutableStateOf(false) }
    var doneFocused by remember { mutableStateOf(false) }
    var placed by remember { mutableStateOf(false) }
    var screenHasFocus by remember { mutableStateOf(false) }
    val dialogOpen = state.consent != null || state.configuring != null
    val hasCards by rememberUpdatedState(rows.isNotEmpty())

    // The first card when there is one and it takes focus, "Listo" otherwise.
    suspend fun landFocus() {
        if (hasCards && retryFocus(
                attempts = PICKER_FOCUS_ATTEMPTS,
                isAlreadyFocused = { firstCardFocused },
                wait = { delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS) },
                request = { firstCardFocus.requestFocus() },
            )
        ) {
            return
        }
        retryFocus(
            attempts = PICKER_FOCUS_ATTEMPTS,
            isAlreadyFocused = { doneFocused },
            wait = { delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS) },
            request = { doneFocus.requestFocus() },
        )
    }

    LaunchedEffect(Unit) {
        withTimeoutOrNull(PICKER_FOCUS_GRACE_MS) { snapshotFlow { hasCards }.first { it } }
        landFocus()
        placed = true
    }
    val cardKeys = installedRows.map { pickerInstalledKey(it.entry.id) } + rows.map { it.entry.id } + community.rows.map { it.entry.repo }
    LaunchedEffect(cardKeys, plugins.size, dialogOpen) {
        // A frame for a removed node (or a closed dialog) to clear focus before asking who holds it.
        delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS)
        if (pickerNeedsRefocus(placed, screenHasFocus, dialogOpen)) landFocus()
    }

    val gridState = rememberLazyGridState()
    val gridFocus = rememberTvGridFocus(gridState)
    val focusDone: () -> Unit = { runCatching { doneFocus.requestFocus() } }
    val density = LocalDensity.current
    val bringIntoView = remember(density) { KeepMarginBringIntoView(with(density) { FOCUS_MARGIN.toPx() }) }
    Column(
        Modifier.fillMaxSize().background(ArkivBlack).padding(horizontal = 64.dp, vertical = 24.dp)
            .onFocusChanged { screenHasFocus = it.hasFocus },
    ) {
        Text(SOURCE_PICKER_TITLE, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(SOURCE_PICKER_LINE, style = MaterialTheme.typography.bodyLarge, color = ArkivTextSecondary, modifier = Modifier.padding(top = 8.dp))
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = ArkivRed)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.padding(top = 8.dp)) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columns = tvPickerColumns(maxWidth.value)
                val statusLines = remember(rows, columns) { gridLinesWithStatus(rows, columns) }
                val installedLines = remember(installedRows, columns) { gridLinesWithStatus(installedRows, columns) }
                gridFocus.update(pickerFocusBlocks(installedRows.map { it.entry.id }, rows.map { it.entry.id }, community), columns)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(PICKER_CARD_GAP_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (installedRows.isNotEmpty()) {
                        item(key = PICKER_INSTALLED_HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                            Text(PICKER_INSTALLED_TITLE, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                        itemsIndexed(installedRows, key = { _, row -> pickerInstalledKey(row.entry.id) }) { index, row ->
                            TvPluginCard(
                                row = row,
                                art = installedCardArt(row.installed),
                                modifier = gridFocus.stop(pickerInstalledKey(row.entry.id), leaveDown = focusDone)
                                    .then(if (cardHasNothingToTheRight(index, installedRows.lastIndex, columns)) Modifier.noFocusToTheRight() else Modifier),
                                reserveStatusLine = installedLines.getOrElse(index) { false },
                                compact = true,
                                onClick = { runCatalogAction(vm, row) },
                            )
                        }
                    }
                    item(key = PICKER_RECOMMENDED_HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                        Text(RECOMMENDED_TITLE, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                    itemsIndexed(rows, key = { _, row -> pickerRecommendedKey(row.entry.id) }) { index, row ->
                        TvPluginCard(
                            row = row,
                            art = art[row.entry.repo],
                            modifier = gridFocus.stop(pickerRecommendedKey(row.entry.id), leaveDown = focusDone)
                                .then(
                                    if (index == 0) {
                                        Modifier.focusRequester(firstCardFocus).onFocusChanged { firstCardFocused = it.hasFocus }
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(if (cardHasNothingToTheRight(index, rows.lastIndex, columns)) Modifier.noFocusToTheRight() else Modifier),
                            reserveStatusLine = statusLines.getOrElse(index) { false },
                            compact = true,
                            onClick = { runCatalogAction(vm, row) },
                        )
                    }
                    tvCommunityItems(
                        community,
                        art,
                        columns,
                        onRefresh = vm::refreshCommunity,
                        onAction = { runCatalogAction(vm, it) },
                        focus = gridFocus,
                        leaveDown = focusDone,
                        compact = true,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        ) {
            TvCompactAction(
                label = SOURCE_PICKER_DONE,
                // Dimmed but still focusable until a source is installed (see TvCompactAction), so the refocus
                // can land on it when there is no card to take it, and enabling it never moves focus.
                modifier = Modifier
                    .focusRequester(doneFocus)
                    .onFocusChanged { doneFocused = it.hasFocus }
                    // Up goes back to the card (or "Actualizar") Down came from, not wherever the geometry points.
                    .onPreviewKeyEvent { e ->
                        val target = gridFocus.returnTarget()
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && target != null) {
                            gridFocus.focus(target, down = false)
                            true
                        } else {
                            false
                        }
                    },
                enabled = pickerCanFinish(plugins),
                onClick = onFinish,
            )
        }
    }
    state.consent?.let { PluginConsentDialog(it, onInstall = vm::confirmInstall, onCancel = vm::cancelConsent) }
    state.configuring?.let { PluginConfigDialog(it, isTv = true, vm = vm) }
}
