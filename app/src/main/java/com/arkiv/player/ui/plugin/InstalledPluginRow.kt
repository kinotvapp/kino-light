package com.arkiv.player.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.data.plugin.catalog.CatalogArtProvider
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import java.io.File

/**
 * The catalog art an installed plugin's row falls back to when the plugin has no icon of its own: what is
 * already on disk for its address (synchronous and small, never the network), read once per address and only
 * for a plugin that needs it. Null otherwise. For the screens whose view model has no catalog rows to take
 * the art from; the Plugins screens have it in [PluginsViewModel.art].
 */
@Composable
internal fun rememberFallbackArt(p: InstalledPlugin, provider: CatalogArtProvider): CatalogArt? =
    remember(p.record.address, p.iconFile) { if (p.iconFile == null) provider.cached(p.record.address) else null }

/**
 * One installed plugin of the Instalados tab ([PluginsContent]): name and version, status, the hosts it may
 * reach, the on/off switch and its actions, all driven by the [PluginsViewModel] the tab shares. [message] is
 * the line for THIS row (see [rowMessagePluginId]). It draws the plugin's own icon, or [art]'s when the
 * plugin has none ([installedIconFile]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InstalledPluginRow(p: InstalledPlugin, busy: Boolean, message: String?, vm: PluginsViewModel, art: CatalogArt? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            installedIconFile(p.iconFile, art)?.let { InstalledPluginIcon(it, size = 40.dp) }
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
        // A FlowRow, not a Row: at a phone's width the three buttons do not fit side by side, and a Row squeezes
        // the last one until its word breaks ("Desinstal / ar"). Here each button keeps its text on one line and
        // the ones that do not fit move, whole, to the next line.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (p.manifest.settings.isNotEmpty()) {
                TextButton(onClick = { vm.openSettings(p.id) }, enabled = !busy) { ActionLabel("Configurar") }
            }
            TextButton(onClick = { vm.checkUpdate(p.id) }, enabled = !busy) {
                ActionLabel(if (p.status == PluginStatus.UPDATE_PENDING) "Revisar actualización" else "Buscar actualización")
            }
            TextButton(onClick = { vm.askUninstall(p) }, enabled = !busy) { ActionLabel("Desinstalar", color = ArkivRed) }
        }
    }
}

/** The words of one of the row's text buttons: always one line, so a word is never broken across two. */
@Composable
private fun ActionLabel(text: String, color: Color = Color.Unspecified) {
    Text(text, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * An installed plugin's own icon, [size] square with rounded corners. It is decoration (the name is read
 * next to it) and is dropped when the file cannot be decoded, so a corrupt icon leaves no empty square
 * in the row. Shared by the phone's and the TV's installed rows; not focusable.
 */
@Composable
internal fun InstalledPluginIcon(file: File, size: Dp) {
    var failed by remember(file) { mutableStateOf(false) }
    if (!failed) {
        AsyncImage(
            model = file,
            contentDescription = null,
            onError = { failed = true },
            modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)),
        )
    }
}
