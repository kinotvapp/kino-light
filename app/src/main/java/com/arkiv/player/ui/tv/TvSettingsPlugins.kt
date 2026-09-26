package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.plugin.InstalledPlugin
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
internal fun TvSettingsPlugins(onOpenAddPlugin: () -> Unit = {}) {
    val graph = rememberGraph()
    val vm: PluginsViewModel = viewModel(factory = viewModelFactory { initializer { PluginsViewModel(graph.pluginAdmin) } })
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val rowMessageId = rowMessagePluginId(state, plugins)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Plugins", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(
            "Agrega fuentes de video publicadas en GitHub. Cada plugin solo puede conectarse con los sitios que te muestra antes de instalarlo.",
            style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary,
        )
        TvActionOption(label = "Agregar plugin") { onOpenAddPlugin() }
        if (rowMessageId == null) {
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White) }
        }

        Text("Instalados", style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (plugins.isEmpty()) Text("Todavía no tienes plugins.", color = ArkivTextSecondary)
        plugins.forEach { p ->
            TvInstalledPluginRows(p, message = state.message.takeIf { rowMessageId == p.id }, vm = vm)
        }
    }

    state.consent?.let { PluginConsentDialog(it, onInstall = vm::confirmInstall, onCancel = vm::cancelConsent) }
    state.confirmUninstall?.let { PluginUninstallDialog(it, onConfirm = vm::confirmUninstall, onCancel = vm::cancelUninstall) }
    state.configuring?.let { com.arkiv.player.ui.plugin.PluginConfigDialog(it, isTv = true, vm = vm) }
}

/**
 * One installed plugin's rows: name and status, the sites it talks to, [message] (the line for THIS
 * plugin, see [rowMessagePluginId]) and its actions. It emits several children, so the caller gives
 * them one column: Ajustes ▸ Plugins spaces them in its own, the "Agregar plugin" window wraps each
 * plugin in one.
 */
@Composable
internal fun TvInstalledPluginRows(p: InstalledPlugin, message: String?, vm: PluginsViewModel) {
    Text("${p.manifest.name} · ${p.record.version} — ${pluginStatusText(p.status)}", style = MaterialTheme.typography.bodyLarge, color = Color.White)
    Text("Se conectará a: ${p.hosts.labels.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
    if (message != null) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White)
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
