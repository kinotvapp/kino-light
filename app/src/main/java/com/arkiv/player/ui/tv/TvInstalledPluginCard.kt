package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.ui.LocalReducedEffects
import com.arkiv.player.ui.cardFocusScale
import com.arkiv.player.ui.plugin.InstalledCardModel
import com.arkiv.player.ui.plugin.installedCardModel
import com.arkiv.player.ui.plugin.installedHostsLines
import com.arkiv.player.ui.plugin.installedMessageLines
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * One installed plugin as a card of the TV's Instalados grid: the same tile/name/status anatomy as
 * [TvPluginCard], plus the hosts it may reach (a recommended card never shows hosts, only an installed
 * plugin's own). The whole card is the focus target; OK opens the actions dialog ([TvInstalledActionsDialog]),
 * which is where every management action lives on the TV (there is no separate switch on the card face, as
 * there never was on the deleted `TvInstalledPluginRows`' own "activado/desactivado" row). [message] is this plugin's own
 * line (see [com.arkiv.player.ui.plugin.rowMessagePluginId]); [reserveMessageLines] leaves the same room for
 * it in a card that has none, so every card of the same grid line ends at the same height.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvInstalledPluginCard(
    plugin: InstalledPlugin,
    art: CatalogArt?,
    message: String?,
    reserveMessageLines: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val model = installedCardModel(plugin, art)
    val label = "${model.nameLine} — ${model.statusLabel}"
    Card(
        onClick = onClick,
        // The description of the whole card is the heading the old row had; what is drawn inside is
        // cleared from the accessibility tree so it is not read a second time.
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        scale = cardFocusScale(LocalReducedEffects.current),
        colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Column(Modifier.clearAndSetSemantics { }) {
            CardTile(name = model.name, iconFile = model.iconFile, tileColorArgb = model.tileColorArgb, legacyDefault = false)
            CardTexts(plugin = plugin, model = model, message = message, reserveMessageLines = reserveMessageLines)
        }
    }
}

@Composable
private fun CardTexts(plugin: InstalledPlugin, model: InstalledCardModel, message: String?, reserveMessageLines: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(model.nameLine, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            model.statusLabel,
            style = MaterialTheme.typography.bodySmall,
            color = if (model.statusIsProblem) ArkivRed else ArkivTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Se conectará a: ${plugin.hosts.labels.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
            minLines = installedHostsLines(),
            maxLines = installedHostsLines(),
            overflow = TextOverflow.Ellipsis,
        )
        if (message != null) {
            // minLines as well as maxLines: a one-line message must reserve the same height as a two-line
            // one, or its line's neighbour (the blank placeholder below) ends up taller.
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                minLines = installedMessageLines(),
                maxLines = installedMessageLines(),
                overflow = TextOverflow.Ellipsis,
            )
        } else if (reserveMessageLines) {
            // Blank space only: a screen reader must not stop on it, as the phone's own placeholder.
            Text(
                " ",
                style = MaterialTheme.typography.bodySmall,
                minLines = installedMessageLines(),
                maxLines = installedMessageLines(),
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}
