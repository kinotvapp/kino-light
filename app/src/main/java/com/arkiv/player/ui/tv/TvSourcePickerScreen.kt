package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.ui.player.WAIT_BETWEEN_FOCUS_ATTEMPTS_MS
import com.arkiv.player.ui.player.retryFocus
import com.arkiv.player.ui.plugin.PluginConfigDialog
import com.arkiv.player.ui.plugin.PluginConsentDialog
import com.arkiv.player.ui.plugin.RECOMMENDED_TITLE
import com.arkiv.player.ui.plugin.SOURCE_PICKER_DONE
import com.arkiv.player.ui.plugin.SOURCE_PICKER_LINE
import com.arkiv.player.ui.plugin.SOURCE_PICKER_SKIP
import com.arkiv.player.ui.plugin.SOURCE_PICKER_TITLE
import com.arkiv.player.ui.plugin.legacyFirst
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

/** How long the first card has to show up for the initial focus before "Ahora no" takes it instead. */
private const val PICKER_FOCUS_GRACE_MS = 1_500L

/** Attempts per focus target, [WAIT_BETWEEN_FOCUS_ATTEMPTS_MS] apart: about a second each. */
private const val PICKER_FOCUS_ATTEMPTS = 30

/**
 * "Elige tus fuentes" on the TV. Laid out like [com.arkiv.player.ui.plugin.SourcePickerScreen], for the D-pad:
 * - Initial focus is the first recommended card (its action is "Instalar"); if there is no card within
 *   [PICKER_FOCUS_GRACE_MS], or it will not take focus, "Ahora no" does.
 * - Success is each target's OWN focus state, never `requestFocus()`'s return (it reports nothing; see
 *   [retryFocus]).
 * - The cards stay focusable in every state (an installed one reads "Instalado"), and "Listo" is a
 *   [TvCompactAction] that stays focusable while disabled, so a state change never throws focus out.
 * - If the rows change under the focused card, or a dialog closes, and nothing here holds focus, it goes
 *   back to the first card (or "Ahora no") ([pickerNeedsRefocus]).
 * - Back and both buttons call [onFinish].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvSourcePickerScreen(onFinish: () -> Unit) {
    BackHandler(onBack = onFinish)
    val vm = sourcePickerViewModel()
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val community by vm.community.collectAsStateWithLifecycle()
    val art by vm.art.collectAsStateWithLifecycle()
    val rows = legacyFirst(catalog.rows)
    val statusLines = remember(rows) { gridLinesWithStatus(rows, TV_CATALOG_COLUMNS) }

    val firstCardFocus = remember { FocusRequester() }
    val skipFocus = remember { FocusRequester() }
    var firstCardFocused by remember { mutableStateOf(false) }
    var skipFocused by remember { mutableStateOf(false) }
    var placed by remember { mutableStateOf(false) }
    var screenHasFocus by remember { mutableStateOf(false) }
    val dialogOpen = state.consent != null || state.configuring != null
    val hasCards by rememberUpdatedState(rows.isNotEmpty())

    // The first card when there is one and it takes focus, "Ahora no" otherwise.
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
            isAlreadyFocused = { skipFocused },
            wait = { delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS) },
            request = { skipFocus.requestFocus() },
        )
    }

    LaunchedEffect(Unit) {
        withTimeoutOrNull(PICKER_FOCUS_GRACE_MS) { snapshotFlow { hasCards }.first { it } }
        landFocus()
        placed = true
    }
    val cardKeys = rows.map { it.entry.id } + community.rows.map { it.entry.repo }
    LaunchedEffect(cardKeys, plugins.size, dialogOpen) {
        // A frame for a removed node (or a closed dialog) to clear focus before asking who holds it.
        delay(WAIT_BETWEEN_FOCUS_ATTEMPTS_MS)
        if (pickerNeedsRefocus(placed, screenHasFocus, dialogOpen)) landFocus()
    }

    val density = LocalDensity.current
    val bringIntoView = remember(density) { KeepMarginBringIntoView(with(density) { FOCUS_MARGIN.toPx() }) }
    Column(
        Modifier.fillMaxSize().background(ArkivBlack).padding(horizontal = 64.dp, vertical = 32.dp)
            .onFocusChanged { screenHasFocus = it.hasFocus },
    ) {
        Text(SOURCE_PICKER_TITLE, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(SOURCE_PICKER_LINE, style = MaterialTheme.typography.bodyLarge, color = ArkivTextSecondary, modifier = Modifier.padding(top = 8.dp))
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = ArkivRed)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.padding(top = 8.dp)) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(TV_CATALOG_COLUMNS),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "recommended-header", span = { GridItemSpan(maxLineSpan) }) {
                    Text(RECOMMENDED_TITLE, style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                itemsIndexed(rows, key = { _, row -> "card-${row.entry.id}" }) { index, row ->
                    TvPluginCard(
                        row = row,
                        art = art[row.entry.repo],
                        modifier = Modifier
                            .then(
                                if (index == 0) {
                                    Modifier.focusRequester(firstCardFocus).onFocusChanged { firstCardFocused = it.hasFocus }
                                } else {
                                    Modifier
                                },
                            )
                            .then(if (cardHasNothingToTheRight(index, rows.lastIndex, TV_CATALOG_COLUMNS)) Modifier.noFocusToTheRight() else Modifier),
                        reserveStatusLine = statusLines.getOrElse(index) { false },
                        onClick = { runCatalogAction(vm, row) },
                    )
                }
                tvCommunityItems(community, art, TV_CATALOG_COLUMNS, onRefresh = vm::refreshCommunity, onAction = { runCatalogAction(vm, it) })
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        ) {
            TvCompactAction(
                label = SOURCE_PICKER_SKIP,
                modifier = Modifier.focusRequester(skipFocus).onFocusChanged { skipFocused = it.hasFocus },
                onClick = onFinish,
            )
            TvCompactAction(label = SOURCE_PICKER_DONE, enabled = pickerCanFinish(plugins), onClick = onFinish)
        }
    }
    state.consent?.let { PluginConsentDialog(it, onInstall = vm::confirmInstall, onCancel = vm::cancelConsent) }
    state.configuring?.let { PluginConfigDialog(it, isTv = true, vm = vm) }
}
