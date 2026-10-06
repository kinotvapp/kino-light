package app.kino.tv.ui.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kino.tv.data.KinoSource
import app.kino.tv.ui.phone.cardInitial
import app.kino.tv.ui.theme.KinoRed
import app.kino.tv.ui.theme.KinoSurface
import app.kino.tv.ui.theme.KinoSurfaceHigh
import app.kino.tv.ui.theme.KinoTextSecondary

/** The picker's first row: no narrowing. */
internal const val ALL_SOURCES_LABEL = "Todas las fuentes"

/** The button that opens the picker, phone and TV. */
internal const val SEARCH_BY_SOURCE_LABEL = "Buscar por fuente"

/** What the chip of a chosen source says. */
internal fun scopeChipLabel(plugin: KinoSource): String = "En: ${plugin.name}"

/** A source as a small tile: its initial on its colour; with no [plugin] ("Todas las fuentes") a search glyph. */
@Composable
internal fun SearchScopeIcon(plugin: KinoSource?, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(plugin?.let { Color(it.color) } ?: KinoSurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (plugin == null) {
            Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
        } else {
            Text(cardInitial(plugin.name), fontWeight = FontWeight.Black, color = Color.White, fontSize = (size.value * 0.45f).sp)
        }
    }
}

/**
 * The phone's "Buscar por fuente" picker: "Todas las fuentes", then every plugin that searches, each
 * with its format badge. The chosen one is red and carries a check ("Elegida").
 */
@Composable
fun SearchScopeDialog(scopes: List<KinoSource>, current: KinoSource?, onPick: (KinoSource?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KinoSurface,
        titleContentColor = Color.White,
        title = { Text(SEARCH_BY_SOURCE_LABEL) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(listOf<KinoSource?>(null) + scopes, key = { it?.id ?: "" }) { scope ->
                    val on = scope?.id == current?.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(scope) }.padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SearchScopeIcon(scope, 36.dp)
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                scope?.name ?: ALL_SOURCES_LABEL,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (on) KinoRed else Color.White,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (scope != null) PluginKindBadge(scope.kind)
                        }
                        if (on) Icon(Icons.Default.Check, contentDescription = "Elegida", tint = KinoRed)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar", color = KinoTextSecondary) } },
    )
}

/** The removable "En: <plugin>" chip next to the search field: tapping it goes back to every source. */
@Composable
fun SearchScopeChip(plugin: KinoSource, onClear: () -> Unit, modifier: Modifier = Modifier) {
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(scopeChipLabel(plugin), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { SearchScopeIcon(plugin, InputChipDefaults.AvatarSize) },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Buscar en todas las fuentes", modifier = Modifier.size(16.dp)) },
        modifier = modifier,
    )
}
