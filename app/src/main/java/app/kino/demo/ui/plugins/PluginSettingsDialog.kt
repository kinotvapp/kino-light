package app.kino.demo.ui.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.kino.demo.data.DemoSession
import app.kino.demo.data.DemoSource
import app.kino.demo.data.demoPluginLog
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurface
import app.kino.demo.ui.theme.KinoTextSecondary

/** The switch's label, in every plugin's settings. */
internal const val DEBUG_SWITCH_LABEL = "Modo debug"

/** The line under the "Modo debug" switch. */
internal const val DEBUG_SWITCH_LINE =
    "Muestra los errores de este plugin en pantalla y guarda un registro que puedes compartir con su autor."

/** The Registro's title for [name]. */
internal fun registroTitle(name: String): String = "Registro de $name"

/**
 * An installed plugin's settings on the phone: "Activo", "Modo debug" with its line and, while it is
 * on, "Ver registro", which turns the dialog into the plugin's Registro (its last events, "Copiar
 * registro", "Compartir registro" and "Volver").
 */
@Composable
fun PluginSettingsDialog(plugin: DemoSource, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showingLog by rememberSaveable(plugin.id) { mutableStateOf(false) }
    val debugOn = DemoSession.debug[plugin.id] == true

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KinoSurface,
        titleContentColor = Color.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (showingLog) registroTitle(plugin.name) else "${plugin.name} ${plugin.version}", modifier = Modifier.weight(1f, fill = false))
                if (!showingLog) PluginKindBadge(plugin.kind)
            }
        },
        text = {
            if (showingLog) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .background(KinoBlack, RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        demoPluginLog(plugin).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color.White)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { fullAppOnly(context) }) { Text("Copiar registro") }
                        OutlinedButton(onClick = { fullAppOnly(context) }) { Text("Compartir registro") }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Se conecta a: ${plugin.hosts}", style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
                    SwitchRow("Activo", checked = DemoSession.enabled[plugin.id] == true) { DemoSession.enabled[plugin.id] = it }
                    SwitchRow(DEBUG_SWITCH_LABEL, checked = debugOn) { DemoSession.debug[plugin.id] = it }
                    Text(DEBUG_SWITCH_LINE, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
                    if (debugOn) OutlinedButton(onClick = { showingLog = true }) { Text("Ver registro") }
                    TextButton(onClick = { fullAppOnly(context) }) { Text("Desinstalar", color = KinoRed) }
                }
            }
        },
        confirmButton = {
            if (showingLog) {
                TextButton(onClick = { showingLog = false }) { Text("Volver") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
