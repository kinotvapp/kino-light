package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.plugin.catalog.CatalogOrigin
import com.arkiv.player.ui.plugin.AddPluginMode
import com.arkiv.player.ui.plugin.CatalogAction
import com.arkiv.player.ui.plugin.CatalogRow
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.plugin.PluginConsentDialog
import com.arkiv.player.ui.plugin.PluginConfigDialog
import com.arkiv.player.ui.plugin.PluginUninstallDialog
import com.arkiv.player.ui.plugin.PluginsViewModel
import com.arkiv.player.ui.plugin.catalogActionOf
import com.arkiv.player.ui.plugin.legacyFirst
import com.arkiv.player.ui.plugin.pluginStatusText
import com.arkiv.player.ui.plugin.rowMessagePluginId
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * The "Agregar plugin" window on the TV: the phone's sections ([com.arkiv.player.ui.plugin.AddPluginScreen])
 * laid out for the D-pad. Every row is a [TvActionOption]; one lazy list scrolls with the remote.
 *
 * Back closes it in [AddPluginMode.SETTINGS]; the onboarding picker has no way out, so it swallows
 * Back. The TV has no close button, so [mode] changes nothing else.
 */
@Composable
fun TvAddPluginScreen(mode: AddPluginMode, onClose: () -> Unit) {
    BackHandler { if (mode == AddPluginMode.SETTINGS) onClose() }

    val graph = rememberGraph()
    // Own key: this window can be hosted next to the Plugins tab's view model on the same owner.
    val vm: PluginsViewModel = viewModel(
        key = "add-plugin",
        factory = viewModelFactory { initializer { PluginsViewModel(graph.pluginAdmin, catalogProvider = graph.pluginCatalog) } },
    )
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val rowMessageId = rowMessagePluginId(state, plugins)
    val rows = legacyFirst(catalog.rows)

    // Initial focus goes to the first recommended row, but rows arrive after the window opens: the
    // request only starts once one exists (a requester on a row that is not composed throws), and it
    // stops for good as soon as focus is anywhere in the list, so a catalog that reloads or a search
    // that filters never takes focus away from the person.
    val firstRowFocus = remember { FocusRequester() }
    var focusInList by remember { mutableStateOf(false) }
    if (!focusInList && rows.isNotEmpty()) FocusWhenReady(firstRowFocus)

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
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onFocusChanged { if (it.hasFocus) focusInList = true },
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    label = { Text("Buscar plugins") },
                    singleLine = true,
                    // `Done` just leaves the field (the list filters as you type).
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(0.6f).downLeavesTheField(focusManager),
                )
            }
            if (catalog.origin == CatalogOrigin.SEED) {
                item(key = "seed-notice") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Sin conexión: mostrando la lista guardada.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
                        TvActionOption(label = "Reintentar") { vm.reloadCatalog() }
                    }
                }
            }
            item(key = "recommended-title") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Recomendados", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    if (catalog.loading) CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
            items(rows, key = { "catalog-${it.entry.id}" }) { row ->
                TvCatalogRow(row, vm, focusRequester = if (row.entry.id == rows.first().entry.id) firstRowFocus else null)
            }
            if (rows.isEmpty() && !catalog.loading) {
                item(key = "no-match") {
                    Text("No hay plugins que coincidan.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
                }
            }

            item(key = "custom") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
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
                        modifier = Modifier.fillMaxWidth(0.6f).downLeavesTheField(focusManager),
                    )
                    TvActionOption(label = if (state.busy) "Revisando…" else "Agregar") { vm.add() }
                }
            }

            item(key = "installed-title") {
                Text("Instalados", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 12.dp))
            }
            if (plugins.isEmpty()) {
                item(key = "none-installed") {
                    Text("Todavía no tienes plugins.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
                }
            }
            items(plugins, key = { "installed-${it.id}" }) { p ->
                // One item per plugin: a lazy item stacks several roots on top of each other.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvInstalledPluginRows(p, message = state.message.takeIf { rowMessageId == p.id }, vm = vm)
                }
            }
        }
    }

    state.consent?.let { PluginConsentDialog(it, onInstall = vm::confirmInstall, onCancel = vm::cancelConsent) }
    state.confirmUninstall?.let { PluginUninstallDialog(it, onConfirm = vm::confirmUninstall, onCancel = vm::cancelUninstall) }
    state.configuring?.let { PluginConfigDialog(it, isTv = true, vm = vm) }
}

/**
 * One recommended plugin: the action [catalogActionOf] says fits its state, then what it is. An
 * installed plugin that needs nothing reads as a status and its action does nothing, but it stays
 * focusable so the list is still walked one row at a time.
 */
@Composable
private fun TvCatalogRow(row: CatalogRow, vm: PluginsViewModel, focusRequester: FocusRequester?) {
    val entry = row.entry
    val installed = row.installed
    val action = catalogActionOf(row)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TvActionOption(
            label = catalogRowLabel(action, entry.name),
            modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
        ) {
            when (action) {
                CatalogAction.INSTALL -> vm.installFromCatalog(entry)
                CatalogAction.CONFIGURE -> installed?.let { vm.openSettings(it.id) }
                CatalogAction.ENABLE -> installed?.let { vm.setEnabled(it.id, true) }
                CatalogAction.INSTALLED -> Unit
            }
        }
        if (entry.legacyDefault) {
            Text("Lo que ya usabas", style = MaterialTheme.typography.bodySmall, color = ArkivRed)
        }
        if (entry.description.isNotBlank()) {
            Text(entry.description, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
        }
        // Why the action says Activar / Configurar / Instalar again, in the same words as the installed list.
        if (installed != null && action != CatalogAction.INSTALLED) {
            Text(pluginStatusText(installed.status), style = MaterialTheme.typography.bodySmall, color = ArkivRed)
        }
    }
}

/** What the action on a recommended row says, with the plugin's name. */
internal fun catalogRowLabel(action: CatalogAction, name: String): String = when (action) {
    CatalogAction.INSTALL -> "Instalar $name"
    CatalogAction.CONFIGURE -> "Configurar $name"
    CatalogAction.ENABLE -> "Activar $name"
    CatalogAction.INSTALLED -> "$name: instalado"
}

/** D-pad Down always leaves a text field: a closed IME otherwise traps focus in it. */
private fun Modifier.downLeavesTheField(focusManager: FocusManager): Modifier = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
        focusManager.moveFocus(FocusDirection.Down)
        true
    } else {
        false
    }
}
