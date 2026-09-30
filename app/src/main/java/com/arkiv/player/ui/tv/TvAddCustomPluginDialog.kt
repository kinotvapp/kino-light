package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.plugin.canSubmitCustom
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * The TV's "Agregar" dialog: add a plugin by its GitHub `usuario/repositorio`, the same content and rules as
 * the phone's ([com.arkiv.player.ui.plugin.AddCustomPluginModal]). It owns no state: the address, the busy
 * flag and the message are the view model's, so what was typed survives the consent sheet taking the
 * screen over and being cancelled (see [com.arkiv.player.ui.plugin.addModalVisible]).
 *
 * [message] is the answer of the last try (an unknown or refused repository), drawn under the field it is
 * about, above the buttons, inside the dialog. The content scrolls and the buttons scroll into view when they
 * take focus, so they stay reachable with the keyboard up. While [busy] the field can be read but not edited
 * and "Agregar" reads "Revisando…". Done on the keyboard does what "Agregar" does, under the same rule
 * ([canSubmitCustom]), and nothing at all on an empty field. [onDismiss] is "Cancelar" and system Back (which
 * first closes the keyboard, if it is up); the caller then clears the address ([com.arkiv.player.ui.plugin.addressAfterDialogDismissed]).
 *
 * The field takes focus as soon as the dialog opens, keyboard included. That is deliberate and only
 * possible here: on the Plugins screen itself no text field is ever focused by itself, but this dialog exists
 * to type one thing. D-pad Up and Down always leave the field ([dpadLeavesTheField]): Down goes to "Agregar"
 * (or to "Cancelar" while there is nothing to add), since a closed keyboard would otherwise trap focus in it.
 * Both buttons stay focusable at any time ([TvCompactAction]).
 */
@Composable
internal fun TvAddCustomPluginDialog(
    address: String,
    busy: Boolean,
    message: String?,
    onAddressChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val addressFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    // Retried until the dialog's window is composed (its nodes do not exist on the first frame).
    FocusWhenReady(addressFocus)
    val canSubmit = canSubmitCustom(address, busy)

    Dialog(onDismissRequest = onDismiss) {
        // Read INSIDE the dialog: it is a window of its own, with its own focus manager.
        val focusManager = LocalFocusManager.current
        Column(
            // Scrolls: the TV keyboard takes the lower half of the screen and the dialog is centred, so the window is
            // shorter than the content whenever it is up (as the phone modal's, and the consent dialog's). The vertical
            // padding is kept small for the same reason, so that with a two-line error the field, the error and the
            // buttons still fit above the keyboard without scrolling (measured on the KALLEY: 1280x720 px, keyboard from y=400).
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ArkivSurface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Agregar un plugin", style = MaterialTheme.typography.headlineSmall, color = Color.White)
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
                // Done on an empty field does nothing at all: the handler replaces the default action, so the keyboard
                // stays up and focus stays in the field.
                keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(addressFocus)
                    .focusProperties { down = if (canSubmit) addFocus else cancelFocus }
                    .dpadLeavesTheField(focusManager),
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArkivRed)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TvCompactAction(label = "Cancelar", modifier = Modifier.focusRequester(cancelFocus), onClick = onDismiss)
                TvCompactAction(
                    label = if (busy) "Revisando…" else "Agregar",
                    modifier = Modifier.focusRequester(addFocus),
                    enabled = canSubmit,
                    onClick = onSubmit,
                )
            }
        }
    }
}
