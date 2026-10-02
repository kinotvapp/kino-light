package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.arkiv.player.AppGraph
import com.arkiv.player.data.subtitles.SubtitleProviderId
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.settings.OnlineKeysCopy
import com.arkiv.player.ui.settings.rememberOnlineKeyView
import com.arkiv.player.ui.settings.testOnlineKey
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes → Audio y subtítulos on TV, the online part: per provider, on/off, its place, which key is
 * in use, "Escribir mi llave" (a dialog with the field and "Probar llave"/"Guardar"), "Probar llave"
 * and "Borrar mi llave"; OpenSubtitles' optional account too. All rows are D-pad buttons.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvOnlineSubtitleKeys() {
    val graph = rememberGraph()
    val providers by graph.subtitleKeys.providers.collectAsStateWithLifecycle()
    androidx.tv.material3.Text(OnlineKeysCopy.SECTION, style = MaterialTheme.typography.titleMedium, color = Color.White)
    androidx.tv.material3.Text(OnlineKeysCopy.INTRO, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
    providers.order.forEachIndexed { index, id ->
        TvProviderKeyRows(graph, id, enabled = id !in providers.disabled, canMoveUp = index > 0)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvProviderKeyRows(graph: AppGraph, id: SubtitleProviderId, enabled: Boolean, canMoveUp: Boolean) {
    val view = rememberOnlineKeyView(graph, id)
    val scope = rememberCoroutineScope()
    var note by remember(id) { mutableStateOf<String?>(null) }
    var editing by remember(id) { mutableStateOf(false) }
    var account by remember(id) { mutableStateOf(false) }
    androidx.tv.material3.Text(
        "${OnlineKeysCopy.title(id)} · ${OnlineKeysCopy.inUse(id, view?.source)}",
        style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.padding(top = 8.dp),
    )
    TvActionOption("${OnlineKeysCopy.enabled(id, enabled)} (toca para cambiar)") {
        graph.subtitleKeys.setProviders(graph.subtitleKeys.providers.value.toggled(id))
    }
    if (canMoveUp) {
        TvActionOption("${OnlineKeysCopy.MOVE_UP} ${id.label}") {
            graph.subtitleKeys.setProviders(graph.subtitleKeys.providers.value.movedUp(id))
        }
    }
    TvActionOption(if (view?.hasUserKey == true) "Cambiar mi llave de ${id.label}" else "Escribir mi llave de ${id.label}") { editing = true }
    TvActionOption(OnlineKeysCopy.TEST) {
        note = OnlineKeysCopy.TESTING
        scope.launch { note = testOnlineKey(graph, id, "") }
    }
    if (view?.hasUserKey == true) {
        TvActionOption(OnlineKeysCopy.CLEAR) {
            scope.launch { withContext(Dispatchers.IO) { graph.subtitleKeys.setUserKey(id, "") } }
        }
    }
    if (id == SubtitleProviderId.OPENSUBTITLES) {
        val user = view?.accountUser.orEmpty()
        if (user.isNotBlank()) {
            TvActionOption("${OnlineKeysCopy.CLEAR_ACCOUNT} ($user)") {
                scope.launch { withContext(Dispatchers.IO) { graph.subtitleKeys.setAccount("", "") } }
            }
        } else {
            TvActionOption(OnlineKeysCopy.ACCOUNT) { account = true }
        }
    }
    note?.let { androidx.tv.material3.Text(it, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary) }

    if (editing) {
        TvTextDialog(
            title = OnlineKeysCopy.title(id),
            labels = listOf("Llave (API key)"),
            secret = listOf(true),
            onTest = { values -> testOnlineKey(graph, id, values[0]) },
            onSave = { values -> withContext(Dispatchers.IO) { graph.subtitleKeys.setUserKey(id, values[0]) } },
            onClose = { editing = false },
        )
    }
    if (account) {
        TvTextDialog(
            title = OnlineKeysCopy.ACCOUNT,
            labels = listOf(OnlineKeysCopy.USER, OnlineKeysCopy.PASSWORD),
            secret = listOf(false, true),
            onTest = null,
            onSave = { values -> withContext(Dispatchers.IO) { graph.subtitleKeys.setAccount(values[0], values[1]) } },
            onClose = { account = false },
        )
    }
}

/** A small dialog of text fields with "Probar llave" (when [onTest] is set), "Cancelar" and "Guardar". */
@Composable
private fun TvTextDialog(
    title: String,
    labels: List<String>,
    secret: List<Boolean>,
    onTest: (suspend (List<String>) -> String)?,
    onSave: suspend (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val first = remember { FocusRequester() }
    var values by remember { mutableStateOf(List(labels.size) { "" }) }
    var note by remember { mutableStateOf<String?>(null) }
    FocusWhenReady(first)
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier.width(560.dp).clip(RoundedCornerShape(16.dp)).background(ArkivSurface)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = Color.White)
            labels.forEachIndexed { i, label ->
                OutlinedTextField(
                    value = values[i],
                    onValueChange = { v -> values = values.toMutableList().also { it[i] = v } },
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation = if (secret[i]) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secret[i]) KeyboardType.Password else KeyboardType.Text,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth().dpadLeavesTheField(focusManager)
                        .let { if (i == 0) it.focusRequester(first) else it },
                )
            }
            note?.let { Text(it, color = ArkivTextSecondary) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                if (onTest != null) {
                    TvCompactAction(label = OnlineKeysCopy.TEST, enabled = values[0].isNotBlank()) {
                        note = OnlineKeysCopy.TESTING
                        val v = values
                        scope.launch { note = onTest(v) }
                    }
                }
                TvCompactAction(label = "Cancelar", onClick = onClose)
                TvCompactAction(label = OnlineKeysCopy.SAVE, enabled = values.all { it.isNotBlank() }) {
                    val v = values.map { it.trim() }
                    scope.launch {
                        onSave(v)
                        onClose()
                    }
                }
            }
        }
    }
}
