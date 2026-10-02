package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.AppGraph
import com.arkiv.player.data.subtitles.KeySource
import com.arkiv.player.data.subtitles.ProviderAuth
import com.arkiv.player.data.subtitles.SubtitleProviderId
import com.arkiv.player.data.subtitles.SubtitleResult
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The online subtitle settings' text, shared by phone and TV. */
internal object OnlineKeysCopy {
    const val SECTION = "Subtítulos en línea"
    const val INTRO = "Kino busca subtítulos en OpenSubtitles y SubDL desde el menú «Audio y subtítulos» del " +
        "reproductor. Puedes usar tu propia llave de cada servicio: reemplaza la compartida de Kino."
    const val TEST = "Probar llave"
    const val SAVE = "Guardar"
    const val CLEAR = "Borrar mi llave"
    const val TESTING = "Probando…"
    const val TEST_OK = "La llave funciona"
    const val ACCOUNT = "Cuenta de OpenSubtitles (opcional: más descargas al día)"
    const val USER = "Usuario"
    const val PASSWORD = "Contraseña"
    const val SAVE_ACCOUNT = "Guardar cuenta"
    const val CLEAR_ACCOUNT = "Quitar cuenta"
    const val MOVE_UP = "Subir"

    fun title(id: SubtitleProviderId) = "Llave propia de ${id.label}"

    fun enabled(id: SubtitleProviderId, on: Boolean) = if (on) "${id.label}: activado" else "${id.label}: desactivado"

    /** Which key is in use, as the person reads it. */
    fun inUse(id: SubtitleProviderId, source: KeySource?): String = when (source) {
        KeySource.USER -> "Usando tu llave"
        KeySource.SHARED -> "Usando la llave compartida de Kino"
        null -> "Sin llave: agrega la tuya para buscar en ${id.label}"
    }
}

/** What a provider's settings row shows, read off the main thread. */
internal data class OnlineKeyView(val source: KeySource?, val hasUserKey: Boolean, val accountUser: String)

/** [id]'s current [OnlineKeyView]; re-read whenever a key changes. */
@Composable
internal fun rememberOnlineKeyView(graph: AppGraph, id: SubtitleProviderId): OnlineKeyView? {
    val version by graph.subtitleKeys.version.collectAsStateWithLifecycle()
    var view by remember(id) { mutableStateOf<OnlineKeyView?>(null) }
    LaunchedEffect(id, version) {
        view = withContext(Dispatchers.IO) {
            runCatching {
                val keys = graph.subtitleKeys
                OnlineKeyView(keys.effective(id)?.source, keys.userKey(id).isNotEmpty(), keys.account().first)
            }.getOrNull()
        }
    }
    return view
}

/**
 * "Probar llave": [typed] when the person typed one, else the key in use (with OpenSubtitles'
 * account, if set). The sentence to show.
 */
internal suspend fun testOnlineKey(graph: AppGraph, id: SubtitleProviderId, typed: String): String = withContext(Dispatchers.IO) {
    val keys = graph.subtitleKeys
    val auth = if (typed.isNotBlank()) {
        ProviderAuth(typed.trim())
    } else {
        keys.effective(id)?.auth ?: return@withContext OnlineKeysCopy.inUse(id, null)
    }
    val provider = graph.subtitleProviders.firstOrNull { it.id == id } ?: return@withContext OnlineKeysCopy.inUse(id, null)
    when (val r = runCatching { provider.test(auth) }.getOrNull()) {
        is SubtitleResult.Ok -> OnlineKeysCopy.TEST_OK
        is SubtitleResult.Failed -> r.failure.message
        null -> com.arkiv.player.data.subtitles.SubtitleFailure.UNAVAILABLE.message
    }
}

/** Ajustes → Subtítulos (phone): each provider's own key, its account, on/off and order. */
@Composable
internal fun OnlineSubtitleKeysSettings() {
    val graph = rememberGraph()
    val providers by graph.subtitleKeys.providers.collectAsStateWithLifecycle()
    Label(OnlineKeysCopy.SECTION)
    Text(OnlineKeysCopy.INTRO, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
    providers.order.forEachIndexed { index, id ->
        ProviderKeyEditor(graph, id, enabled = id !in providers.disabled, canMoveUp = index > 0)
    }
}

@Composable
private fun ProviderKeyEditor(graph: AppGraph, id: SubtitleProviderId, enabled: Boolean, canMoveUp: Boolean) {
    val view = rememberOnlineKeyView(graph, id)
    val scope = rememberCoroutineScope()
    var draft by remember(id) { mutableStateOf("") }
    var note by remember(id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(OnlineKeysCopy.title(id), style = MaterialTheme.typography.titleSmall, color = Color.White)
        Text(OnlineKeysCopy.inUse(id, view?.source), style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(OnlineKeysCopy.enabled(id, enabled), enabled) {
                graph.subtitleKeys.setProviders(graph.subtitleKeys.providers.value.toggled(id))
            }
            if (canMoveUp) Chip(OnlineKeysCopy.MOVE_UP, false) { graph.subtitleKeys.setProviders(graph.subtitleKeys.providers.value.movedUp(id)) }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.trim() },
            label = { Text(if (view?.hasUserKey == true) "Tu llave está guardada (escribe otra para cambiarla)" else "Llave (API key)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = draft.isNotBlank(), onClick = {
                val value = draft
                scope.launch {
                    withContext(Dispatchers.IO) { graph.subtitleKeys.setUserKey(id, value) }
                    draft = ""
                    note = null
                }
            }) { Text(OnlineKeysCopy.SAVE) }
            TextButton(onClick = {
                note = OnlineKeysCopy.TESTING
                val typed = draft
                scope.launch { note = testOnlineKey(graph, id, typed) }
            }) { Text(OnlineKeysCopy.TEST) }
            if (view?.hasUserKey == true) {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { graph.subtitleKeys.setUserKey(id, "") }
                        note = null
                    }
                }) { Text(OnlineKeysCopy.CLEAR) }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary) }
        if (id == SubtitleProviderId.OPENSUBTITLES) OpenSubtitlesAccountEditor(graph, view)
    }
}

@Composable
private fun OpenSubtitlesAccountEditor(graph: AppGraph, view: OnlineKeyView?) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text(OnlineKeysCopy.ACCOUNT, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary, modifier = Modifier.padding(top = 8.dp))
    if (!view?.accountUser.isNullOrBlank()) {
        Row {
            Text(view?.accountUser.orEmpty(), color = Color.White, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { graph.subtitleKeys.setAccount("", "") } } }) {
                Text(OnlineKeysCopy.CLEAR_ACCOUNT)
            }
        }
        return
    }
    OutlinedTextField(
        value = user, onValueChange = { user = it }, label = { Text(OnlineKeysCopy.USER) }, singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password, onValueChange = { password = it }, label = { Text(OnlineKeysCopy.PASSWORD) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(enabled = user.isNotBlank() && password.isNotEmpty(), onClick = {
        val u = user
        val p = password
        scope.launch {
            withContext(Dispatchers.IO) { graph.subtitleKeys.setAccount(u, p) }
            user = ""
            password = ""
        }
    }) { Text(OnlineKeysCopy.SAVE_ACCOUNT) }
}
