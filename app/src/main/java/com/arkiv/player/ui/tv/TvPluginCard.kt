package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
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
import com.arkiv.player.ui.plugin.cardPill
import com.arkiv.player.ui.plugin.cardSignedTag
import com.arkiv.player.ui.plugin.withSignedTag
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

/** Side of the plugin's app-style icon at the top of a card. */
private val APP_ICON_SIZE = 72.dp

/** Height of the placeholder initial, in dp (not sp: it must not grow with the font scale, see [CardTile]). */
private val INITIAL_SIZE = 36.dp

/** Shape of the low strip of a [compact] card. */
private const val COMPACT_TILE_RATIO = 3.5f

/** Side of the icon (or height of the initial) of a compact tile, which has the art beside its pill instead of under it. */
private val COMPACT_ART_SIZE = 32.dp

/**
 * One recommended (or community) plugin as a card of the Plugins screen's Recomendados grid, drawn exactly like
 * an Instalados card ([TvInstalledPluginCard]: the plugin's app-style icon on a gradient tinted with its own
 * colour, the name, a status line, grey text under it) but without the gear: its status line is the one
 * thing OK will do ([cardActionLabel]) or "Instalado", plus "Firmado" for a signed plugin ([cardSignedTag]),
 * and the grey text is what it does. The whole card is the focus target; an installed plugin
 * that needs nothing is still focusable, so the grid is walked one card at a time, and its click does
 * nothing.
 *
 * The card always has the same height: the name is one line, the description exactly
 * [cardDescriptionLines] and the action one, all cut with an ellipsis, so a long text or a large font
 * cannot push the action out of it. The one line that varies is the plugin's status; [reserveStatusLine]
 * leaves room for it in the cards that have none, so the cards of a grid line end at the same height
 * (see [gridLinesWithStatus]).
 *
 * [compact] is for a grid that has to show many cards on one screen ("Elige tus fuentes" and Ajustes ▸
 * Plugins ▸ Recomendados, ~150 dp wide each, see [tvPickerColumns]): the same texts and actions, with a
 * much lower tile ([COMPACT_TILE_RATIO] instead of 16:9, the art beside the pill: [CompactCardTile]), a
 * smaller name, ONE line of description ([cardDescriptionLines]) and tighter padding.
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
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val entry = row.entry
    val action = catalogActionOf(row)
    val label = listOfNotNull(catalogRowLabel(action, entry.name), cardSignedTag(row)).joinToString(". ")
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
        Box(Modifier.clearAndSetSemantics { }) {
          PluginCardSurface(name = entry.name, iconFile = art?.iconFile, tileColorArgb = tileColor(art), pill = cardPill(row), compact = compact) {
            CardTexts(
                name = entry.name,
                description = entry.description,
                action = action,
                status = cardStatus(row),
                signedTag = cardSignedTag(row),
                reserveStatusLine = reserveStatusLine,
                compact = compact,
            )
          }
        }
    }
}

/**
 * The 16:9 top of the card. The icon is drawn when the art has one AND it can be decoded: the repository
 * only checks that the file starts with the PNG signature, so a corrupt file reaches Coil, which reports
 * an error and the initial takes its place. The tile is never left blank.
 *
 * The pill ([cardPill]: "Lo que ya usabas" or "De la comunidad") takes a row of its own at the top of the tile and the icon (or the initial)
 * is centred in what is left, shrunk to fit it ([tileArtSize]) instead of running under the pill. Without
 * the pill the art has the whole tile. The pill is one line with an ellipsis, so a big font shortens it
 * instead of growing the tile out of 16:9.
 *
 * Internal, not private: [TvInstalledPluginCard] (the Instalados tab) draws the very same tile for an
 * installed plugin, never with a [pill] (pills are a Recomendados-only thing); it passes
 * [iconFile] and [tileColorArgb] straight from its own model ([com.arkiv.player.ui.plugin.installedCardModel])
 * instead of a catalog [CatalogArt], so its own icon and colour (not just the catalog's) can win.
 */
@Composable
internal fun PluginCardSurface(
    name: String, iconFile: File?, tileColorArgb: Long, pill: String?, compact: Boolean = false, body: @Composable () -> Unit,
) {
    var iconFailed by remember(iconFile) { mutableStateOf(false) }
    val cover = iconFile != null && !iconFailed
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(ArkivSurfaceHigh)
            .background(Brush.verticalGradient(listOf(Color(tileColorArgb).copy(alpha = 0.35f), Color.Transparent))),
    ) {
        if (compact) {
            CompactCardTile(name, iconFile.takeIf { cover }, { iconFailed = true }, tileColorArgb, pill)
        } else {
            // The plugin's icon like an app icon, on its own colour, with the pill at the other end.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    Modifier.size(APP_ICON_SIZE).clip(RoundedCornerShape(16.dp)).background(Color(tileColorArgb)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cover) {
                        // Fit, never Crop: a logo must show whole.
                        AsyncImage(
                            model = iconFile,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            onError = { iconFailed = true },
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )
                    } else {
                        TileArt(name = name, tileColorArgb = tileColorArgb, letterSize = INITIAL_SIZE.value)
                    }
                }
                if (pill != null) {
                    Text(
                        text = pill,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(ArkivRed)
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
            }
        }
        body()
    }
}

/** The low strip of a [compact][TvPluginCard] card: the icon (or the initial) at its start with the pill beside it. */
@Composable
private fun CompactCardTile(name: String, icon: File?, onIconError: () -> Unit, tileColorArgb: Long, pill: String?) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().aspectRatio(COMPACT_TILE_RATIO).background(Color(tileColorArgb)),
        contentAlignment = if (pill == null) Alignment.Center else Alignment.CenterStart,
    ) {
        val artSize = tileArtSize(maxHeight.value, COMPACT_ART_SIZE.value)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 6.dp),
        ) {
            if (icon != null) {
                if (artSize > 0f) {
                    AsyncImage(model = icon, contentDescription = null, contentScale = ContentScale.Fit, onError = { onIconError() }, modifier = Modifier.size(artSize.dp))
                }
            } else {
                TileArt(name = name, tileColorArgb = tileColorArgb, letterSize = artSize)
            }
            if (pill != null) {
                Text(
                    text = pill,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ArkivRed)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** The plugin's initial at [letterSize] dp (converted to sp, so it does not grow with the font scale); nothing at a size of zero. */
@Composable
private fun TileArt(name: String, tileColorArgb: Long, letterSize: Float) {
    val letter = with(LocalDensity.current) { letterSize.dp.toSp() }
    if (letter.value > 0f) {
        Text(
            text = cardInitial(name),
            style = MaterialTheme.typography.headlineLarge,
            fontSize = letter,
            lineHeight = letter,
            fontWeight = FontWeight.Black,
            color = Color(onTileColor(tileColorArgb)),
        )
    }
}

/**
 * The texts under the tile. The full-size card (Ajustes > Plugins: Recomendados and "De la comunidad") is laid
 * out like an Instalados card ([TvInstalledPluginCard]): the name, then the status line -- what OK does or
 * "Instalado", with " · Firmado" for a signed plugin ([withSignedTag]) -- then the description in the grey of
 * the hosts line, then the plugin's own status when it explains the action. A [compact] card ("Elige tus
 * fuentes") keeps the action last, under a one-line description.
 */
@Composable
private fun CardTexts(
    name: String,
    description: String,
    action: CatalogAction,
    status: PluginStatus?,
    signedTag: String?,
    reserveStatusLine: Boolean,
    compact: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 4.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 2.dp),
    ) {
        if (compact) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            // The Instalados card's own title style.
            Text(name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ActionLine(action, signedTag, compact = false)
        }
        // minLines as well as maxLines: a shorter description still takes its lines, so every card is as tall as the next.
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
            minLines = cardDescriptionLines(compact),
            maxLines = cardDescriptionLines(compact),
            overflow = TextOverflow.Ellipsis,
        )
        // Why the action says Activar / Configurar / Instalar again, in the short words of a card (see cardStatusLabel).
        if (status != null) {
            Text(cardStatusLabel(status), style = MaterialTheme.typography.bodySmall, color = ArkivRed, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else if (reserveStatusLine) {
            Text(" ", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        if (compact) ActionLine(action, signedTag, compact = true)
    }
}

/**
 * What OK does on this card ([cardActionLabel]) in red, or a grey "Instalado" with a check for a plugin that
 * needs nothing, followed by " · Firmado" for a signed one. On the full-size card it is the status line under
 * the name, at the Instalados status line's size; on a [compact] one, the last line.
 */
@Composable
private fun ActionLine(action: CatalogAction, signedTag: String?, compact: Boolean) {
    val style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall
    val label = withSignedTag(cardActionLabel(action), signedTag)
    if (action == CatalogAction.INSTALLED) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = ArkivTextSecondary, modifier = Modifier.size(14.dp))
            Text(label, style = style, color = ArkivTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Text(label, style = style, fontWeight = FontWeight.Medium, color = ArkivRed, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
