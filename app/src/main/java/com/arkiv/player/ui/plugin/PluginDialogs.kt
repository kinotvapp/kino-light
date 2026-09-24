package com.arkiv.player.ui.plugin

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.ui.catalog.MetaChip
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/**
 * The consent sheet: what the plugin is, WHICH HOSTS it will talk to, and that nobody verified
 * it. Nothing beyond the manifest has been downloaded yet. On an update only the new hosts are
 * marked "nuevo". Focus starts on "Cancelar".
 */
@Composable
fun PluginConsentDialog(preview: InstallPreview, onInstall: () -> Unit, onCancel: () -> Unit) {
    val m = preview.manifest
    val cancelFocus = remember { FocusRequester() }
    FocusWhenReady(cancelFocus)
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(16.dp), color = ArkivSurface, modifier = Modifier.widthIn(max = 520.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (preview.isUpdate) "Actualizar ${m.name}" else "Instalar ${m.name}",
                    style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull("versión ${m.version}", m.author.takeIf { it.isNotBlank() }?.let { "de $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary,
                )
                if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Text("Se va a conectar con:", style = MaterialTheme.typography.titleSmall, color = Color.White)
                m.hosts.forEach { host ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        if (preview.isUpdate && host in preview.newHosts) MetaChip("nuevo", ArkivRed, strong = true)
                    }
                }
                Text(
                    "Plugin no verificado: solo instálalo si confías en quien lo hizo.",
                    style = MaterialTheme.typography.bodySmall, color = ArkivRed,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus).focusRing()) { Text("Cancelar") }
                    Button(onClick = onInstall, modifier = Modifier.focusRing()) { Text(if (preview.isUpdate) "Actualizar" else "Instalar") }
                }
            }
        }
    }
}

/** "¿Desinstalar X?" — the library keeps its titles, which is said up front. Focus starts on "Cancelar". */
@Composable
fun PluginUninstallDialog(plugin: InstalledPlugin, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    FocusWhenReady(cancelFocus)
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(16.dp), color = ArkivSurface, modifier = Modifier.widthIn(max = 480.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("¿Desinstalar ${plugin.manifest.name}?", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    "Lo que guardaste de este plugin se queda en tu biblioteca, pero no se va a poder ver hasta que lo vuelvas a instalar.",
                    style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus).focusRing()) { Text("Cancelar") }
                    TextButton(onClick = onConfirm, modifier = Modifier.focusRing()) { Text("Desinstalar", color = ArkivRed) }
                }
            }
        }
    }
}

/**
 * Puts the initial focus on [requester] (the safe choice). The dialog window isn't composed on the
 * first frame, and `requestFocus()` throws "FocusRequester is not initialized" until it is -- hence
 * the retry loop, as in `UpdateDialog`.
 */
@Composable
private fun FocusWhenReady(requester: FocusRequester) {
    LaunchedEffect(requester) {
        delay(200)
        repeat(20) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
}

/**
 * A white outline on the focused button while a D-pad/keyboard drives the UI. Material3's own
 * focus state is a faint overlay that is hard to see from the couch on the dark surface; on a
 * touch screen the initial focus on "Cancelar" stays invisible.
 */
private fun Modifier.focusRing(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    this
        .onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused && keyboard) Color.White else Color.Transparent, RoundedCornerShape(20.dp))
}
