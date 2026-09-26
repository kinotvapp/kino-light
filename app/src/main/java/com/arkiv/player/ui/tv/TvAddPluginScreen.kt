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
import androidx.compose.ui.platform.LocalFocusManager
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
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.plugin.PluginConsentDialog
import com.arkiv.player.ui.plugin.PluginConfigDialog
import com.arkiv.player.ui.plugin.PluginUninstallDialog
import com.arkiv.player.ui.plugin.PluginsViewModel
import com.arkiv.player.ui.plugin.catalogActionOf
import com.arkiv.player.ui.plugin.catalogRefreshLine
import com.arkiv.player.ui.plugin.handleAddPluginBack
import com.arkiv.player.ui.plugin.legacyFirst
import com.arkiv.player.ui.plugin.pluginStatusText
import com.arkiv.player.ui.plugin.rowMessagePluginId
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/** How long the text fields stay out of focus at most while the first recommended row takes it; see [TvAddPluginScreen]. */
private const val INITIAL_FOCUS_GRACE_MS = 1_500L

/**
 * The "Agregar plugin" window on the TV: the phone's sections ([com.arkiv.player.ui.plugin.AddPluginScreen])
 * laid out for the D-pad. Every row is a [TvActionOption]; one lazy list scrolls with the remote.
 *
 * Back closes it in [AddPluginMode.SETTINGS]; the onboarding picker has no way out, so it swallows
 * Back. The TV has no close button, so [mode] changes nothing else.
 */
@Composable
fun TvAddPluginScreen(mode: AddPluginMode, onClose: () -> Unit) {
    BackHandler { handleAddPluginBack(mode, onClose) }

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

    // Initial focus goes to the first recommended row, with no keyboard. Two things make that fragile:
    //  1. On a TV (non-touch mode) Android gives the window's Compose view focus on the first frame, and Compose
    //     hands it to the first focusable node it finds -- the search field, which sits at the top of the list.
    //     A focused text field opens the system keyboard by itself, covering the list. So both text fields
    //     refuse focus (canFocus = false) until [initialFocusPlaced]; the default focus then lands on another
    //     control and [FocusWhenReady] moves it to the first row a moment later.
    //  2. The rows may arrive after the window opens, and a requester on a row that is not composed throws, so
    //     the request only runs while a row exists (and [FocusWhenReady] retries until the row is attached).
    // [initialFocusPlaced] turns true, for good, as soon as that first row has focus. If that never happens
    // (no rows, or the row could not take focus) it turns true anyway after [INITIAL_FOCUS_GRACE_MS], so the
    // fields are never unreachable. Once true nothing requests focus again, so a catalog that reloads or a
    // search that filters never takes focus away from the person, and moving INTO the search field by D-pad
    // (Up from the first row) works, keyboard included, as it always did.
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
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(0.6f).focusProperties { canFocus = initialFocusPlaced }.dpadLeavesTheField(focusManager),
                )
            }
            // Only while the list is still the copy shipped in the APK. The notice waits for the refresh to end
            // (it may still succeed); meanwhile the action reads "Actualizando…" and does nothing, but stays
            // focusable so focus is not thrown out from under the person when the label changes.
            catalogRefreshLine(catalog)?.let { line ->
                item(key = "seed-notice") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        line.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary) }
                        TvActionOption(label = line.actionLabel) { if (line.actionEnabled) vm.reloadCatalog() }
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
                TvCatalogRow(row, vm, modifier = if (row.entry.id == rows.first().entry.id) firstRowModifier else Modifier)
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
                        modifier = Modifier.fillMaxWidth(0.6f).focusProperties { canFocus = initialFocusPlaced }.dpadLeavesTheField(focusManager),
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
private fun TvCatalogRow(row: CatalogRow, vm: PluginsViewModel, modifier: Modifier) {
    val entry = row.entry
    val installed = row.installed
    val action = catalogActionOf(row)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TvActionOption(
            label = catalogRowLabel(action, entry.name),
            modifier = modifier,
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
