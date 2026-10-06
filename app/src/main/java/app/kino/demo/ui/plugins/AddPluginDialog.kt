package app.kino.demo.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.kino.demo.data.PluginKind
import app.kino.demo.ui.components.KinoChip
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurface
import app.kino.demo.ui.theme.KinoTextSecondary
import kotlinx.coroutines.delay

/** The dialog's title, phone and TV. */
internal const val ADD_PLUGIN_TITLE = "Agregar un plugin"

/** The line under the type's instruction: consent comes before anything is installed. */
internal const val ADD_CONSENT_LINE = "Antes de instalar vas a ver con qué sitios se conecta."

/** How long "Agregar" reads "Revisando…" before it answers. */
internal const val ADD_CHECK_MS = 1_200L

/** The ONE instruction the dialog shows for [kind]. */
internal fun addInstruction(kind: PluginKind): String = when (kind) {
    PluginKind.KINO -> "Escribe usuario/repositorio de GitHub o pega la URL del manifest (kino-plugin.json)"
    PluginKind.NUVIO -> "Pega la URL del manifest del repositorio de Nuvio"
    PluginKind.STREMIO -> "Pega la URL del manifest.json del addon de Stremio (o de una colección de addons)"
}

/** The address field's label for [kind]. */
internal fun addLabel(kind: PluginKind): String = if (kind == PluginKind.KINO) "Dirección del plugin" else "URL"

/** The example in the empty address field for [kind]. */
internal fun addPlaceholder(kind: PluginKind): String = when (kind) {
    PluginKind.KINO -> "usuario/repositorio o https://…/kino-plugin.json"
    PluginKind.NUVIO, PluginKind.STREMIO -> "https://…/manifest.json"
}

/**
 * "Revisando…" for [ADD_CHECK_MS] while [busy], then the full-app notice and [onDone]: what "Agregar"
 * does in this app, on the phone and the TV.
 */
@Composable
internal fun AddCheckEffect(busy: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(busy) {
        if (!busy) return@LaunchedEffect
        delay(ADD_CHECK_MS)
        fullAppOnly(context)
        onDone()
    }
}

/**
 * The phone's "Agregar un plugin": the "Tipo de plugin" selector (Kino / Nuvio / Stremio), the selected
 * type's one instruction, the address field, and "Agregar" (reads "Revisando…" for a moment) / "Cancelar".
 */
@Composable
fun AddPluginDialog(onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(PluginKind.KINO) }
    var address by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    AddCheckEffect(busy) { busy = false; onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KinoSurface,
        titleContentColor = Color.White,
        title = { Text(ADD_PLUGIN_TITLE) },
        text = {
            // Scrolls: at a large font, or with the keyboard up in landscape, the dialog is shorter than its content.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tipo de plugin", style = MaterialTheme.typography.labelLarge, color = KinoTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PluginKind.entries.forEach { k -> KinoChip(k.label, selected = k == kind) { kind = k } }
                }
                Text(addInstruction(kind), style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
                Text(ADD_CONSENT_LINE, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(addLabel(kind)) },
                    placeholder = { Text(addPlaceholder(kind), color = KinoTextSecondary) },
                    singleLine = true,
                    readOnly = busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = KinoRed)
            }
        },
        confirmButton = {
            TextButton(onClick = { busy = true }, enabled = address.isNotBlank() && !busy) { Text(if (busy) "Revisando…" else "Agregar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
