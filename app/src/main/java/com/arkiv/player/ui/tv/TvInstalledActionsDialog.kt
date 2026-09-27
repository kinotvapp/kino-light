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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.plugin.PluginsViewModel
import com.arkiv.player.ui.theme.ArkivSurface

/**
 * The TV's actions dialog for one installed plugin: OK on its card ([TvInstalledPluginCard]) opens this
 * instead of walking four rows, as the deleted `TvInstalledPluginRows` used to. Same content, same
 * [PluginsViewModel] calls, same order the old rows picked their first focusable action in (the switch's own
 * action first, else Configurar, else "Buscar actualización", which is always there): "Activar {name}" or "Desactivar {name}"
 * (absent for [PluginStatus.DAMAGED], which cannot be toggled), "Configurar {name}" (only with settings),
 * "Buscar actualización de {name}" (or "Revisar actualización de {name}" once one is pending consent),
 * "Desinstalar {name}" and "Cerrar". Every action closes the dialog after it runs, so whatever it opens (the
 * consent sheet, "¿Desinstalar…?", Configurar) shows alone, not stacked under this one; [onDismiss] is also
 * Back and "Cerrar" -- the caller sends focus back to the card that opened it.
 */
@Composable
internal fun TvInstalledActionsDialog(plugin: InstalledPlugin, vm: PluginsViewModel, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    FocusWhenReady(firstFocus)
    // TvCompactAction, not TvActionOption: the latter is sized to 60% of a wide Ajustes pane, which inside
    // this narrow dialog would leave a long label ("Buscar actualización de …") cramped; TvCompactAction
    // takes exactly the width it's given (fillMaxWidth here) and keeps its label to one line.
    fun firstModifier() = Modifier.focusRequester(firstFocus).fillMaxWidth()
    val name = plugin.manifest.name
    val hasSwitch = plugin.status != PluginStatus.DAMAGED
    val hasSettings = plugin.manifest.settings.isNotEmpty()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ArkivSurface)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(name, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
