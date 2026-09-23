package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.data.magis.MagisAccount
import com.arkiv.player.data.magis.MagisAccountState
import kotlinx.coroutines.launch

/**
 * The phone's "Ajustes → Cuenta" (Task 8, sub-project 2B): the link with Magis, standing on its
 * own. There's no more Kino login/logout here -this screen stopped taking an `AccountManager`-;
 * all that's left is linking or unlinking Magis directly against [MagisAccount], with no Kino
 * account in between. Task 9 (sub-project 2B) took `AccountManager` and the whole Kino login
 * (`ui/entrada/`) away, so this file also lost `AnonimoSection` -its only caller was that screen-;
 * [PasswordField] stays below because `MagisLinkOffer` (the login/registration forms, shared with
 * the entry-time offer) reuses it -- this file's own [UnlinkedSection] just opens that screen.
 */
@Composable
internal fun AccountSection(account: MagisAccount, onLink: () -> Unit, accountUnavailable: Boolean = false) {
    val state by account.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { account.refresh() }

    Text("Cuenta", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

    when (val s = state) {
        is MagisAccountState.Linked -> LinkedSection(account, s)
        MagisAccountState.None -> if (accountUnavailable) {
            Text(
                "Cuenta Xuper no disponible en tu región",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            UnlinkedSection(onLink)
        }
    }
}

@Composable
internal fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Ocultar contraseña" else "Mostrar contraseña",
                )
            }
        },
        // `PasswordVisualTransformation` masks what gets DRAWN, not what's exposed in the
        // accessibility tree: without this, the typed text comes out in the clear in a
        // `uiautomator dump` and to any installed accessibility service. Verified on the S24+ with
        // the password autofilled by the password manager -- it read out whole.
        //
        // When the password is in plain view (the eye icon), it isn't marked: there the person
        // already decided to show it, and marking it anyway would keep a screen reader from
        // dictating it.
        modifier = modifier.semantics { if (!visible) password() },
    )
}

@Composable
private fun LinkedSection(account: MagisAccount, state: MagisAccountState.Linked) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Text("Xuper vinculado como ${state.email}", style = MaterialTheme.typography.bodyMedium)

    OutlinedButton(
        enabled = !busy,
        onClick = {
            scope.launch {
                busy = true
                // try/finally, not try/catch: unlink() doesn't throw -MagisSession.logout() never
                // throws, it returns a MagisResult-, but without the finally an unexpected
                // exception left the button stuck on "Desvinculando…" forever. Same pattern as the
                // TV (TvSettingsAccount.TvLinkedSection).
                try {
                    account.unlink()
                } finally {
                    busy = false
                }
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text(if (busy) "Desvinculando…" else "Desvincular Xuper") }
}

/** Sub-block for an unlinked account: just opens [MagisLinkOffer] as an overlay (see
 *  `SettingsScreen`'s `linkingMagis`), same as `TvSettingsAccount`'s equivalent option -- the
 *  login/registration forms themselves live only in that shared screen now, not here. */
@Composable
private fun UnlinkedSection(onLink: () -> Unit) {
    OutlinedButton(onClick = onLink, modifier = Modifier.padding(top = 8.dp)) {
        Text("Vincular Xuper")
    }
}
