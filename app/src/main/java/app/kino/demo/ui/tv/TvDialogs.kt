package app.kino.demo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.demo.data.DemoSession
import app.kino.demo.data.DemoSource
import app.kino.demo.data.PluginKind
import app.kino.demo.data.demoPluginLog
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.plugins.DEBUG_SWITCH_LABEL
import app.kino.demo.ui.plugins.DEBUG_SWITCH_LINE
import app.kino.demo.ui.plugins.PluginKindBadge
import app.kino.demo.ui.plugins.registroTitle
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.plugins.ADD_CONSENT_LINE
import app.kino.demo.ui.plugins.ADD_PLUGIN_TITLE
import app.kino.demo.ui.plugins.AddCheckEffect
import app.kino.demo.ui.plugins.addInstruction
import app.kino.demo.ui.plugins.addLabel
import app.kino.demo.ui.plugins.addPlaceholder
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurface
import app.kino.demo.ui.theme.KinoTextSecondary
import kotlinx.coroutines.delay

/**
 * Where a TV dialog puts the D-pad focus when it opens. The dialog's window takes a few frames to be
 * focused, so the request repeats until the target really holds focus (as [rememberLandingFocus]).
 */
class DialogFocus internal constructor() {
    val requester = FocusRequester()
    internal var focused = false
}

@Composable
fun rememberDialogFocus(): DialogFocus {
    val focus = remember { DialogFocus() }
    LaunchedEffect(focus) {
        repeat(60) {
            if (focus.focused) return@LaunchedEffect
            runCatching { focus.requester.requestFocus() }
            delay(50)
        }
    }
    return focus
}

/** Marks the element a TV dialog opens on. */
fun Modifier.dialogFocus(focus: DialogFocus): Modifier =
    focusRequester(focus.requester).onFocusChanged { focus.focused = it.isFocused }

/** A TV dialog: a dark rounded panel with a title, [content] below it; Back closes it ([onDismiss]). */
@Composable
internal fun TvDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 420.dp, max = 640.dp)
                .background(KinoSurface, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            content()
        }
    }
}

/**
 * The TV's "Agregar un plugin": the type selector (Kino / Nuvio / Stremio) where focus starts, the
 * type's instruction, the address field and "Cancelar" / "Agregar" ("Revisando…" for a moment).
 */
@Composable
internal fun TvAddPluginDialog(onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(PluginKind.KINO) }
    var address by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    val focus = rememberDialogFocus()
    AddCheckEffect(busy) { busy = false; onDismiss() }

    TvDialog(ADD_PLUGIN_TITLE, onDismiss) {
        Text("Tipo de plugin", style = MaterialTheme.typography.labelLarge, color = KinoTextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PluginKind.entries.forEach { k ->
                TvChoiceChip(
                    label = k.label,
                    selected = k == kind,
                    onClick = { kind = k },
                    modifier = if (k == PluginKind.KINO) Modifier.dialogFocus(focus) else Modifier,
                )
            }
        }
        Text(addInstruction(kind), style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
        Text(ADD_CONSENT_LINE, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { androidx.compose.material3.Text(addLabel(kind)) },
            placeholder = { androidx.compose.material3.Text(addPlaceholder(kind), color = KinoTextSecondary) },
            singleLine = true,
            readOnly = busy,
            modifier = Modifier.fillMaxWidth(),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = KinoRed)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End)) {
            TvCompactAction(label = "Cancelar", onClick = onDismiss)
            TvCompactAction(label = if (busy) "Revisando…" else "Agregar") { if (address.isNotBlank() && !busy) busy = true }
        }
    }
}

/**
 * An installed plugin's settings on the TV (OK on its card): "Activo", "Modo debug" with its line and,
 * while it is on, "Ver registro", which turns the dialog into the plugin's Registro (its last events,
 * "Copiar registro" and "Volver").
 */
@Composable
internal fun TvPluginSettingsDialog(plugin: DemoSource, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showingLog by rememberSaveable(plugin.id) { mutableStateOf(false) }
    val focus = rememberDialogFocus()
    val on = DemoSession.enabled[plugin.id] == true
    val debugOn = DemoSession.debug[plugin.id] == true

    TvDialog(if (showingLog) registroTitle(plugin.name) else "${plugin.name} ${plugin.version}", onDismiss) {
        if (showingLog) {
            Column(
                Modifier.fillMaxWidth().background(KinoBlack, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                demoPluginLog(plugin).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color.White)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvCompactAction(label = "Volver", modifier = Modifier.dialogFocus(focus)) { showingLog = false }
                TvCompactAction(label = "Copiar registro") { fullAppOnly(context) }
            }
        } else {
            PluginKindBadge(plugin.kind)
            Text("Se conecta a: ${plugin.hosts}", style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
            TvCompactAction(label = if (on) "Activo: sí" else "Activo: no", modifier = Modifier.dialogFocus(focus)) { DemoSession.enabled[plugin.id] = !on }
            TvCompactAction(label = if (debugOn) "$DEBUG_SWITCH_LABEL: activado" else "$DEBUG_SWITCH_LABEL: desactivado") { DemoSession.debug[plugin.id] = !debugOn }
            Text(DEBUG_SWITCH_LINE, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (debugOn) TvCompactAction(label = "Ver registro") { showingLog = true }
                TvCompactAction(label = "Desinstalar") { fullAppOnly(context) }
                TvCompactAction(label = "Cerrar", onClick = onDismiss)
            }
        }
    }
}
