package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import coil.compose.AsyncImage
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.catalog.CatalogArt
import java.io.File
import com.arkiv.player.ui.LocalReducedEffects
import com.arkiv.player.ui.cardFocusScale
import com.arkiv.player.ui.plugin.CatalogAction
import com.arkiv.player.ui.plugin.CatalogRow
import com.arkiv.player.ui.plugin.cardActionLabel
import com.arkiv.player.ui.plugin.cardDescriptionLines
import com.arkiv.player.ui.plugin.cardInitial
import com.arkiv.player.ui.plugin.cardStatusLabel
import com.arkiv.player.ui.plugin.catalogActionOf
import com.arkiv.player.ui.plugin.onTileColor
import com.arkiv.player.ui.plugin.tileArtSize
import com.arkiv.player.ui.plugin.tileColor
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Corner radius shared by the card and its tile; the tile is clipped to it so the art never pokes out of the top corners. */
private val CARD_CORNER = 12.dp

/** Side of the box the plugin's icon is drawn in (and decoded for: Coil samples the file down to it). */
private val ICON_SIZE = 96.dp

/** Height of the placeholder initial, in dp (not sp: it must not grow with the font scale, see [CardTile]). */
private val INITIAL_SIZE = 56.dp

/**
 * One recommended plugin as a card of the Plugins screen's Recomendados grid: a 16:9 tile in the plugin's own colour
 * with its icon (or, while it has none, the first letter of its name), then its name, what it does and
 * the one thing OK will do ([cardActionLabel]). The whole card is the focus target; an installed plugin
 * that needs nothing is still focusable, so the grid is walked one card at a time, and its click does
 * nothing.
 *
 * The card always has the same height: the name is one line, the description exactly
 * [cardDescriptionLines] and the action one, all cut with an ellipsis, so a long text or a large font
 * cannot push the action out of it. The one line that varies is the plugin's status; [reserveStatusLine]
 * leaves room for it in the cards that have none, so the cards of a grid line end at the same height
 * (see [gridLinesWithStatus]).
 *
 * [modifier] goes first in the chain: a `focusRequester` on it reaches the card's own focus target.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPluginCard(
    row: CatalogRow,
    art: CatalogArt?,
    modifier: Modifier = Modifier,
    reserveStatusLine: Boolean = false,
    onClick: () -> Unit,
) {
    val entry = row.entry
    val action = catalogActionOf(row)
    val label = catalogRowLabel(action, entry.name)
    Card(
        onClick = onClick,
        // The description of the whole card is the same sentence the row had ("Instalar Xuper"); what is
        // drawn inside is cleared from the accessibility tree so it is not read a second time.
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        scale = cardFocusScale(LocalReducedEffects.current),
        colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Column(Modifier.clearAndSetSemantics { }) {
            CardTile(name = entry.name, iconFile = art?.iconFile, tileColorArgb = tileColor(art), legacyDefault = entry.legacyDefault)
            CardTexts(
                name = entry.name,
                description = entry.description,
                action = action,
                status = cardStatus(row),
                reserveStatusLine = reserveStatusLine,
            )
        }
    }
}

/**
 * The 16:9 top of the card. The icon is drawn when the art has one AND it can be decoded: the repository
 * only checks that the file starts with the PNG signature, so a corrupt file reaches Coil, which reports
 * an error and the initial takes its place. The tile is never left blank.
 *
 * The "Lo que ya usabas" pill takes a row of its own at the top of the tile and the icon (or the initial)
 * is centred in what is left, shrunk to fit it ([tileArtSize]) instead of running under the pill. Without
 * the pill the art has the whole tile. The pill is one line with an ellipsis, so a big font shortens it
 * instead of growing the tile out of 16:9.
 *
 * Internal, not private: [TvInstalledPluginCard] (the Instalados tab) draws the very same tile for an
 * installed plugin, never with [legacyDefault] (that pill is a Recomendados-only thing); it passes
 * [iconFile] and [tileColorArgb] straight from its own model ([com.arkiv.player.ui.plugin.installedCardModel])
 * instead of a catalog [CatalogArt], so its own icon and colour (not just the catalog's) can win.
 */
@Composable
internal fun CardTile(name: String, iconFile: File?, tileColorArgb: Long, legacyDefault: Boolean) {
    val tile = tileColorArgb
    var iconFailed by remember(iconFile) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(topStart = CARD_CORNER, topEnd = CARD_CORNER))
            .background(Color(tile)),
    ) {
        if (legacyDefault) {
            Text(
                text = "Lo que ya usabas",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(ArkivRed)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (iconFile != null && !iconFailed) {
                val iconSize = tileArtSize(maxHeight.value, ICON_SIZE.value)
                if (iconSize > 0f) {
                    AsyncImage(
                        model = iconFile,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onError = { iconFailed = true },
                        modifier = Modifier.size(iconSize.dp),
                    )
                }
            } else {
                // The size is in dp, converted to sp, so the letter does not grow with the font scale and run under the pill.
                val letterSize = with(LocalDensity.current) { tileArtSize(maxHeight.value, INITIAL_SIZE.value).dp.toSp() }
                if (letterSize.value > 0f) {
                    Text(
                        text = cardInitial(name),
                        style = MaterialTheme.typography.headlineLarge,
                        fontSize = letterSize,
                        lineHeight = letterSize,
                        fontWeight = FontWeight.Black,
                        color = Color(onTileColor(tile)),
                    )
                }
            }
        }
    }
}

@Composable
private fun CardTexts(name: String, description: String, action: CatalogAction, status: PluginStatus?, reserveStatusLine: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // minLines as well as maxLines: a one-line description still takes two, so every card is as tall as the next.
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
            minLines = cardDescriptionLines(),
            maxLines = cardDescriptionLines(),
            overflow = TextOverflow.Ellipsis,
        )
        // Why the action says Activar / Configurar / Instalar again, in the short words of a card (see cardStatusLabel).
        if (status != null) {
            Text(cardStatusLabel(status), style = MaterialTheme.typography.bodySmall, color = ArkivRed, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else if (reserveStatusLine) {
            Text(" ", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        if (action == CatalogAction.INSTALLED) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = ArkivTextSecondary, modifier = Modifier.size(14.dp))
                Text(cardActionLabel(action), style = MaterialTheme.typography.labelLarge, color = ArkivTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(cardActionLabel(action), style = MaterialTheme.typography.labelLarge, color = ArkivRed, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The status a card writes under the description: only for an installed plugin whose button is not
 * "Instalado" (it is what explains why the button says Activar, Configurar or Instalar again). Null for a
 * plugin that is not installed and for one that needs nothing.
 */
internal fun cardStatus(row: CatalogRow): PluginStatus? =
    row.installed?.status?.takeIf { catalogActionOf(row) != CatalogAction.INSTALLED }

/**
 * For each row, whether its line of the grid has a status somewhere ([cardStatus]). The cards fill the
 * grid [columns] at a time from the first column, and a line is as tall as its tallest card, so a card
 * whose neighbour writes a status reserves that line too ([TvPluginCard]'s `reserveStatusLine`); a line
 * with no status reserves nothing.
 */
internal fun gridLinesWithStatus(rows: List<CatalogRow>, columns: Int): List<Boolean> {
    val withStatus = rows.map { cardStatus(it) != null }
    return withStatus.indices.map { index ->
        val lineStart = index / columns * columns
        (lineStart until minOf(lineStart + columns, withStatus.size)).any { withStatus[it] }
    }
}
