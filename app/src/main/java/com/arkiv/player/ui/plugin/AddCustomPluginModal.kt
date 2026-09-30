package com.arkiv.player.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * The phone's "Agregar" modal: add a plugin by its GitHub `usuario/repositorio`. It owns no state: the
 * address, the busy flag and the message are the view model's ([PluginsUiState]), so what was typed
 * survives the consent sheet taking the screen over and being cancelled (see [addModalVisible]).
 *
 * [message] is the answer of the last try (an unknown or refused repository) and is drawn under the field
 * it is about, inside the modal, so it is never hidden behind it. While [busy] the field can be read but
 * not edited (it keeps its focus, so the keyboard stays) and "Agregar" reads "Revisando…". Done on the
 * keyboard does what "Agregar" does, under the same rule ([canSubmitCustom]). [onDismiss] is Cancelar, a
 * tap outside and system Back.
 */
@Composable
internal fun AddCustomPluginModal(
    address: String,
    busy: Boolean,
    message: String?,
    onAddressChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val addressFocus = remember { FocusRequester() }
    // The modal exists to type one thing: the field starts focused (retried until the dialog window is composed).
    FocusWhenReady(addressFocus)
    val canSubmit = canSubmitCustom(address, busy)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ArkivSurface,
        titleContentColor = Color.White,
        title = { Text("Agregar un plugin") },
        text = {
            // Scrolls: at a large font, or with the keyboard up in landscape, the dialog is shorter than its content.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Escribe usuario/repositorio de GitHub, por ejemplo kinotvapp/kino-plugin-archive. Antes de " +
                        "instalar vas a ver con qué sitios se conecta.",
                    style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary,
                )
                Text(
                    buildAnnotatedString {
                        append("También puedes instalar un scraper de ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Nuvio") }
                        append(", por ejemplo tapframe/nuvio-providers.")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    label = { Text("usuario/repositorio") },
                    singleLine = true,
                    readOnly = busy,
                    isError = message != null,
                    supportingText = message?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(addressFocus),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArkivRed)
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = canSubmit) { Text(if (busy) "Revisando…" else "Agregar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
