package com.arkiv.player.ui.live

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.data.live.OwnPastedList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.arkiv.player.data.live.IptvOrgCatalog
import com.arkiv.player.data.live.IptvOrgKind
import com.arkiv.player.data.live.XtreamUrl
import com.arkiv.player.data.live.OwnField
import com.arkiv.player.data.live.OwnKind
import com.arkiv.player.data.live.OwnProbe

/** The add/edit form and the manager of "Mis canales", driven by [vm]. */
@Composable
fun OwnSourceDialogs(vm: OwnSourcesViewModel, showManager: Boolean, onCloseManager: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (ui.open) OwnSourceFormDialog(ui, vm)
    if (ui.picker) OwnIptvOrgPicker(ui, vm)
    if (showManager) OwnSourcesManager(vm, onCloseManager)
}

@Composable
private fun OwnSourceFormDialog(ui: OwnFormUi, vm: OwnSourcesViewModel) {
    val f = ui.form
    var advanced by remember(ui.editingId) {
        mutableStateOf(f.userAgent.isNotEmpty() || f.referer.isNotEmpty() || f.epgUrl.isNotEmpty() || f.logo.isNotEmpty() || f.groupName.isNotEmpty())
    }
    val playlist = f.kind == OwnKind.PLAYLIST
    val xtream = ui.xtream
    AlertDialog(
        onDismissRequest = { if (!ui.busy) vm.dismiss() },
        title = {
            Text(
                when {
                    ui.editingId != null -> OwnSourcesCopy.TITLE_EDIT
                    xtream != null -> OwnSourcesCopy.TITLE_XTREAM
                    playlist -> OwnSourcesCopy.TITLE_PLAYLIST
                    else -> OwnSourcesCopy.TITLE_CHANNEL
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.editingId == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !playlist, onClick = { vm.setXtreamMode(false); vm.change(f.copy(kind = OwnKind.CHANNEL)) }, label = { Text(OwnSourcesCopy.KIND_CHANNEL) })
                        FilterChip(selected = playlist && xtream == null, onClick = { vm.setXtreamMode(false); vm.change(f.copy(kind = OwnKind.PLAYLIST)) }, label = { Text(OwnSourcesCopy.KIND_PLAYLIST) })
                        FilterChip(selected = xtream != null, onClick = { vm.setXtreamMode(true) }, label = { Text(OwnSourcesCopy.KIND_XTREAM) })
                    }
                }
                OwnTextField(OwnSourcesCopy.NAME, f.name, ui.errors[OwnField.NAME]) { vm.change(f.copy(name = it)) }
                val pastedLabel = ui.pasted
                if (xtream != null) {
                    OwnXtreamFields(ui, xtream, vm)
                } else if (playlist && pastedLabel != null) {
                    // A list with no address: what it is, and how to replace it.
                    Text(pastedLabel, style = MaterialTheme.typography.titleSmall)
                    if (ui.editingId != null && f.pastedText == null) {
                        Text(OwnSourcesCopy.PASTED_REPLACE, style = MaterialTheme.typography.bodySmall)
                    }
                    ui.errors[OwnField.URL]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                } else {
                    OwnTextField(
                        if (playlist) OwnSourcesCopy.URL_PLAYLIST else OwnSourcesCopy.URL_CHANNEL, f.url, ui.errors[OwnField.URL], uri = true,
                        placeholder = if (playlist) OwnSourcesCopy.URL_PLAYLIST_HINT else OwnSourcesCopy.URL_CHANNEL_HINT,
                    ) { vm.change(f.copy(url = it)) }
                }
                if (playlist && xtream == null) {
                    Text(OwnSourcesCopy.PLAYLIST_INFO, style = MaterialTheme.typography.bodySmall)
                    OwnPasteOrFile(vm, enabled = !ui.busy)
                    if (pastedLabel == null) Text(OwnSourcesCopy.PASTE_HINT, style = MaterialTheme.typography.bodySmall)
                    else TextButton(onClick = vm::clearPasted, enabled = !ui.busy) { Text(OwnSourcesCopy.USE_URL) }
                }
                if (ui.cleartext) {
                    Text(OwnSourcesCopy.CLEARTEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                TextButton(onClick = { advanced = !advanced }) { Text(OwnSourcesCopy.ADVANCED + if (advanced) " ▴" else " ▾") }
                if (advanced) {
                    if (playlist) {
                        // A Xtream server brings its own guide (xmltv.php); this one is for another.
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
                TextButton(
                    onClick = vm::probeNow,
                    enabled = !ui.busy && ui.pasted == null && (if (xtream != null) xtream.server.isNotBlank() else f.url.isNotBlank()),
                ) { Text(OwnSourcesCopy.PROBE) }
                TextButton(onClick = vm::dismiss, enabled = !ui.busy) { Text(OwnSourcesCopy.CANCEL) }
            }
        },
    )
}

/**
 * "Pegar lista" (the clipboard's text, read only when tapped) and "Abrir archivo" (the system file picker:
 * every type, since lists come as .m3u, .m3u8, .w3u, .json or no known type; the extension and the content
 * are checked afterwards). The file is read here, at most [OwnPastedList.MAX_BYTES] + 1 bytes.
 */
@Composable
private fun OwnPasteOrFile(vm: OwnSourcesViewModel, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val (name, bytes, truncated) = withContext(Dispatchers.IO) { readPickedList(context, uri) }
            vm.useFile(name, bytes, truncated)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.usePasted(clipboardText(context)) }, enabled = enabled) { Text(OwnSourcesCopy.PASTE) }
        OutlinedButton(onClick = { runCatching { picker.launch(arrayOf("*/*")) } }, enabled = enabled) { Text(OwnSourcesCopy.OPEN_FILE) }
    }
}

private fun clipboardText(context: Context): String? = runCatching {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
    if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)?.toString()
}.getOrNull()

/** The picked file's display name, its bytes (null = unreadable) and whether it went past the cap. Blocking IO. */
private fun readPickedList(context: Context, uri: Uri): Triple<String?, ByteArray?, Boolean> {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return try {
        val limit = OwnPastedList.MAX_BYTES + 1
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        Triple(name, bytes, bytes != null && bytes.size > OwnPastedList.MAX_BYTES)
    } catch (e: Exception) {
        Triple(name, null, false)
    }
}

/** Server, user and password of a Xtream account. The password is masked (with a toggle) and never leaves the device except through the synced row. */
@Composable
private fun OwnXtreamFields(ui: OwnFormUi, x: XtreamInput, vm: OwnSourcesViewModel) {
    var show by remember { mutableStateOf(false) }
    Text(OwnSourcesCopy.XTREAM_INFO, style = MaterialTheme.typography.bodySmall)
    OwnTextField(
        OwnSourcesCopy.XTREAM_SERVER, x.server, ui.xtreamErrors[XtreamUrl.XtreamField.SERVER] ?: ui.errors[OwnField.URL], uri = true,
        placeholder = OwnSourcesCopy.XTREAM_SERVER_HINT,
    ) { vm.changeXtream(x.copy(server = it)) }
    OwnTextField(OwnSourcesCopy.XTREAM_USER, x.username, ui.xtreamErrors[XtreamUrl.XtreamField.USERNAME], uri = true) { vm.changeXtream(x.copy(username = it)) }
    OutlinedTextField(
        value = x.password, onValueChange = { vm.changeXtream(x.copy(password = it)) },
        label = { Text(OwnSourcesCopy.XTREAM_PASS) }, singleLine = true,
        isError = ui.xtreamErrors[XtreamUrl.XtreamField.PASSWORD] != null,
        supportingText = ui.xtreamErrors[XtreamUrl.XtreamField.PASSWORD]?.let { { Text(it) } },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, capitalization = KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = { show = !show }) { Text(if (show) OwnSourcesCopy.XTREAM_HIDE else OwnSourcesCopy.XTREAM_SHOW) }
}

/** The ready-made iptv-org lists: a country, a language or a category; one tap saves it as a list. */
@Composable
private fun OwnIptvOrgPicker(ui: OwnFormUi, vm: OwnSourcesViewModel) {
    var kind by remember { mutableStateOf(IptvOrgKind.COUNTRY) }
    AlertDialog(
        onDismissRequest = { if (!ui.busy) vm.dismiss() },
        title = { Text(OwnSourcesCopy.IPTV_ORG_TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(OwnSourcesCopy.IPTV_ORG_INFO, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IptvOrgKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
                }
                IptvOrgCatalog.of(kind).forEach { item ->
                    TextButton(onClick = { vm.addIptvOrg(item) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(item.title, modifier = Modifier.fillMaxWidth())
                    }
                }
                ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (ui.busy) Text("Agregando…")
            }
        },
        confirmButton = { TextButton(onClick = vm::dismiss, enabled = !ui.busy) { Text(OwnSourcesCopy.CLOSE) } },
    )
}

@Composable
private fun OwnTextField(label: String, value: String, error: String?, uri: Boolean = false, placeholder: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
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
                                    "${OwnSourcesCopy.kindLabel(s.kind, s.url)}\u00A0·\u00A0${OwnSourcesCopy.hostOf(s.url)}",
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
