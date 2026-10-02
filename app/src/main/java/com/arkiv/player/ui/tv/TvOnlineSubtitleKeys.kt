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
        TvTextInputDialog(
            title = OnlineKeysCopy.title(id),
            fields = listOf(TvTextInput("Llave (API key)", secret = true, required = true)),
            testLabel = OnlineKeysCopy.TEST,
            saveLabel = OnlineKeysCopy.SAVE,
            onTest = { values -> testOnlineKey(graph, id, values[0]) },
            onSave = { values -> withContext(Dispatchers.IO) { graph.subtitleKeys.setUserKey(id, values[0]) } },
            onDismiss = { editing = false },
        )
    }
    if (account) {
        TvTextInputDialog(
            title = OnlineKeysCopy.ACCOUNT,
            fields = listOf(
                TvTextInput(OnlineKeysCopy.USER, required = true),
                TvTextInput(OnlineKeysCopy.PASSWORD, secret = true, required = true),
            ),
            saveLabel = OnlineKeysCopy.SAVE,
            onSave = { values -> withContext(Dispatchers.IO) { graph.subtitleKeys.setAccount(values[0], values[1]) } },
            onDismiss = { account = false },
        )
    }
}
