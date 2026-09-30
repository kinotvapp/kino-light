package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.live.OwnField
import com.arkiv.player.data.live.OwnKind
import com.arkiv.player.data.live.OwnProbe
import com.arkiv.player.ui.live.FreshPressGate
import com.arkiv.player.ui.live.OwnFormUi
import com.arkiv.player.ui.live.OwnSourcesCopy
import com.arkiv.player.ui.live.OwnSourcesViewModel
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * The TV's add/edit form and manager of "Mis canales", driven by [vm] (the same state the phone uses).
 * Fields use the system keyboard, like [TvAddCustomPluginDialog]: D-pad Up and Down always leave a
 * field ([dpadLeavesTheField]) so a closed keyboard never traps focus.
 */
@Composable
internal fun TvOwnSourceDialogs(vm: OwnSourcesViewModel, showManager: Boolean, onCloseManager: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (ui.open) TvOwnSourceForm(ui, vm)
    if (showManager) TvOwnSourcesManager(vm, onCloseManager)
}

@Composable
private fun TvOwnSourceForm(ui: OwnFormUi, vm: OwnSourcesViewModel) {
    val f = ui.form
    val playlist = f.kind == OwnKind.PLAYLIST
    var advanced by remember(ui.editingId) {
        mutableStateOf(f.userAgent.isNotEmpty() || f.referer.isNotEmpty() || f.epgUrl.isNotEmpty() || f.logo.isNotEmpty() || f.groupName.isNotEmpty())
    }
    val nameFocus = remember { FocusRequester() }
    FocusWhenReady(nameFocus)
    Dialog(onDismissRequest = { if (!ui.busy) vm.dismiss() }) {
        // Read INSIDE the dialog: it is a window of its own, with its own focus manager.
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .width(600.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ArkivSurface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when {
                    ui.editingId != null -> OwnSourcesCopy.TITLE_EDIT
                    playlist -> OwnSourcesCopy.TITLE_PLAYLIST
                    else -> OwnSourcesCopy.TITLE_CHANNEL
                },
                style = MaterialTheme.typography.headlineSmall, color = Color.White,
            )
            if (ui.editingId == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvCompactAction(label = (if (!playlist) "✓ " else "") + "Canal", onClick = { vm.change(f.copy(kind = OwnKind.CHANNEL)) })
                    TvCompactAction(label = (if (playlist) "✓ " else "") + "Lista M3U", onClick = { vm.change(f.copy(kind = OwnKind.PLAYLIST)) })
                }
            }
            TvOwnTextField(OwnSourcesCopy.NAME, f.name, ui.errors[OwnField.NAME], focusManager, Modifier.focusRequester(nameFocus)) { vm.change(f.copy(name = it)) }
            TvOwnTextField(
                if (playlist) OwnSourcesCopy.URL_PLAYLIST else OwnSourcesCopy.URL_CHANNEL, f.url, ui.errors[OwnField.URL], focusManager, uri = true,
                placeholder = if (playlist) OwnSourcesCopy.URL_PLAYLIST_HINT else OwnSourcesCopy.URL_CHANNEL_HINT,
            ) { vm.change(f.copy(url = it)) }
            if (ui.cleartext) Text(OwnSourcesCopy.CLEARTEXT, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
            TvCompactAction(label = OwnSourcesCopy.ADVANCED + if (advanced) " ▴" else " ▾", onClick = { advanced = !advanced })
            if (advanced) {
                if (playlist) {
                    TvOwnTextField(OwnSourcesCopy.EPG, f.epgUrl, ui.errors[OwnField.EPG], focusManager, uri = true) { vm.change(f.copy(epgUrl = it)) }
                } else {
                    TvOwnTextField(OwnSourcesCopy.GROUP, f.groupName, null, focusManager) { vm.change(f.copy(groupName = it)) }
                    TvOwnTextField(OwnSourcesCopy.LOGO, f.logo, ui.errors[OwnField.LOGO], focusManager, uri = true) { vm.change(f.copy(logo = it)) }
                }
                TvOwnTextField(OwnSourcesCopy.USER_AGENT, f.userAgent, ui.errors[OwnField.USER_AGENT], focusManager) { vm.change(f.copy(userAgent = it)) }
                TvOwnTextField(OwnSourcesCopy.REFERER, f.referer, ui.errors[OwnField.REFERER], focusManager, uri = true) { vm.change(f.copy(referer = it)) }
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArkivRed)
            when (val p = ui.probe) {
                is OwnProbe.Ok -> Text(p.message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF00E676))
                is OwnProbe.Failed -> Text(p.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
            ui.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TvCompactAction(label = OwnSourcesCopy.PROBE, enabled = !ui.busy && f.url.isNotBlank(), onClick = vm::probeNow)
                TvCompactAction(label = OwnSourcesCopy.CANCEL, enabled = !ui.busy, onClick = vm::dismiss)
                TvCompactAction(label = OwnSourcesCopy.SAVE, enabled = !ui.busy, onClick = vm::save)
            }
        }
    }
}

@Composable
private fun TvOwnTextField(
    label: String,
    value: String,
    error: String?,
    focusManager: androidx.compose.ui.focus.FocusManager,
    modifier: Modifier = Modifier,
    uri: Boolean = false,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
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
            imeAction = ImeAction.Next,
        ),
        modifier = modifier.fillMaxWidth().dpadLeavesTheField(focusManager),
    )
}

@Composable
private fun TvOwnSourcesManager(vm: OwnSourcesViewModel, onClose: () -> Unit) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<OwnLiveSourceEntity?>(null) }
    val closeFocus = remember { FocusRequester() }
    FocusWhenReady(closeFocus)
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ArkivSurface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(OwnSourcesCopy.MY_SOURCES, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvCompactAction(label = OwnSourcesCopy.ADD_CHANNEL, onClick = { vm.startNew(OwnKind.CHANNEL); onClose() })
                TvCompactAction(label = OwnSourcesCopy.ADD_PLAYLIST, onClick = { vm.startNew(OwnKind.PLAYLIST); onClose() })
            }
            if (sources.isEmpty()) {
                Text(OwnSourcesCopy.EMPTY_TITLE, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(OwnSourcesCopy.EMPTY_BODY, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
            } else {
                sources.forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                            Text(
                                "${OwnSourcesCopy.kindLabel(s.kind == "PLAYLIST")}\u00A0·\u00A0${OwnSourcesCopy.hostOf(s.url)}",
                                style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        TvCompactAction(label = OwnSourcesCopy.EDIT, onClick = { vm.startEdit(s); onClose() })
                        TvCompactAction(label = OwnSourcesCopy.DELETE, onClick = { deleting = s })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TvCompactAction(label = OwnSourcesCopy.CLOSE, modifier = Modifier.focusRequester(closeFocus), onClick = onClose)
            }
        }
    }
    deleting?.let { s -> TvOwnDeleteDialog(s.name, onConfirm = { vm.delete(s.id); deleting = null }, onDismiss = { deleting = null }) }
}

/** Confirm a delete. The gate swallows the tail of the OK press that opened it, so it can't confirm itself (as [TvFavoriteDialog]). */
@Composable
private fun TvOwnDeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    val gate = remember { FreshPressGate() }
    FocusWhenReady(cancelFocus)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    !gate.admit(isDown = native.action == android.view.KeyEvent.ACTION_DOWN, repeatCount = native.repeatCount)
                }
                .widthIn(min = 360.dp, max = 560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ArkivSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(OwnSourcesCopy.confirmDelete(name), style = MaterialTheme.typography.titleLarge, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Cancel takes the first focus: the destructive action is never the default.
                TvCompactAction(label = OwnSourcesCopy.CANCEL, modifier = Modifier.focusRequester(cancelFocus), onClick = onDismiss)
                TvCompactAction(label = OwnSourcesCopy.DELETE, onClick = onConfirm)
            }
        }
    }
}
