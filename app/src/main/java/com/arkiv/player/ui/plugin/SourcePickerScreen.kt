package com.arkiv.player.ui.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.tv.gridLinesWithStatus

/**
 * "Elige tus fuentes" on the phone: title and one line, what an action answers, then one grid with the
 * recommended cards and "De la comunidad" ([communityItems]), and at the bottom "Ahora no" and "Listo"
 * ([pickerCanFinish]); "Ahora no" is off while an install runs ([pickerCanSkip]). Both buttons and system Back
 * call [onFinish] (rulings R9, R10). Installing opens the
 * consent sheet and, for a plugin that needs setup, Configurar, both over this screen; the card then reads
 * "Instalado".
 */
@Composable
fun SourcePickerScreen(onFinish: () -> Unit) {
    BackHandler(onBack = onFinish)
    val vm = sourcePickerViewModel()
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val community by vm.community.collectAsStateWithLifecycle()
    val art by vm.art.collectAsStateWithLifecycle()
    val rows = legacyFirst(catalog.rows)
    val statusLines = remember(rows) { gridLinesWithStatus(rows, PHONE_CATALOG_COLUMNS) }

    Column(Modifier.fillMaxSize().background(ArkivBlack).systemBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(SOURCE_PICKER_TITLE, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(SOURCE_PICKER_LINE, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArkivRed)
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(PHONE_CATALOG_COLUMNS),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "recommended-header", span = { GridItemSpan(maxLineSpan) }) {
                Text(RECOMMENDED_TITLE, style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            itemsIndexed(rows, key = { _, row -> "card-${row.entry.id}" }) { index, row ->
                PluginCard(
                    row = row,
                    art = art[row.entry.repo],
                    reserveStatusLine = statusLines.getOrElse(index) { false },
                    enabled = !state.busy,
                    onAction = { runCatalogAction(vm, row) },
                )
            }
            communityItems(community, art, busy = state.busy, columns = PHONE_CATALOG_COLUMNS, onRefresh = vm::refreshCommunity, onAction = { runCatalogAction(vm, it) })
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val canSkip = pickerCanSkip(state.busy)
            TextButton(onClick = onFinish, enabled = canSkip) {
                Text(SOURCE_PICKER_SKIP, color = ArkivTextSecondary.copy(alpha = if (canSkip) 1f else 0.4f))
            }
            Button(
                onClick = onFinish,
                enabled = pickerCanFinish(plugins),
                colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
            ) { Text(SOURCE_PICKER_DONE) }
        }
    }
    state.consent?.let { PluginConsentDialog(it, onInstall = vm::confirmInstall, onCancel = vm::cancelConsent) }
    state.configuring?.let { PluginConfigDialog(it, isTv = false, vm = vm) }
}
