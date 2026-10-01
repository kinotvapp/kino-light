package com.arkiv.player.ui.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.alpha
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
import coil.compose.AsyncImage
import com.arkiv.player.data.plugin.catalog.CatalogArt
import java.io.File
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.tv.cardStatus
import com.arkiv.player.ui.tv.catalogRowLabel

/** Corner radius shared by the card and its tile. */
private val CARD_CORNER = 12.dp

/** Side of the plugin's app-style icon at the top of a card. */
private val APP_ICON_SIZE = 56.dp

/** The tonal button's container: the brand red at low strength, so it reads as this card's action without shouting. */
private val ACTION_CONTAINER = ArkivRed.copy(alpha = 0.30f)

/** The button of an installed plugin that needs nothing: a quiet, disabled-looking "Instalado". */
private val INSTALLED_CONTAINER = Color.White.copy(alpha = 0.08f)

/**
 * One recommended plugin as a card of the phone's Plugins screen (Recomendados tab): a 16:9 tile in the plugin's own
 * colour with its icon (or, while it has none, the first letter of its name), then its name, what it does,
 * up to two tags and one full-width button ([cardActionLabel]). The tap goes to [onAction] only through
 * that button; when the plugin needs nothing the button reads "Instalado" and does nothing.
 *
 * Every card in a line of the grid ends at the same height, whatever its text and however large the
 * person's font: the name is one line, the description exactly [cardDescriptionLines], the tags one line
 * and the status [cardStatusLines], all cut with an ellipsis, and the button sits under all of that in the
 * normal flow, so a long text or a big font makes the card taller for everyone instead of pushing the
 * button out of it. The one block that is not always there is the status (why the button says Activar,
 * Configurar or Instalar again); [reserveStatusLine] leaves room for it in a card that has none, so the
 * line ends at the same height (see [com.arkiv.player.ui.tv.gridLinesWithStatus]).
 *
 * [enabled] is false while the screen is busy with another install or check: the button is then dimmed
 * and ignores taps, as the row it replaces did. [modifier] is applied to the card.
 */
@Composable
fun PluginCard(
    row: CatalogRow,
    art: CatalogArt?,
    modifier: Modifier = Modifier,
    reserveStatusLine: Boolean = false,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    val entry = row.entry
    val action = catalogActionOf(row)
    val status = cardStatus(row)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CARD_CORNER),
        colors = CardDefaults.cardColors(containerColor = ArkivSurface),
    ) {
        PluginCardSurface(name = entry.name, iconFile = art?.iconFile, tileColorArgb = tileColor(art), pill = cardPill(row)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // titleSmall, not titleMedium: a card is about 150 dp wide inside, and "Internet Archive" has to fit on one line.
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // minLines as well as maxLines: a one-line description still takes two, so every card is as tall as the next.
            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                minLines = cardDescriptionLines(),
                maxLines = cardDescriptionLines(),
                overflow = TextOverflow.Ellipsis,
            )
            CardTags(listOfNotNull(cardSignedTag(row)) + cardTags(entry.tags))
            if (status != null) {
                Text(
                    cardStatusLabel(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivRed,
                    maxLines = cardStatusLines(),
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (reserveStatusLine) {
                // Blank space only: a screen reader must not stop on it. As tall as the status it stands in for.
                Text(
                    " ",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = cardStatusLines(),
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Spacer(Modifier.height(4.dp))
            CardAction(name = entry.name, action = action, enabled = enabled, onClick = onAction)
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
 * is centred in what is left, shrunk to fit it ([tileArtSize]): the tile is only ~89 dp tall on a phone, so
 * a pill drawn over a fixed-size icon would cover part of it. Without the pill the art has the whole tile.
 * The pill is one line with an ellipsis, so a big font shortens it instead of growing the tile out of 16:9.
 *
 * Deferred cleanup: this is the TV card's tile ([com.arkiv.player.ui.tv.TvPluginCard]) with the phone's
 * sizes; once the TV pass is done both can share one composable.
 *
 * Internal, not private: [InstalledPluginCard] (the Instalados tab) draws the very same tile for an
 * installed plugin, never with a [pill] (pills are a Recomendados-only thing); it passes
 * [iconFile] and [tileColorArgb] straight from its own model ([installedCardModel]) instead of a catalog
 * [CatalogArt], so its own icon and colour (not just the catalog's) can win.
 */
@Composable
internal fun PluginCardSurface(name: String, iconFile: File?, tileColorArgb: Long, pill: String?, body: @Composable () -> Unit) {
    var iconFailed by remember(iconFile) { mutableStateOf(false) }
    val cover = iconFile != null && !iconFailed
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(ArkivSurface)
            .background(Brush.verticalGradient(listOf(Color(tileColorArgb).copy(alpha = 0.35f), Color.Transparent))),
    ) {
        // The plugin's icon like an app icon, on its own colour, with the pill at the other end.
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(APP_ICON_SIZE).clip(RoundedCornerShape(14.dp)).background(Color(tileColorArgb)),
                contentAlignment = Alignment.Center,
            ) {
                if (cover) {
                    // Fit, never Crop: a logo must show whole.
                    AsyncImage(
                        model = iconFile,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onError = { iconFailed = true },
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                } else {
                    // Decoration: the card's name is read right below, so a screen reader must not say the letter first.
                    Text(
                        text = cardInitial(name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color(onTileColor(tileColorArgb)),
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
            if (pill != null) {
                // Solid, not the translucent MetaChip: it sits on any colour.
                Text(
                    text = pill,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(ArkivRed)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        body()
    }
}

/**
 * The tag chips, always one line tall: a second chip that does not fit at the person's font size is left
 * out instead of wrapping under the first, and a card with no tags keeps the line (an invisible chip of
 * the same height), so it is as tall as the card next to it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardTags(tags: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        maxLines = 1,
    ) {
        if (tags.isEmpty()) {
            TagChip(" ", modifier = Modifier.alpha(0f).clearAndSetSemantics { })
        } else {
            tags.forEach { TagChip(it) }
        }
    }
}

/** The look of [com.arkiv.player.ui.catalog.MetaChip], but one line long: a very long tag ends in an ellipsis instead of growing the card. */
@Composable
private fun TagChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(ArkivTextSecondary.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The card's only button: at least 48 dp tall and as wide as the card. Its accessibility description is the
 * same sentence the old row's button had ("Instalar Xuper", "Xuper: instalado"), because the words on it
 * do not say which plugin it acts on.
 */
@Composable
private fun CardAction(name: String, action: CatalogAction, enabled: Boolean, onClick: () -> Unit) {
    val installed = action == CatalogAction.INSTALLED
    FilledTonalButton(
        onClick = onClick,
        // "Instalado" is never tappable: the disabled look is the point.
        enabled = enabled && !installed,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = ACTION_CONTAINER,
            contentColor = Color.White,
            disabledContainerColor = INSTALLED_CONTAINER,
            disabledContentColor = ArkivTextSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = catalogRowLabel(action, name) },
    ) {
        if (installed) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(cardActionLabel(action), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
