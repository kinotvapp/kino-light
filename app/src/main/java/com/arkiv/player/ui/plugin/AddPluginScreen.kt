package com.arkiv.player.ui.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.ui.catalog.MetaChip
import com.arkiv.player.ui.readingWidth
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * How the "Agregar plugin" window is reached. [SETTINGS] is the button in Ajustes ▸ Plugins: back
 * closes it. [ONBOARDING] is the mandatory first-launch picker (not wired yet): it has no way out,
 * so it draws no back arrow and swallows system Back.
 */
enum class AddPluginMode { ONBOARDING, SETTINGS }

/** Whether the person can leave the window: the back arrow is drawn and system Back closes it. */
internal val AddPluginMode.canClose: Boolean get() = this == AddPluginMode.SETTINGS

/** What system Back does in [mode]: closes the window when it has a way out, nothing otherwise. Both windows use it. */
internal fun handleAddPluginBack(mode: AddPluginMode, onClose: () -> Unit) {
    if (mode.canClose) onClose()
}

/**
 * The "Agregar plugin" window on the phone: search the recommended plugins and install one, add one
 * by `usuario/repositorio`, and manage what is installed. Installing always goes through the consent
 * sheet, whichever list it starts from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPluginScreen(mode: AddPluginMode, onClose: () -> Unit) {
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
    val rowMessageId = rowMessagePluginId(state, plugins)
    val rows = legacyFirst(catalog.rows)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agregar plugin", maxLines = 1) },
                navigationIcon = {
                    if (mode.canClose) {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ArkivBlack, titleContentColor = Color.White, navigationIconContentColor = Color.White,
                ),
            )
        },
        containerColor = ArkivBlack,
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .readingWidth()
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    // The bottom inset is taken by the list's own padding; the keyboard then only adds
                    // what it covers on top of it.
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                // Above the list, not inside it: what an install or an add answers must be seen wherever
                // the list is scrolled to. A message about one installed plugin shows on its own row.
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArkivRed)
                if (rowMessageId == null) {
                    state.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = state.query,
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
                        item(key = "seed-notice") {
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
                    item(key = "recommended-title") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle("Recomendados")
                            if (catalog.loading) CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        }
                    }
                    items(rows, key = { "catalog-${it.entry.id}" }) { row -> CatalogCard(row, busy = state.busy, vm = vm) }
                    if (rows.isEmpty() && !catalog.loading) {
                        item(key = "no-match") {
                            Text("No hay plugins que coincidan.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
                        }
                    }

                    item(key = "custom") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                            SectionTitle("Agregar uno custom")
                            Text(
                                "Agrega fuentes de video publicadas en GitHub. Cada plugin solo puede conectarse con los sitios que te muestra antes de instalarlo.",
                                style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary,
                            )
                            OutlinedTextField(
                                value = state.address,
                                onValueChange = vm::onAddressChange,
                                label = { Text("usuario/repositorio") },
                                singleLine = true,
                                enabled = !state.busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { vm.add() }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(onClick = vm::add, enabled = !state.busy && state.address.isNotBlank()) {
                                Text(if (state.busy) "Revisando…" else "Agregar")
                            }
                        }
                    }

                    item(key = "installed-title") { SectionTitle("Instalados", Modifier.padding(top = 12.dp)) }
                    if (plugins.isEmpty()) {
                        item(key = "none-installed") {
                            Text("Todavía no tienes plugins.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
                        }
                    }
                    items(plugins, key = { "installed-${it.id}" }) { p ->
                        InstalledPluginRow(p, busy = state.busy, message = state.message.takeIf { rowMessageId == p.id }, vm = vm)
                    }
                }
            }
        }
    }

    state.consent?.let { PluginConsentDialog(it, onInstall = vm::confirmInstall, onCancel = vm::cancelConsent) }
    state.confirmUninstall?.let { PluginUninstallDialog(it, onConfirm = vm::confirmUninstall, onCancel = vm::cancelUninstall) }
    state.configuring?.let { PluginConfigDialog(it, isTv = false, vm = vm) }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = modifier)
}

/** One recommended plugin: what it is, and the one button [catalogActionOf] says fits its state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogCard(row: CatalogRow, busy: Boolean, vm: PluginsViewModel) {
    val entry = row.entry
    val installed = row.installed
    val action = catalogActionOf(row)
    Surface(shape = RoundedCornerShape(12.dp), color = ArkivSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f, fill = false))
                if (entry.legacyDefault) MetaChip("Lo que ya usabas", ArkivRed, strong = true)
            }
            if (entry.description.isNotBlank()) {
                Text(entry.description, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
            }
            if (entry.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    entry.tags.forEach { MetaChip(it) }
                }
            }
            // Why the button says Activar / Configurar / Instalar again, in the same words as the installed list.
            if (installed != null && action != CatalogAction.INSTALLED) {
                Text(pluginStatusText(installed.status), style = MaterialTheme.typography.bodySmall, color = ArkivRed)
            }
            Button(
                onClick = {
                    when (action) {
                        CatalogAction.INSTALL -> vm.installFromCatalog(entry)
                        CatalogAction.CONFIGURE -> installed?.let { vm.openSettings(it.id) }
                        CatalogAction.ENABLE -> installed?.let { vm.setEnabled(it.id, true) }
                        CatalogAction.INSTALLED -> Unit
                    }
                },
                enabled = !busy && action != CatalogAction.INSTALLED,
            ) {
                Text(
                    when (action) {
                        CatalogAction.INSTALL -> "Instalar"
                        CatalogAction.CONFIGURE -> "Configurar"
                        CatalogAction.ENABLE -> "Activar"
                        CatalogAction.INSTALLED -> "Instalado"
                    },
                )
            }
        }
    }
}
