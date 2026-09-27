package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.plugin.InstalledCardModel
import com.arkiv.player.ui.plugin.PluginsViewModel
import com.arkiv.player.ui.plugin.installedCardModel
import com.arkiv.player.ui.plugin.pluginStatusText
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * The TV's actions dialog for one installed plugin: OK on its card ([TvInstalledPluginCard]) opens this
 * instead of walking four rows, as the deleted `TvInstalledPluginRows` used to. Its header restores what
 * that row's own heading always showed inline -- the name, version and full status sentence, then the
 * complete host list, wrapping freely (the card's own lines are capped and may cut either) -- since the
 * dialog covers the card while it is up. Same content otherwise, same [PluginsViewModel] calls, same order
 * the old rows picked their first focusable action in (the switch's own action first, else Configurar, else
 * "Buscar actualización", which is always there): "Activar {name}" or "Desactivar {name}" (absent for a
 * damaged plugin, which cannot be toggled -- [InstalledCardModel.switchEnabled]), "Configurar {name}" (only
 * with settings -- [InstalledCardModel.hasSettings]), "Buscar actualización de {name}" (or "Revisar
 * actualización de {name}" once one is pending consent), "Desinstalar {name}" and "Cerrar". Every action
 * closes the dialog after it runs, so whatever it opens (the consent sheet, "¿Desinstalar…?", Configurar)
 * shows alone, not stacked under this one; [onDismiss] is also Back and "Cerrar" -- the caller sends focus
 * back to the card that opened it.
 */
@Composable
internal fun TvInstalledActionsDialog(plugin: InstalledPlugin, art: CatalogArt?, vm: PluginsViewModel, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    FocusWhenReady(firstFocus)
    // TvCompactAction, not TvActionOption: the latter is sized to 60% of a wide Ajustes pane, which inside
    // this narrow dialog would leave a long label ("Buscar actualización de …") cramped; TvCompactAction
    // takes exactly the width it's given (fillMaxWidth here) and keeps its label to one line.
    fun firstModifier() = Modifier.focusRequester(firstFocus).fillMaxWidth()
    val name = plugin.manifest.name
    // The same model the card built (art only feeds the tile/icon, which this dialog never draws, so it
    // changes nothing here): one rule for "can the switch move" / "does Configurar belong", not a second copy.
    val model = installedCardModel(plugin, art)
    val hasSwitch = model.switchEnabled
    val hasSettings = model.hasSettings

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ArkivSurface)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The old row's own heading, restored: name, version and the full status sentence together.
            Text("${model.nameLine} — ${pluginStatusText(plugin.status)}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            // The full host list, wrapping freely: the card's own line is capped and may cut it.
            Text(
                "Se conectará a: ${plugin.hosts.labels.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
            )
            if (hasSwitch) {
                TvCompactAction(
                    label = if (plugin.isUsable) "Desactivar $name" else "Activar $name",
                    modifier = firstModifier(),
                ) { vm.setEnabled(plugin.id, !plugin.isUsable); onDismiss() }
            }
            if (hasSettings) {
                TvCompactAction(
                    label = "Configurar $name",
                    modifier = if (hasSwitch) Modifier.fillMaxWidth() else firstModifier(),
                ) { vm.openSettings(plugin.id); onDismiss() }
            }
            TvCompactAction(
                label = if (plugin.status == PluginStatus.UPDATE_PENDING) "Revisar actualización de $name" else "Buscar actualización de $name",
                modifier = if (hasSwitch || hasSettings) Modifier.fillMaxWidth() else firstModifier(),
            ) { vm.checkUpdate(plugin.id); onDismiss() }
            TvCompactAction(label = "Desinstalar $name", modifier = Modifier.fillMaxWidth()) { vm.askUninstall(plugin); onDismiss() }
            TvCompactAction(label = "Cerrar", modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}
