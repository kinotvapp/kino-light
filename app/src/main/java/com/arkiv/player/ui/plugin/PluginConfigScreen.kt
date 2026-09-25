package com.arkiv.player.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.plugin.PluginSetting
import com.arkiv.player.data.plugin.SettingType
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.settings.PasswordField
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Ajustes ▸ Plugins ▸ <plugin> ▸ Configurar, phone and TV: one field per setting of the manifest.
 * The URL field uses the URI keyboard, the password is masked with a show/hide toggle (and hidden
 * from the accessibility tree while masked, see [PasswordField]). On TV, D-pad Down always leaves
 * a text field, since a closed keyboard would otherwise trap the focus in it.
 */
@Composable
fun PluginConfigContent(
    draft: PluginConfigDraft,
    isTv: Boolean,
    onChange: (key: String, value: Any?) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val leaveOnDown = Modifier.onPreviewKeyEvent { e ->
        if (isTv && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
            focusManager.moveFocus(FocusDirection.Down)
            true
        } else {
            false
        }
    }
    val width = if (isTv) Modifier.fillMaxWidth(0.6f) else Modifier.fillMaxWidth()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (isTv) 48.dp else 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Configurar ${draft.pluginName}", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text("Lo que escribas aquí solo lo usa este plugin.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        draft.settings.forEach { s -> SettingField(s, draft, width.then(leaveOnDown), onChange) }
        draft.error?.let { Text(it, color = ArkivRed, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCancel, enabled = !draft.saving) { Text("Cancelar") }
            Button(onClick = onSave, enabled = !draft.saving) { Text(if (draft.saving) "Guardando…" else "Guardar") }
        }
    }
}

@Composable
private fun SettingField(s: PluginSetting, draft: PluginConfigDraft, modifier: Modifier, onChange: (String, Any?) -> Unit) {
    val label = s.label + if (s.required) " *" else ""
    when (s.type) {
        SettingType.TEXT -> OutlinedTextField(
            value = draft.text(s.key), onValueChange = { onChange(s.key, it) }, label = { Text(label) },
            placeholder = s.hint.takeIf { it.isNotEmpty() }?.let { { Text(it) } }, singleLine = true, modifier = modifier,
        )
        SettingType.URL -> OutlinedTextField(
            value = draft.text(s.key), onValueChange = { onChange(s.key, it) }, label = { Text(label) },
            placeholder = s.hint.takeIf { it.isNotEmpty() }?.let { { Text(it) } }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = modifier,
        )
        SettingType.PASSWORD -> PasswordField(draft.text(s.key), { onChange(s.key, it) }, label, modifier)
        SettingType.TOGGLE -> Row(
            modifier.selectable(selected = draft.toggle(s.key), onClick = { onChange(s.key, !draft.toggle(s.key)) }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(s.label, color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = draft.toggle(s.key), onCheckedChange = null)
        }
        SettingType.SELECT -> Column(modifier) {
            Text(s.label, color = Color.White)
            s.options.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = draft.text(s.key) == o.value, onClick = { onChange(s.key, o.value) }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = draft.text(s.key) == o.value, onClick = null)
                    Text(o.label, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** The Configurar screen over Ajustes ▸ Plugins, full screen. */
@Composable
fun PluginConfigDialog(draft: PluginConfigDraft, isTv: Boolean, vm: PluginsViewModel) {
    Dialog(onDismissRequest = vm::closeSettings, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = ArkivBlack, modifier = Modifier.fillMaxSize()) {
            PluginConfigContent(draft, isTv, vm::onSettingChange, vm::saveSettings, vm::closeSettings)
        }
    }
}

/**
 * The same screen as its own route (`plugin_config/{id}`), for the "Configurar" button of an
 * `auth_required` error in the player or "Ver más". [onDone] runs after saving or cancelling.
 */
@Composable
fun PluginConfigRoute(pluginId: String, isTv: Boolean, onDone: () -> Unit) {
    val graph = rememberGraph()
    val vm: PluginsViewModel = viewModel(key = "config-$pluginId", factory = viewModelFactory { initializer { PluginsViewModel(graph.pluginAdmin) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(pluginId) { vm.openSettings(pluginId) }
    LaunchedEffect(state.settingsClosed) { if (state.settingsClosed) onDone() }
    Surface(color = ArkivBlack, modifier = Modifier.fillMaxSize()) {
        state.configuring?.let { PluginConfigContent(it, isTv, vm::onSettingChange, vm::saveSettings, vm::closeSettings) }
            ?: state.message?.let { Text(it, color = Color.White, modifier = Modifier.padding(24.dp)) }
    }
}
