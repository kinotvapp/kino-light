package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
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
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.ui.plugin.PluginConsentDialog
import com.arkiv.player.ui.plugin.PluginUninstallDialog
import com.arkiv.player.ui.plugin.PluginsViewModel
import com.arkiv.player.ui.plugin.pluginStatusText
import com.arkiv.player.ui.plugin.rowMessagePluginId
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Ajustes ▸ Plugins on the TV: the phone's section with D-pad-sized actions. */
@Composable
internal fun TvSettingsPlugins() {
    val graph = rememberGraph()
    val vm: PluginsViewModel = viewModel(factory = viewModelFactory { initializer { PluginsViewModel(graph.pluginAdmin) } })
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val rowMessageId = rowMessagePluginId(state, plugins)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Agregar un plugin", style = MaterialTheme.typography.titleMedium, color = Color.White)
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
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    } else {
                        false
                    }
                },
        )
        TvActionOption(label = if (state.busy) "Revisando…" else "Agregar") { vm.add() }
        if (rowMessageId == null) {
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White) }
        }

        Text("Instalados", style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (plugins.isEmpty()) Text("Todavía no tienes plugins.", color = ArkivTextSecondary)
        plugins.forEach { p ->
            Text("${p.manifest.name} · ${p.record.version} — ${pluginStatusText(p.status)}", style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text("Se conectará a: ${p.hosts.labels.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
            if (rowMessageId == p.id) {
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White) }
            }
            if (p.status != PluginStatus.DAMAGED) {
                TvActionOption(label = "${p.manifest.name}: ${if (p.isUsable) "activado" else "desactivado"}") { vm.setEnabled(p.id, !p.isUsable) }
            }
            if (p.manifest.settings.isNotEmpty()) {
                TvActionOption(label = "Configurar ${p.manifest.name}") { vm.openSettings(p.id) }
            }
            TvActionOption(label = if (p.status == PluginStatus.UPDATE_PENDING) "Revisar actualización de ${p.manifest.name}" else "Buscar actualización de ${p.manifest.name}") { vm.checkUpdate(p.id) }
            TvActionOption(label = "Desinstalar ${p.manifest.name}") { vm.askUninstall(p) }
        }
    }

    state.consent?.let { PluginConsentDialog(it, onInstall = vm::confirmInstall, onCancel = vm::cancelConsent) }
    state.confirmUninstall?.let { PluginUninstallDialog(it, onConfirm = vm::confirmUninstall, onCancel = vm::cancelUninstall) }
    state.configuring?.let { com.arkiv.player.ui.plugin.PluginConfigDialog(it, isTv = true, vm = vm) }
}
