package com.arkiv.player.ui.tv

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.BuildConfig
import com.arkiv.player.data.credentials.CredentialsActivator
import com.arkiv.player.data.credentials.RemoteCredentialsStore
import com.arkiv.player.ui.theme.ArkivRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface TvActivationUiState {
    data object Idle : TvActivationUiState
    data object Loading : TvActivationUiState
    data object Failed : TvActivationUiState
}

private const val ACTIVATION_DISCLAIMER =
    "Kino conecta con servicios de terceros para poder funcionar, no aloja contenido propio. " +
        "No apoyamos la piratería: te invitamos siempre a ver películas y series por canales " +
        "legales. Al activar, entiendes que decides usar estos servicios bajo tu propia " +
        "responsabilidad, y que la app no responde por su uso."

/**
 * TV counterpart of [com.arkiv.player.ui.ActivationScreen]: the code is typed with the same
 * keyboard-plus-fields layout as the Xuper login ([TvKeyboardAndFields]), so there's one way to
 * type on this app's TV screens, not two.
 *
 * Initial focus lands on the code field, not on "Activar": with a code required, the remote can no
 * longer activate by accident, which is what used to justify putting the default focus on "Cerrar".
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvActivationScreen(
    activator: CredentialsActivator,
    store: RemoteCredentialsStore,
    onActivated: () -> Unit,
) {
    var state by remember { mutableStateOf<TvActivationUiState>(TvActivationUiState.Idle) }
    // A non-blank BuildConfig.ACTIVATION_CODE bakes the code into the build: pre-fill it and show the
    // keyboard-less screen below (only "Activar"), instead of the type-the-code keyboard. Blank keeps
    // the classic keyboard flow. See app/build.gradle.kts (ACTIVATION_CODE).
    var code by remember { mutableStateOf(BuildConfig.ACTIVATION_CODE) }
    val prefilled = BuildConfig.ACTIVATION_CODE.isNotBlank()
    var keyboardMode by remember { mutableStateOf(TvKeyboardMode.LOWER) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity

    fun activate() {
        if (state is TvActivationUiState.Loading) return
        // Unlike the phone, "Activar" isn't disabled for an invalid code: a disabled TV Surface
        // can't take focus, and the remote would silently skip over the button.
        if (CredentialsActivator.normalizeCode(code) == null) {
            state = TvActivationUiState.Failed
            return
        }
        state = TvActivationUiState.Loading
        scope.launch {
            val credentials = activator.activate(code)
            if (credentials != null) {
                store.save(credentials)
                onActivated()
            } else {
                state = TvActivationUiState.Failed
            }
        }
    }

    if (prefilled) {
        // Code baked in: no keyboard, just the disclaimer and an "Activar" button.
        TvActivationPrefilled(
            state = state,
            onActivate = ::activate,
            onClose = { activity?.finishAndRemoveTask() },
        )
        return
    }

    TvKeyboardAndFields(
        title = "Activar Kino",
        subtitle = "Escribe el código de activación.",
        keyboardMode = keyboardMode,
        onMode = { keyboardMode = it },
        activeText = code,
        onActiveTextChange = { code = it; state = TvActivationUiState.Idle },
        extras = listOf('_', '-'),
    ) { firstFieldFocus ->
        TvFieldChip(
            label = "Código de activación",
            value = code,
            active = true,
            onFocus = {},
            modifier = Modifier.focusRequester(firstFieldFocus),
        )
        if (state is TvActivationUiState.Failed) {
            Text(
                "No se pudo activar. Revisa el código e intenta de nuevo.",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivRed,
            )
        }
        // The buttons stay on screen while loading: removing the focused "Activar" dropped focus
        // to the keyboard's first key, so a failed attempt left the remote on "a".
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvActivationButton("Activar", onClick = ::activate)
            TvActivationButton("Cerrar", onClick = { activity?.finishAndRemoveTask() })
            if (state is TvActivationUiState.Loading) CircularProgressIndicator(Modifier.size(32.dp))
        }
        Text(
            ACTIVATION_DISCLAIMER,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Keyboard-less activation screen for a build that ships with the code baked in
 * (`BuildConfig.ACTIVATION_CODE`): just the disclaimer and the Activar/Cerrar buttons. Initial focus
 * lands on "Activar" -- there is nothing to type first, so the remote's first press activates.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvActivationPrefilled(
    state: TvActivationUiState,
    onActivate: () -> Unit,
    onClose: () -> Unit,
) {
    val activateFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { activateFocus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Activar Kino", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(ACTIVATION_DISCLAIMER, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        if (state is TvActivationUiState.Failed) {
            Text(
                "No se pudo activar. Revisa tu conexión e intenta de nuevo.",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivRed,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvActivationButton("Activar", onClick = onActivate, modifier = Modifier.focusRequester(activateFocus))
            TvActivationButton("Cerrar", onClick = onClose)
            if (state is TvActivationUiState.Loading) CircularProgressIndicator(Modifier.size(32.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvActivationButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp).width(160.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = arkivTvSurfaceColors(),
        border = arkivTvSurfaceBorder(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}
