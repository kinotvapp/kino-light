package com.arkiv.player.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkiv.player.BuildConfig
import com.arkiv.player.data.credentials.CredentialsActivator
import com.arkiv.player.data.credentials.RemoteCredentialsStore
import com.arkiv.player.ui.theme.ArkivBlack
import kotlinx.coroutines.launch

private sealed interface ActivationUiState {
    data object Idle : ActivationUiState
    data object Loading : ActivationUiState
    data object Failed : ActivationUiState
}

/**
 * Shown at the root of navigation whenever [RemoteCredentialsStore.read] returns null: nothing
 * else in the app is reachable until activation succeeds. See
 * docs/superpowers/specs/2026-09-15-split-credential-activation-design.md, "Architecture", for the
 * exact flow this implements.
 */
@Composable
fun ActivationScreen(
    activator: CredentialsActivator,
    store: RemoteCredentialsStore,
    onActivated: () -> Unit,
) {
    var state by remember { mutableStateOf<ActivationUiState>(ActivationUiState.Idle) }
    // A non-blank BuildConfig.ACTIVATION_CODE means this build ships with the code baked in: pre-fill
    // it and hide the entry field below, so the person only presses "Activar". Blank keeps the
    // classic type-the-code screen. See app/build.gradle.kts (ACTIVATION_CODE).
    var code by remember { mutableStateOf(BuildConfig.ACTIVATION_CODE) }
    val prefilled = BuildConfig.ACTIVATION_CODE.isNotBlank()
    val codeValid = CredentialsActivator.normalizeCode(code) != null
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity

    fun activate() {
        if (!codeValid) return
        state = ActivationUiState.Loading
        scope.launch {
            val credentials = activator.activate(code)
            if (credentials != null) {
                store.save(credentials)
                onActivated()
            } else {
                state = ActivationUiState.Failed
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ArkivBlack).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Kino conecta con servicios de terceros para poder funcionar, no aloja contenido propio. " +
                "No apoyamos la piratería: te invitamos siempre a ver películas y series por canales " +
                "legales. Al activar, entiendes que decides usar estos servicios bajo tu propia " +
                "responsabilidad, y que la app no responde por su uso.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (state is ActivationUiState.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 24.dp),
                color = Color.White,
            )
            return@Column
        }
        // Hidden when the code is baked in (prefilled): nothing to type, only "Activar" below.
        if (!prefilled) {
            OutlinedTextField(
                code,
                { code = it; state = ActivationUiState.Idle },
                label = { Text("Código de activación") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { activate() }),
                modifier = Modifier.padding(top = 24.dp).fillMaxWidth(0.8f),
            )
        }
        if (state is ActivationUiState.Failed) {
            Text(
                "No se pudo activar. Revisa el código e intenta de nuevo.",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(0.8f),
        ) {
            Button(onClick = ::activate, enabled = codeValid, modifier = Modifier.weight(1f)) {
                Text("Activar")
            }
            OutlinedButton(
                onClick = { activity?.finishAndRemoveTask() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Cerrar")
            }
        }
    }
}
