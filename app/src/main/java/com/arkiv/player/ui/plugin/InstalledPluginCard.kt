package com.arkiv.player.ui.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Corner radius shared with [PluginCard]. */
private val CARD_CORNER = 12.dp

/** The least a touch target measures: the "Gestionar" button and every action of its sheet. */
private val MIN_TARGET = 48.dp

/** The tonal button's container: the brand red at low strength, as [PluginCard]'s own action. */
private val ACTION_CONTAINER = ArkivRed.copy(alpha = 0.30f)

/**
 * One installed plugin as a card of the phone's Instalados tab: the same anatomy as [PluginCard] (a 16:9
 * tile in the plugin's colour with its icon or initial, its name, one status line) plus what only an
 * installed plugin has -- the hosts it may reach, an on/off switch and a "Gestionar" button that opens
 * [InstalledActionsSheet] with every action the row it replaces had (Configurar, Buscar actualización,
 * Desinstalar), plus "Olvidar rechazos de host" when `plugin.record.rejectedHosts` isn't empty, calling
 * the very same [PluginsViewModel] functions and opening the very same dialogs
 * (consent, uninstall, Configurar) those always did.
 *
 * [message] is this plugin's own line (see [rowMessagePluginId]); [reserveMessageLines] leaves the same room
 * for it in a card that has none, so every card of the same grid line ends at the same height (see
 * [installedGridLinesWithMessage]). [busy] disables the sheet's actions, as it did the row's buttons; the
 * switch stays togglable regardless (it always did, see [InstalledCardModel.switchEnabled]).
 *
 * [liveNotice] is a live plugin's "Lista recortada: …" line (`LiveCatalog.noticeFor`), under the status;
 * [reserveNoticeLines] leaves its room in a card without one on the same grid line
 * ([installedGridLinesReserving]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstalledPluginCard(
    plugin: InstalledPlugin,
    art: CatalogArt?,
    busy: Boolean,
    message: String?,
    reserveMessageLines: Boolean,
    vm: PluginsViewModel,
    modifier: Modifier = Modifier,
    liveNotice: String? = null,
    reserveNoticeLines: Boolean = false,
) {
    val model = installedCardModel(plugin, art)
    var sheetOpen by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CARD_CORNER),
        colors = CardDefaults.cardColors(containerColor = ArkivSurface),
    ) {
        PluginCardSurface(name = model.name, iconFile = model.iconFile, tileColorArgb = model.tileColorArgb, pill = model.pill) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                model.nameLine,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                model.statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (model.statusIsProblem) ArkivRed else ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (liveNotice != null || reserveNoticeLines) {
                Text(
                    liveNotice ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                    minLines = installedMessageLines(),
                    maxLines = installedMessageLines(),
                    overflow = TextOverflow.Ellipsis,
                    // The blank placeholder is room only: a screen reader must not stop on it.
                    modifier = if (liveNotice == null) Modifier.clearAndSetSemantics { } else Modifier,
                )
            }
            Text(
                pluginConsentHostLine(address = plugin.record.address, hostsLabel = plugin.hosts.labels.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                minLines = installedHostsLines(),
                maxLines = installedHostsLines(),
                overflow = TextOverflow.Ellipsis,
            )
            if (message != null) {
                // minLines as well as maxLines: a one-line message must reserve the same height as a
                // two-line one, or its line's neighbour (the blank placeholder below) ends up taller.
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    minLines = installedMessageLines(),
                    maxLines = installedMessageLines(),
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (reserveMessageLines) {
                // Blank space only: a screen reader must not stop on it. As tall as installedMessageLines().
                Text(
                    " ",
                    style = MaterialTheme.typography.bodySmall,
                    minLines = installedMessageLines(),
                    maxLines = installedMessageLines(),
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = model.switchChecked,
                    enabled = model.switchEnabled,
                    onCheckedChange = { on -> vm.setEnabled(plugin.id, on) },
                    modifier = Modifier.semantics {
                        contentDescription = if (model.switchChecked) "Desactivar ${model.name}" else "Activar ${model.name}"
                    },
                )
                FilledTonalButton(
                    onClick = { sheetOpen = true },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = ACTION_CONTAINER, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).heightIn(min = MIN_TARGET),
                ) {
                    Text("Gestionar", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        }
    }
    if (sheetOpen) {
        InstalledActionsSheet(plugin = plugin, model = model, busy = busy, vm = vm, onDismiss = { sheetOpen = false })
    }
}

/**
 * "Gestionar"'s sheet: the plugin's full host list (the card's own line is capped to
 * [installedHostsLines] and may cut it), then Configurar (only when [InstalledCardModel.hasSettings]), Buscar
 * actualización (its label switches to "Revisar actualización" the same way the row's did, once an update is
 * pending consent), Olvidar rechazos de host (only when `plugin.record.rejectedHosts` isn't empty), Quitar
 * permiso de video amplio (only when `plugin.record.anyVideoHost`, under the line saying so) and
 * Desinstalar, each calling the same [PluginsViewModel] function the row's own button
 * did and then closing the sheet, so whatever dialog that call opens (the consent sheet, "¿Desinstalar…?",
 * Configurar) shows over the tab, not stacked under this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledActionsSheet(
    plugin: InstalledPlugin,
    model: InstalledCardModel,
    busy: Boolean,
    vm: PluginsViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                model.nameLine,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
            // The full list, wrapping freely: the card's own line is capped and may cut it.
            Text(
                pluginConsentHostLine(address = plugin.record.address, hostsLabel = plugin.hosts.labels.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
            // The broad video permission, when granted: what it lets the plugin do; its revoke action is below.
            installedAnyVideoHostLine(plugin.record)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                )
            }
            if (model.hasSettings) {
                SheetAction("Configurar", enabled = !busy) { vm.openSettings(plugin.id); onDismiss() }
            }
            SheetAction(
                if (plugin.status == PluginStatus.UPDATE_PENDING) "Revisar actualización" else "Buscar actualización",
                enabled = !busy,
            ) { vm.checkUpdate(plugin.id); onDismiss() }
            if (plugin.record.rejectedHosts.isNotEmpty()) {
                SheetAction("Olvidar rechazos de host", enabled = !busy) { vm.forgetHostRejections(plugin.id); onDismiss() }
            }
            if (plugin.record.anyVideoHost) {
                SheetAction("Quitar permiso de video amplio", enabled = !busy) { vm.revokeAnyVideoHost(plugin.id); onDismiss() }
            }
            SheetAction("Desinstalar", color = ArkivRed, enabled = !busy) { vm.askUninstall(plugin); onDismiss() }
        }
    }
}

/**
 * One row of the sheet: the same look [com.arkiv.player.ui.library.LibraryScreen]'s own menu sheet uses,
 * dimmed while [enabled] is false. `role = Role.Button` on the click so TalkBack reads it as a button, not
 * as plain clickable text.
 */
@Composable
private fun SheetAction(text: String, color: Color = Color.Unspecified, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) color else ArkivTextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TARGET)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
