package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.arkiv.player.ui.plugin.pluginConsentHostLine
import com.arkiv.player.ui.plugin.pluginStatusText
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * The TV's actions dialog for one installed plugin: OK on its card ([TvInstalledPluginCard]) opens this
 * instead of walking four rows, as the deleted `TvInstalledPluginRows` used to. Its header restores what
 * that row's own heading always showed inline -- the name, version and full status sentence, then the
 * complete host list, wrapping freely (the card's own lines are capped and may cut either) -- since the
 * dialog covers the card while it is up. Same content otherwise, same [PluginsViewModel] calls, in this order
 * ([tvInstalledActions]), with the first focus on "Cerrar" ([tvInstalledActionsInitialFocus]): "Activar {name}" or "Desactivar {name}" (absent for a
 * damaged plugin, which cannot be toggled -- [InstalledCardModel.switchEnabled]), "Configurar {name}" (only
 * with settings -- [InstalledCardModel.hasSettings]), "Buscar actualización de {name}" (or "Revisar
 * actualización de {name}" once one is pending consent), "Olvidar rechazos de host de {name}" (only when
 * `plugin.record.rejectedHosts` isn't empty), "Desinstalar {name}" and "Cerrar". Every action
 * closes the dialog after it runs, so whatever it opens (the consent sheet, "¿Desinstalar…?", Configurar)
 * shows alone, not stacked under this one; [onDismiss] is also Back and "Cerrar" -- the caller sends focus
 * back to the card that opened it.
 */
@Composable
internal fun TvInstalledActionsDialog(plugin: InstalledPlugin, art: CatalogArt?, vm: PluginsViewModel, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    FocusWhenReady(firstFocus)
    val name = plugin.manifest.name
    // The same model the card built (art only feeds the tile/icon, which this dialog never draws, so it
    // changes nothing here): one rule for "can the switch move" / "does Configurar belong", not a second copy.
    val model = installedCardModel(plugin, art)
    val actions = tvInstalledActions(model.switchEnabled, model.hasSettings, plugin.record.rejectedHosts.isNotEmpty())
    val focused = tvInstalledActionsInitialFocus(actions)
    // TvCompactAction, not TvActionOption: the latter is sized to 60% of a wide Ajustes pane, which inside
    // this narrow dialog would leave a long label ("Buscar actualización de …") cramped; TvCompactAction
    // takes exactly the width it's given (fillMaxWidth here) and keeps its label to one line.
    fun modifierFor(action: TvInstalledAction) =
        if (action == focused) Modifier.focusRequester(firstFocus).fillMaxWidth() else Modifier.fillMaxWidth()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            // Scrolls: at a larger TV text size the header's three lines plus five 52 dp actions can run
            // taller than the screen, and "Cerrar" must never be pushed off it (the sibling dialogs,
            // TvAddCustomPluginDialog and PluginConsentDialog, scroll for this same reason).
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ArkivSurface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The old row's own heading, restored: name, version and the full status sentence together.
            Text("${model.nameLine} — ${pluginStatusText(plugin.status)}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            // The full host list, wrapping freely: the card's own line is capped and may cut it.
            Text(
                pluginConsentHostLine(address = plugin.record.address, hostsLabel = plugin.hosts.labels.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
            )
            for (action in actions) {
                when (action) {
                    TvInstalledAction.TOGGLE -> TvCompactAction(
                        label = if (plugin.isUsable) "Desactivar $name" else "Activar $name",
                        modifier = modifierFor(action),
                    ) { vm.setEnabled(plugin.id, !plugin.isUsable); onDismiss() }
                    TvInstalledAction.CONFIGURE -> TvCompactAction(label = "Configurar $name", modifier = modifierFor(action)) {
                        vm.openSettings(plugin.id); onDismiss()
                    }
                    TvInstalledAction.UPDATE -> TvCompactAction(
                        label = if (plugin.status == PluginStatus.UPDATE_PENDING) "Revisar actualización de $name" else "Buscar actualización de $name",
                        modifier = modifierFor(action),
                    ) { vm.checkUpdate(plugin.id); onDismiss() }
                    TvInstalledAction.FORGET_REJECTIONS -> TvCompactAction(label = "Olvidar rechazos de host de $name", modifier = modifierFor(action)) {
                        vm.forgetHostRejections(plugin.id); onDismiss()
                    }
                    TvInstalledAction.UNINSTALL -> TvCompactAction(label = "Desinstalar $name", modifier = modifierFor(action)) {
                        vm.askUninstall(plugin); onDismiss()
                    }
                    TvInstalledAction.CLOSE -> TvCompactAction(label = "Cerrar", modifier = modifierFor(action), onClick = onDismiss)
                }
            }
        }
    }
}

/** One row of [TvInstalledActionsDialog], in the order they are shown. */
internal enum class TvInstalledAction { TOGGLE, CONFIGURE, UPDATE, FORGET_REJECTIONS, UNINSTALL, CLOSE }

/**
 * The rows [TvInstalledActionsDialog] shows: the switch's own action only when the switch can move
 * ([InstalledCardModel.switchEnabled]), Configurar only with settings, "Olvidar rechazos de host" only
 * when there are some; "Buscar actualización", "Desinstalar" and "Cerrar" always.
 */
internal fun tvInstalledActions(hasSwitch: Boolean, hasSettings: Boolean, hasRejections: Boolean): List<TvInstalledAction> = buildList {
    if (hasSwitch) add(TvInstalledAction.TOGGLE)
    if (hasSettings) add(TvInstalledAction.CONFIGURE)
    add(TvInstalledAction.UPDATE)
    if (hasRejections) add(TvInstalledAction.FORGET_REJECTIONS)
    add(TvInstalledAction.UNINSTALL)
    add(TvInstalledAction.CLOSE)
}

/**
 * Where the D-pad focus starts when the dialog opens: "Cerrar", the one row that changes nothing --
 * the same rule as the consent and uninstall sheets, which open on "Cancelar". It used to be the
 * first row, "Desactivar {name}": one more OK after the one that opened the menu
 * switched the plugin off.
 */
internal fun tvInstalledActionsInitialFocus(actions: List<TvInstalledAction>): TvInstalledAction =
    TvInstalledAction.CLOSE.takeIf { it in actions } ?: actions.first()
