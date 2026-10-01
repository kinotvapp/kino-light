package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
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
import com.arkiv.player.ui.plugin.InstalledStatusLine
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.ui.LocalReducedEffects
import com.arkiv.player.ui.cardFocusScale
import com.arkiv.player.ui.plugin.InstalledCardModel
import com.arkiv.player.ui.plugin.installedCardModel
import com.arkiv.player.ui.plugin.installedHostsLines
import com.arkiv.player.ui.plugin.installedMessageLines
import com.arkiv.player.ui.plugin.pluginConsentHostLine
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * One installed plugin as a card of the TV's Instalados grid: the same tile/name/status anatomy as
 * [TvPluginCard], plus the hosts it may reach (a recommended card never shows hosts, only an installed
 * plugin's own). The whole card is the focus target; OK opens the actions dialog ([TvInstalledActionsDialog]),
 * which is where every management action lives on the TV (there is no separate switch on the card face, as
 * there never was on the deleted `TvInstalledPluginRows`' own "activado/desactivado" row). A small gear in the
 * tile's top-right corner is only a visual hint that the card opens that dialog -- it holds no focus of its
 * own, so it adds nothing to the D-pad's traversal. [message] is this plugin's own
 * line (see [com.arkiv.player.ui.plugin.rowMessagePluginId]); [reserveMessageLines] leaves the same room for
 * it in a card that has none, so every card of the same grid line ends at the same height. [liveNotice] is a
 * live plugin's "Lista recortada: …" line (`LiveCatalog.noticeFor`), under the status, and
 * [reserveNoticeLines] its room in a card of the same grid line without one
 * ([com.arkiv.player.ui.plugin.installedGridLinesReserving]) -- the same as the phone's card.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvInstalledPluginCard(
    plugin: InstalledPlugin,
    art: CatalogArt?,
    message: String?,
    reserveMessageLines: Boolean,
    modifier: Modifier = Modifier,
    liveNotice: String? = null,
    reserveNoticeLines: Boolean = false,
    onClick: () -> Unit,
) {
    val model = installedCardModel(plugin, art)
    // The notice is drawn inside the cleared column below, so the card's description carries it.
    val label = listOfNotNull("${model.nameLine} — ${model.statusLabel}", model.signedTag, liveNotice).joinToString(". ")
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
        Box(Modifier.clearAndSetSemantics { }) {
            PluginCardSurface(name = model.name, iconFile = model.iconFile, tileColorArgb = model.tileColorArgb, pill = null) {
                CardTexts(plugin, model, message, reserveMessageLines, liveNotice, reserveNoticeLines)
            }
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp),
            )
        }
    }
}

@Composable
private fun CardTexts(
    plugin: InstalledPlugin,
    model: InstalledCardModel,
    message: String?,
    reserveMessageLines: Boolean,
    liveNotice: String?,
    reserveNoticeLines: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(model.nameLine, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        InstalledStatusLine(model)
        if (liveNotice != null || reserveNoticeLines) {
            Text(
                liveNotice ?: " ",
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                minLines = installedMessageLines(),
                maxLines = installedMessageLines(),
                overflow = TextOverflow.Ellipsis,
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
