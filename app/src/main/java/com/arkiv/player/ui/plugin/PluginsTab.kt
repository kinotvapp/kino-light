package com.arkiv.player.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Ajustes ▸ Plugins on the phone: add by `usuario/repositorio`, and manage what's installed. */
@Composable
fun PluginsTab() {
    val graph = rememberGraph()
    val vm: PluginsViewModel = viewModel(factory = viewModelFactory { initializer { PluginsViewModel(graph.pluginAdmin) } })
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val rowMessageId = rowMessagePluginId(state, plugins)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Plugins", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 24.dp))
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
        if (rowMessageId == null) {
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White) }
        }

        if (plugins.isEmpty()) {
            Text("Todavía no tienes plugins.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        }
        plugins.forEach { p ->
            PluginRow(p, busy = state.busy, message = state.message.takeIf { rowMessageId == p.id }, vm = vm)
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }

    state.consent?.let { PluginConsentDialog(it, onInstall = vm::confirmInstall, onCancel = vm::cancelConsent) }
    state.confirmUninstall?.let { PluginUninstallDialog(it, onConfirm = vm::confirmUninstall, onCancel = vm::cancelUninstall) }
    state.configuring?.let { PluginConfigDialog(it, isTv = false, vm = vm) }
}

@Composable
private fun PluginRow(p: InstalledPlugin, busy: Boolean, message: String?, vm: PluginsViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (p.iconFile != null) {
                AsyncImage(model = p.iconFile, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
            }
            Column(Modifier.weight(1f)) {
                Text("${p.manifest.name} · ${p.record.version}", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                Text(
                    pluginStatusText(p.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.status == PluginStatus.ACTIVE) ArkivTextSecondary else ArkivRed,
                )
            }
            Switch(
                checked = p.isUsable,
                enabled = p.status != PluginStatus.DAMAGED,
                onCheckedChange = { on -> vm.setEnabled(p.id, on) },
            )
        }
        Text("Se conectará a: ${p.hosts.labels.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.manifest.settings.isNotEmpty()) {
                TextButton(onClick = { vm.openSettings(p.id) }, enabled = !busy) { Text("Configurar") }
            }
            TextButton(onClick = { vm.checkUpdate(p.id) }, enabled = !busy) {
                Text(if (p.status == PluginStatus.UPDATE_PENDING) "Revisar actualización" else "Buscar actualización")
            }
            TextButton(onClick = { vm.askUninstall(p) }, enabled = !busy) { Text("Desinstalar", color = ArkivRed) }
        }
    }
}
