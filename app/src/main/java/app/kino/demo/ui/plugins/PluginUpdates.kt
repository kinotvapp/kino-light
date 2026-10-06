package app.kino.demo.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.kino.demo.data.AppliedUpdate
import app.kino.demo.data.WaitingUpdate
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurface
import app.kino.demo.ui.theme.KinoTextSecondary

/** Every text of the plugin-updates bell and its sheet, phone and TV. */
internal object PluginUpdatesCopy {
    const val TITLE = "Novedades de tus plugins"
    const val WAITING = "Esperan tu aprobación"
    const val UPDATED = "Se actualizaron"
    const val REVIEW = "Revisar"
    const val EMPTY = "No hay novedades de tus plugins por ahora."
    const val CLOSE = "Cerrar"
}

/** "v1.2.0 → v1.3.0". */
internal fun versionLine(from: String, to: String): String = "v$from → v$to"

/** What a screen reader says about [n] updates waiting. */
internal fun pendingUpdatesLabel(n: Int): String =
    if (n == 1) "1 actualización de plugin por aprobar" else "$n actualizaciones de plugins por aprobar"

/** What the TV rail says beside Plugins while [n] updates wait. */
internal fun railBadgeLabel(n: Int): String = if (n == 1) "1 por aprobar" else "$n por aprobar"

/** An icon with [count] in a red badge on its corner (none at 0). */
@Composable
fun CountBadgedIcon(icon: ImageVector, label: String, count: Int, tint: Color = Color.White, size: Dp = 24.dp) {
    BadgedBox(
        badge = { if (count > 0) Badge(containerColor = KinoRed, contentColor = Color.White) { Text("$count") } },
        modifier = Modifier.semantics { contentDescription = if (count > 0) "$label, ${pendingUpdatesLabel(count)}" else label },
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

/** The Home bell's glyph with its count. */
@Composable
fun PluginUpdatesBellIcon(count: Int) = CountBadgedIcon(Icons.Default.Notifications, PluginUpdatesCopy.TITLE, count)

/**
 * The phone's bell sheet: the updates waiting for approval, each with "Revisar", then the last week's
 * applied ones ("Nombre v1.2.0 → v1.3.0" and the day), or one line when there is nothing; "Cerrar".
 */
@Composable
fun PluginUpdatesDialog(waiting: List<WaitingUpdate>, updated: List<AppliedUpdate>, onReview: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = KinoSurface, modifier = Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(PluginUpdatesCopy.TITLE, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (waiting.isEmpty() && updated.isEmpty()) {
                        Text(PluginUpdatesCopy.EMPTY, style = MaterialTheme.typography.bodyLarge, color = KinoTextSecondary)
                    }
                    if (waiting.isNotEmpty()) {
                        SectionTitle(PluginUpdatesCopy.WAITING)
                        waiting.forEach { w ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(w.name, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                                    Text(versionLine(w.currentVersion, w.pendingVersion), style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
                                }
                                TextButton(onClick = { onReview(w.id) }) { Text(PluginUpdatesCopy.REVIEW, color = KinoRed) }
                            }
                        }
                    }
                    if (updated.isNotEmpty()) {
                        SectionTitle(PluginUpdatesCopy.UPDATED)
                        updated.forEach { u ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${u.name} ${versionLine(u.fromVersion, u.toVersion)}", style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.weight(1f))
                                Text(u.day, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = KinoRed, contentColor = Color.White)) {
                        Text(PluginUpdatesCopy.CLOSE)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = KinoTextSecondary, fontWeight = FontWeight.SemiBold)
}
