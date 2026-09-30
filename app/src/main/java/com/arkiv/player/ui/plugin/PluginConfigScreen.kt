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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.arkiv.player.data.plugin.PluginSettings
import com.arkiv.player.data.plugin.SettingType
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.settings.PasswordField
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Plugins ▸ <plugin> ▸ Configurar (the phone's drawer item, the TV's Ajustes tab): one field per setting of the manifest.
 * The URL field uses the URI keyboard, the password is masked with a show/hide toggle (and hidden
 * from the accessibility tree while masked, see [PasswordField]). On TV, D-pad Down always leaves
 * a text field, since a closed keyboard would otherwise trap the focus in it. Configurar is the
 * ONLY way to set up a plugin on TV (no touch to fall back on), so the first field -- or Cancelar,
 * if the plugin has none -- must start focused: the same [FocusWhenReady] retry and [focusRing]
 * `PluginDialogs.kt`'s own dialogs already use (reused here, not reimplemented), since this
 * composable isn't on screen the first frame either.
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
    val initialFocus = remember { FocusRequester() }
    if (isTv) FocusWhenReady(initialFocus)
    val width = if (isTv) Modifier.fillMaxWidth(0.6f) else Modifier.fillMaxWidth()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (isTv) 48.dp else 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Configurar ${draft.pluginName}", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text("Lo que escribas aquí solo lo usa este plugin.", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        draft.settings.forEachIndexed { i, s ->
            val firstFocus = if (isTv && i == 0) initialFocus else null
            SettingField(s, draft, width.then(leaveOnDown), onChange, firstFocus)
        }
        draft.error?.let { Text(it, color = ArkivRed, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val cancelModifier = Modifier.focusRing().let { if (isTv && draft.settings.isEmpty()) it.focusRequester(initialFocus) else it }
            TextButton(onClick = onCancel, enabled = !draft.saving, modifier = cancelModifier) { Text("Cancelar") }
            Button(onClick = onSave, enabled = !draft.saving, modifier = Modifier.focusRing()) { Text(if (draft.saving) "Guardando…" else "Guardar") }
        }
    }
}

/** [firstFocus] is non-null only for TV's first setting (see [PluginConfigContent]); for [SettingType.SELECT] it lands on the first option, not the group. */
@Composable
private fun SettingField(s: PluginSetting, draft: PluginConfigDraft, modifier: Modifier, onChange: (String, Any?) -> Unit, firstFocus: FocusRequester? = null) {
    val label = s.label + if (s.required) " *" else ""
    val fieldModifier = firstFocus?.let { modifier.focusRequester(it) } ?: modifier
    when (s.type) {
        SettingType.TEXT -> OutlinedTextField(
            value = draft.text(s.key), onValueChange = { onChange(s.key, it) }, label = { Text(label) },
            placeholder = s.hint.takeIf { it.isNotEmpty() }?.let { { Text(it) } }, singleLine = true, modifier = fieldModifier,
        )
        SettingType.URL -> OutlinedTextField(
            value = draft.text(s.key), onValueChange = { onChange(s.key, it) }, label = { Text(label) },
            placeholder = s.hint.takeIf { it.isNotEmpty() }?.let { { Text(it) } }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = fieldModifier,
        )
        SettingType.PASSWORD -> PasswordField(draft.text(s.key), { onChange(s.key, it) }, label, fieldModifier)
        SettingType.TOGGLE -> Row(
            fieldModifier.focusRing().selectable(selected = draft.toggle(s.key), onClick = { onChange(s.key, !draft.toggle(s.key)) }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(s.label, color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = draft.toggle(s.key), onCheckedChange = null)
        }
        SettingType.LIST -> ListField(s, draft.entries(s.key), modifier, firstFocus) { onChange(s.key, it) }
        SettingType.SELECT -> Column(modifier) {
            Text(s.label, color = Color.White)
            s.options.forEachIndexed { i, o ->
                val optionModifier = Modifier.fillMaxWidth().focusRing().let { if (firstFocus != null && i == 0) it.focusRequester(firstFocus) else it }
                Row(
                    optionModifier.selectable(selected = draft.text(s.key) == o.value, onClick = { onChange(s.key, o.value) }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = draft.text(s.key) == o.value, onClick = null)
                    Text(o.label, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * A [SettingType.LIST]: the entries as text lines, each with "Editar", and "+ Agregar" below (until `max`).
 * Adding and editing share one dialog with the list's fields; editing also offers "Quitar".
 */
@Composable
private fun ListField(s: PluginSetting, entries: List<Map<String, String>>, modifier: Modifier, firstFocus: FocusRequester?, onChange: (List<Map<String, String>>) -> Unit) {
    // -1 = adding, >= 0 = editing that entry, null = closed.
    var editing by remember { mutableStateOf<Int?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(s.label + if (s.required) " *" else "", color = Color.White)
        entries.forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.fields.mapNotNull { f -> e[f.key]?.takeIf { it.isNotBlank() } }.joinToString("  ·  "), color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = i }, modifier = Modifier.focusRing()) { Text("Editar") }
            }
        }
        val full = entries.size >= s.max
        Text("${entries.size} de ${s.max}", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        Button(onClick = { editing = -1 }, enabled = !full, modifier = Modifier.focusRing().let { if (firstFocus != null) it.focusRequester(firstFocus) else it }) { Text("+ Agregar") }
    }
    editing?.let { index ->
        ListEntryDialog(
            s, entries.getOrNull(index), adding = index < 0,
            onSave = { entry -> onChange(if (index < 0) entries + entry else entries.mapIndexed { i, e -> if (i == index) entry else e }); editing = null },
            onRemove = { onChange(entries.filterIndexed { i, _ -> i != index }); editing = null },
            onCancel = { editing = null },
        )
    }
}

@Composable
private fun ListEntryDialog(s: PluginSetting, entry: Map<String, String>?, adding: Boolean, onSave: (Map<String, String>) -> Unit, onRemove: () -> Unit, onCancel: () -> Unit) {
    var values by remember { mutableStateOf(s.fields.associate { it.key to entry?.get(it.key).orEmpty() }) }
    val problem = s.fields.firstNotNullOfOrNull { f ->
        val v = values[f.key].orEmpty().trim()
        if (v.isEmpty()) "Completa \"${f.label}\"".takeIf { f.required } else PluginSettings.validateValue(f, v)
    }
    val initialFocus = remember { FocusRequester() }
    FocusWhenReady(initialFocus)
    Dialog(onDismissRequest = onCancel) {
        // Read inside the Dialog: its window has its own focus owner, not the screen behind it.
        // Same as the Configurar screen: D-pad Down always leaves a text field, or an open keyboard would trap the focus.
        val focusManager = LocalFocusManager.current
        val leaveOnDown = Modifier.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                focusManager.moveFocus(FocusDirection.Down)
                true
            } else {
                false
            }
        }
        Surface(color = ArkivBlack, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (adding) "Agregar a ${s.label}" else "Editar", style = MaterialTheme.typography.titleMedium, color = Color.White)
                s.fields.forEachIndexed { i, f ->
                    val focus = if (i == 0) Modifier.focusRequester(initialFocus) else Modifier
                    OutlinedTextField(
                        value = values.getValue(f.key), onValueChange = { values = values + (f.key to it) },
                        label = { Text(f.label + if (f.required) " *" else "") },
                        placeholder = f.hint.takeIf { it.isNotEmpty() }?.let { { Text(it) } }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (f.type == SettingType.URL) KeyboardType.Uri else KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth().then(leaveOnDown).then(focus),
                    )
                }
                if (problem != null && values.values.any { it.isNotBlank() }) Text(problem, color = ArkivRed, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel, modifier = Modifier.focusRing()) { Text("Cancelar") }
                    if (!adding) TextButton(onClick = onRemove, modifier = Modifier.focusRing()) { Text("Quitar") }
                    Button(onClick = { onSave(values.mapValues { it.value.trim() }) }, enabled = problem == null, modifier = Modifier.focusRing()) { Text(if (adding) "Agregar" else "Guardar") }
                }
            }
        }
    }
}

/** The Configurar screen over the Plugins screen, full screen. */
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
