package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.ui.plugin.InstalledPluginIcon
import com.arkiv.player.ui.plugin.PluginsViewModel
import com.arkiv.player.ui.plugin.installedIconFile
import com.arkiv.player.ui.plugin.pluginStatusText
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * One installed plugin's rows: name and status, the sites it talks to, [message] (the line for THIS
 * plugin, see [com.arkiv.player.ui.plugin.rowMessagePluginId]) and its actions. It emits several
 * children, so the caller gives them one column: the Plugins screen's Instalados tab wraps each plugin
 * in one ([TvPluginsContent]). The icon is the plugin's own, or [art]'s when the plugin has none
 * ([installedIconFile]).
 *
 * [firstActionModifier] goes on the first action the plugin shows (the on/off switch, or the next one when
 * a damaged plugin has none), so the caller can send focus to it or say where Up from it leads.
 */
@Composable
internal fun TvInstalledPluginRows(
    p: InstalledPlugin,
    message: String?,
    vm: PluginsViewModel,
    art: CatalogArt? = null,
    firstActionModifier: Modifier = Modifier,
) {
    val heading = "${p.manifest.name} · ${p.record.version} — ${pluginStatusText(p.status)}"
    val iconFile = installedIconFile(p.iconFile, art)
    if (iconFile == null) {
        Text(heading, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    } else {
        // Plain decoration: an image is not a focus target, so the D-pad walks the same actions in the same order.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InstalledPluginIcon(iconFile, size = 32.dp)
            Text(heading, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
    }
    Text("Se conectará a: ${p.hosts.labels.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
    if (message != null) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
    val hasSwitch = p.status != PluginStatus.DAMAGED
    val hasSettings = p.manifest.settings.isNotEmpty()
    if (hasSwitch) {
        TvActionOption(label = "${p.manifest.name}: ${if (p.isUsable) "activado" else "desactivado"}", modifier = firstActionModifier) { vm.setEnabled(p.id, !p.isUsable) }
    }
    if (hasSettings) {
        TvActionOption(label = "Configurar ${p.manifest.name}", modifier = if (hasSwitch) Modifier else firstActionModifier) { vm.openSettings(p.id) }
    }
    // The update check is always there: it is the first action of a damaged plugin with no settings.
    TvActionOption(
        label = if (p.status == PluginStatus.UPDATE_PENDING) "Revisar actualización de ${p.manifest.name}" else "Buscar actualización de ${p.manifest.name}",
        modifier = if (hasSwitch || hasSettings) Modifier else firstActionModifier,
    ) { vm.checkUpdate(p.id) }
    TvActionOption(label = "Desinstalar ${p.manifest.name}") { vm.askUninstall(p) }
}
