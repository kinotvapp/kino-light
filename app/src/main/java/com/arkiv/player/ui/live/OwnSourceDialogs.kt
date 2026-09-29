package com.arkiv.player.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.live.OwnField
import com.arkiv.player.data.live.OwnKind
import com.arkiv.player.data.live.OwnProbe

/** The add/edit form and the manager of "Mis canales", driven by [vm]. */
@Composable
fun OwnSourceDialogs(vm: OwnSourcesViewModel, showManager: Boolean, onCloseManager: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (ui.open) OwnSourceFormDialog(ui, vm)
    if (showManager) OwnSourcesManager(vm, onCloseManager)
}

@Composable
private fun OwnSourceFormDialog(ui: OwnFormUi, vm: OwnSourcesViewModel) {
    val f = ui.form
    var advanced by remember(ui.editingId) {
        mutableStateOf(f.userAgent.isNotEmpty() || f.referer.isNotEmpty() || f.epgUrl.isNotEmpty() || f.logo.isNotEmpty() || f.groupName.isNotEmpty())
    }
    val playlist = f.kind == OwnKind.PLAYLIST
    AlertDialog(
        onDismissRequest = { if (!ui.busy) vm.dismiss() },
        title = {
            Text(
                when {
                    ui.editingId != null -> OwnSourcesCopy.TITLE_EDIT
                    playlist -> OwnSourcesCopy.TITLE_PLAYLIST
                    else -> OwnSourcesCopy.TITLE_CHANNEL
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.editingId == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !playlist, onClick = { vm.change(f.copy(kind = OwnKind.CHANNEL)) }, label = { Text("Canal") })
                        FilterChip(selected = playlist, onClick = { vm.change(f.copy(kind = OwnKind.PLAYLIST)) }, label = { Text("Lista M3U") })
                    }
                }
                OwnTextField(OwnSourcesCopy.NAME, f.name, ui.errors[OwnField.NAME]) { vm.change(f.copy(name = it)) }
                OwnTextField(
                    if (playlist) OwnSourcesCopy.URL_PLAYLIST else OwnSourcesCopy.URL_CHANNEL, f.url, ui.errors[OwnField.URL], uri = true,
                ) { vm.change(f.copy(url = it)) }
                if (ui.cleartext) {
                    Text(OwnSourcesCopy.CLEARTEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                TextButton(onClick = { advanced = !advanced }) { Text(OwnSourcesCopy.ADVANCED + if (advanced) " ▴" else " ▾") }
                if (advanced) {
                    if (playlist) {
                        OwnTextField(OwnSourcesCopy.EPG, f.epgUrl, ui.errors[OwnField.EPG], uri = true) { vm.change(f.copy(epgUrl = it)) }
                    } else {
                        OwnTextField(OwnSourcesCopy.GROUP, f.groupName, null) { vm.change(f.copy(groupName = it)) }
                        OwnTextField(OwnSourcesCopy.LOGO, f.logo, ui.errors[OwnField.LOGO], uri = true) { vm.change(f.copy(logo = it)) }
                    }
                    OwnTextField(OwnSourcesCopy.USER_AGENT, f.userAgent, ui.errors[OwnField.USER_AGENT]) { vm.change(f.copy(userAgent = it)) }
                    OwnTextField(OwnSourcesCopy.REFERER, f.referer, ui.errors[OwnField.REFERER], uri = true) { vm.change(f.copy(referer = it)) }
                }
                when (val p = ui.probe) {
                    is OwnProbe.Ok -> Text(p.message, color = androidx.compose.ui.graphics.Color(0xFF00E676))
                    is OwnProbe.Failed -> Text(p.message, color = MaterialTheme.colorScheme.error)
                    null -> if (ui.busy) Text("Revisando…")
                }
                ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = vm::save, enabled = !ui.busy) { Text(OwnSourcesCopy.SAVE) } },
        dismissButton = {
            Row {
                TextButton(onClick = vm::probeNow, enabled = !ui.busy && f.url.isNotBlank()) { Text(OwnSourcesCopy.PROBE) }
                TextButton(onClick = vm::dismiss, enabled = !ui.busy) { Text(OwnSourcesCopy.CANCEL) }
            }
        },
    )
}

@Composable
private fun OwnTextField(label: String, value: String, error: String?, uri: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            capitalization = if (uri) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            autoCorrectEnabled = false,
            keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OwnSourcesManager(vm: OwnSourcesViewModel, onClose: () -> Unit) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<OwnLiveSourceEntity?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(OwnSourcesCopy.MY_SOURCES) },
        text = {
            if (sources.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(OwnSourcesCopy.EMPTY_TITLE, style = MaterialTheme.typography.titleSmall)
                    Text(OwnSourcesCopy.EMPTY_BODY)
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    sources.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${OwnSourcesCopy.kindLabel(s.kind == "PLAYLIST")}\u00A0·\u00A0${OwnSourcesCopy.hostOf(s.url)}",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { vm.startEdit(s); onClose() }) { Text(OwnSourcesCopy.EDIT) }
                            TextButton(onClick = { deleting = s }) { Text(OwnSourcesCopy.DELETE) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(OwnSourcesCopy.CLOSE) } },
    )
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(OwnSourcesCopy.confirmDelete(s.name)) },
            confirmButton = { TextButton(onClick = { vm.delete(s.id); deleting = null }) { Text(OwnSourcesCopy.DELETE) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(OwnSourcesCopy.CANCEL) } },
        )
    }
}
