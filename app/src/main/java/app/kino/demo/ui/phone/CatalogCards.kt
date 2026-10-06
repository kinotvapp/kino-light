package app.kino.demo.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kino.demo.data.DemoSource
import app.kino.demo.data.PluginKind
import app.kino.demo.ui.plugins.COMMUNITY_TITLE
import app.kino.demo.ui.plugins.FREE_LIVE_COLOR
import app.kino.demo.ui.plugins.FREE_LIVE_NOTE
import app.kino.demo.ui.plugins.PluginKindBadge
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurface
import app.kino.demo.ui.theme.KinoTextSecondary

private val CARD_CORNER = 12.dp
private val APP_ICON_SIZE = 56.dp

/** The card's action: the brand red at low strength, so it reads as an action without shouting. */
internal val ACTION_CONTAINER = KinoRed.copy(alpha = 0.30f)
private val INSTALLED_CONTAINER = Color.White.copy(alpha = 0.08f)

/** The first letter or digit of [name], upper-cased, drawn on a tile with no icon. */
internal fun cardInitial(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/**
 * The top of a plugin card: an app-style icon tile in the plugin's colour (its initial) and, at the
 * other end, an optional pill; then [body] under a soft gradient of the same colour.
 */
@Composable
internal fun CardTile(name: String, color: Long, pill: String?, pillColor: Color = KinoRed, body: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(KinoSurface)
            .background(Brush.verticalGradient(listOf(Color(color).copy(alpha = 0.35f), Color.Transparent))),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(APP_ICON_SIZE).clip(RoundedCornerShape(14.dp)).background(Color(color)),
                contentAlignment = Alignment.Center,
            ) {
                Text(cardInitial(name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Color.White)
            }
            if (pill != null) {
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
                        .background(pillColor)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        body()
    }
}

/** A card's name and, after it, its format badge (none for a Kino plugin); the name ellipsizes only when it must. */
@Composable
private fun NameWithBadge(name: String, kind: PluginKind) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        PluginKindBadge(kind)
    }
}

@Composable
private fun TagChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(KinoTextSecondary.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = KinoTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One catalog plugin as a card: tile (with "Gratis y legal" or "De la comunidad"), name and format
 * badge, two lines of description, tags and one full-width button ("Instalar", or a quiet disabled
 * "Instalado").
 */
@Composable
internal fun CatalogCard(plugin: DemoSource, installed: Boolean, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CARD_CORNER),
        colors = CardDefaults.cardColors(containerColor = KinoSurface),
    ) {
        CardTile(
            name = plugin.name,
            color = plugin.color,
            pill = when {
                plugin.freeLive -> FREE_LIVE_NOTE
                plugin.community -> COMMUNITY_TITLE
                else -> null
            },
            pillColor = if (plugin.freeLive) Color(FREE_LIVE_COLOR) else KinoRed,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NameWithBadge(plugin.name, plugin.kind)
                Text(
                    plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = KinoTextSecondary,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { plugin.tags.take(2).forEach { TagChip(it) } }
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(
                    onClick = onAction,
                    enabled = !installed,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = ACTION_CONTAINER,
                        contentColor = Color.White,
                        disabledContainerColor = INSTALLED_CONTAINER,
                        disabledContentColor = KinoTextSecondary,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (installed) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(if (installed) "Instalado" else "Instalar", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/**
 * One installed plugin as a card: tile, name, version and format badge, status, the hosts it may
 * reach, an on/off switch and "Gestionar".
 */
@Composable
internal fun InstalledCard(plugin: DemoSource, enabled: Boolean, onToggle: (Boolean) -> Unit, onManage: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CARD_CORNER),
        colors = CardDefaults.cardColors(containerColor = KinoSurface),
    ) {
        CardTile(name = plugin.name, color = plugin.color, pill = null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NameWithBadge("${plugin.name} ${plugin.version}", plugin.kind)
                Text(
                    if (enabled) "Activo" else "Desactivado",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) KinoTextSecondary else KinoRed,
                    maxLines = 1,
                )
                Text(
                    "Se conecta a: ${plugin.hosts}",
                    style = MaterialTheme.typography.bodySmall,
                    color = KinoTextSecondary,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = onToggle)
                    Spacer(Modifier.weight(1f))
                }
                FilledTonalButton(
                    onClick = onManage,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = ACTION_CONTAINER, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Gestionar", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
