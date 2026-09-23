package com.arkiv.player.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.magis.MagisAccount
import com.arkiv.player.data.magis.MagisException
import com.arkiv.player.data.magis.MagisSession
import kotlinx.coroutines.launch

/**
 * Phone counterpart of `TvMagisLinkOffer`: same three steps (choice, then whichever form it
 * needs), same backing calls (`MagisAccount.link`/`sendRegistrationCode`/`confirmRegistration`),
 * same copy. The differences are all about the input device: a phone has its own keyboard, so
 * there's no on-screen grid to build or a "which field is active" indirection to route keystrokes
 * through -- each field owns its own state and writes to it directly, like the rest of this
 * project's phone forms (`AccountSection`'s `UnlinkedSection`, which this replaces the inline form
 * of in favor of this shared screen -- see its own comment).
 */
private enum class MagisOfferStep { CHOICE, LOGIN, REGISTER_EMAIL, REGISTER_CODE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MagisLinkOffer(
    account: MagisAccount,
    onNotNow: () -> Unit,
    /** True once this device has actually seen the Magis portal reject account creation/login for
     *  this region (failure-driven, see `AppGraph.regionGeoBlocked` -- never a country check). When
     *  true, the CHOICE step hides "Iniciar sesión"/"Crear cuenta" (they would just error) and keeps
     *  only the skip control -- the way out stays reachable even in a blocked region. */
    regionGeoBlocked: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(MagisOfferStep.CHOICE) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Separate from `busy`: "Ahora no"/back never waited on the form's own submit before (see the
    // TextButton("Ahora no")'s own comment below), and linking the fallback account shouldn't
    // start blocking it now just because it's also a network call.
    var skipping by remember { mutableStateOf(false) }
    // The device minted for a pending registration, held ENTIRELY in memory -- see
    // MagisSession.PendingRegistration's own KDoc for why that's enough, no server needed.
    var pending by remember { mutableStateOf<MagisSession.PendingRegistration?>(null) }

    fun goTo(next: MagisOfferStep) {
        step = next
        error = null
    }

    /** What "Omitir por ahora"/"Ahora no"/back all call now: see [MagisAccount.linkFallbackAccount]'s
     *  KDoc for why skipping links a shared account instead of leaving the device without one. */
    fun skip() {
        if (skipping) return
        skipping = true
        scope.launch {
            // Best-effort: linking the shared fallback account must NEVER take the app down (native
            // crypto / portal / network can fail), and skipping must dismiss the offer either way.
            // login() guards itself the same way; skip() used to rely only on the callee's runCatching.
            runCatching { account.linkFallbackAccount() }
                .onFailure { android.util.Log.w("MagisLinkOffer", "skip: linkFallbackAccount failed", it) }
            onNotNow()
        }
    }

    // Same reasoning as the TV screen: back closes THIS screen (same intent as "Ahora no"), not
    // whatever's underneath it.
    BackHandler(onBack = ::skip)

    fun login() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                account.link(email.trim(), password)
                // No need to "close" anything here: the caller (SettingsScreen/PlayerScreen)
                // watches account.state and stops composing this screen on its own once it
                // becomes Linked.
            } catch (e: MagisException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    /** Requests the code. Also used to RESEND it from [MagisOfferStep.REGISTER_CODE]: each
     *  request mints a fresh device (see [MagisSession.sendRegistrationCode]), so a code already
     *  in flight stops being valid -- the code field is cleared so nobody resends an old one by
     *  mistake. */
    fun sendRegistrationCode() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                pending = account.sendRegistrationCode(email.trim())
                code = ""
                goTo(MagisOfferStep.REGISTER_CODE)
            } catch (e: MagisException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun confirmRegistration() {
        val current = pending ?: return
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                account.confirmRegistration(current, email.trim(), password, code.trim())
                // Same as login(): once confirmRegistration leaves the account Linked, this
                // screen stops composing itself. Nothing to close here.
            } catch (e: MagisException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    val canLogIn = !busy && email.isNotBlank() && password.isNotBlank()
    val canSendCode = !busy && email.isNotBlank()
    val canConfirmRegistration = !busy && code.isNotBlank() && password.isNotBlank()

    if (step == MagisOfferStep.CHOICE) {
        MagisOfferChoice(
            onLogIn = { goTo(MagisOfferStep.LOGIN) },
            onCreateAccount = {
                email = ""
                password = ""
                goTo(MagisOfferStep.REGISTER_EMAIL)
            },
            onSkip = ::skip,
            skipping = skipping,
            regionGeoBlocked = regionGeoBlocked,
        )
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text(
            when (step) {
                MagisOfferStep.LOGIN -> "Iniciar sesión en Xuper"
                MagisOfferStep.REGISTER_EMAIL -> "Crear tu cuenta de Xuper"
                MagisOfferStep.REGISTER_CODE -> "Confirma el código"
                MagisOfferStep.CHOICE -> "" // unreachable, handled above
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            when (step) {
                MagisOfferStep.LOGIN -> "Tiene que ser una cuenta que ya exista. Da acceso a tu plan de Xuper desde Kino."
                MagisOfferStep.REGISTER_EMAIL -> "Te vamos a mandar un código a este email para crear la cuenta."
                MagisOfferStep.REGISTER_CODE -> "Mira el código que te llegó y escríbelo aquí, junto con la contraseña que quieres para esta cuenta nueva."
                MagisOfferStep.CHOICE -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        // The email field shows up on LOGIN and REGISTER_EMAIL, never on REGISTER_CODE -- by
        // then it's already fixed; to correct it, "Volver" goes back to REGISTER_EMAIL.
        if (step == MagisOfferStep.LOGIN || step == MagisOfferStep.REGISTER_EMAIL) {
            OutlinedTextField(
                email,
                { email = it; error = null },
                label = { Text("Email de Xuper") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (step == MagisOfferStep.LOGIN) {
            PasswordField(
                password,
                { password = it; error = null },
                "Contraseña de Xuper",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        if (step == MagisOfferStep.REGISTER_CODE) {
            Text(
                "Código enviado a $email",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Si no lo ves en unos minutos, revisa la carpeta de spam o correo no deseado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                code,
                { code = it; error = null },
                label = { Text("Código") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(
                password,
                { password = it; error = null },
                "Contraseña nueva de Xuper",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            when (step) {
                MagisOfferStep.LOGIN -> {
                    Button(enabled = canLogIn, onClick = ::login) {
                        Text(if (busy) "Iniciando…" else "Iniciar sesión")
                    }
                    OutlinedButton(enabled = !busy, onClick = { goTo(MagisOfferStep.CHOICE) }) { Text("Volver") }
                }
                MagisOfferStep.REGISTER_EMAIL -> {
                    Button(enabled = canSendCode, onClick = ::sendRegistrationCode) {
                        Text(if (busy) "Enviando…" else "Enviar código")
                    }
                    OutlinedButton(enabled = !busy, onClick = { goTo(MagisOfferStep.CHOICE) }) { Text("Volver") }
                }
                MagisOfferStep.REGISTER_CODE -> {
                    Button(enabled = canConfirmRegistration, onClick = ::confirmRegistration) {
                        Text(if (busy) "Confirmando…" else "Confirmar")
                    }
                    OutlinedButton(enabled = !busy, onClick = ::sendRegistrationCode) {
                        Text(if (busy) "…" else "Reenviar código")
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            code = ""
                            pending = null
                            goTo(MagisOfferStep.REGISTER_EMAIL)
                        },
                    ) { Text("Corregir email") }
                }
                MagisOfferStep.CHOICE -> Unit // unreachable, handled above
            }
            // "Ahora no" doesn't depend on `busy`: if Magis is taking a while to respond, the
            // person still has to be able to leave -it's not a destructive button, so there's no
            // risk of leaving something half-done-. It has its own `skipping` instead (see skip()).
            TextButton(enabled = !skipping, onClick = ::skip) { Text(if (skipping) "…" else "Ahora no") }
        }
    }
}

/**
 * The initial choice, shown before any form: log in to an existing account, create a new one, or
 * skip entirely. No keyboard focus to fight for here (unlike the TV screen, which has to win a
 * race against the remote's own default focus) -- a phone shows nothing until a field is tapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MagisOfferChoice(
    onLogIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onSkip: () -> Unit,
    skipping: Boolean,
    regionGeoBlocked: Boolean,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Vincular tu cuenta de Xuper",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (regionGeoBlocked) {
                // Failure-driven (this device has actually seen the portal reject account
                // creation/login here, see AppGraph.regionGeoBlocked): "Iniciar sesión"/"Crear
                // cuenta" would just error, so they're hidden below -- but never the way out, Kino
                // plays fine without an account (the backup seed pool covers it).
                Text(
                    "En tu región no se puede crear ni iniciar sesión en una cuenta de Xuper por " +
                        "ahora. Kino funciona igual, sin cuenta.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    "Da acceso a tu plan de Xuper desde Kino, y hace falta para el canal en vivo. " +
                        "Puedes vincularla más adelante desde Ajustes cuando quieras.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Es opcional: puedes seguir viendo películas y series sin vincular tu cuenta.",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 24.dp),
            ) {
                if (!regionGeoBlocked) {
                    OutlinedButton(enabled = !skipping, onClick = onLogIn) { Text("Iniciar sesión") }
                    OutlinedButton(enabled = !skipping, onClick = onCreateAccount) { Text("Crear cuenta") }
                }
                // Filled/primary, not outlined: linking is optional, and the visually strongest
                // button being the one that asks nothing of you says so -- same reasoning as the
                // TV screen defaulting remote focus to "Omitir por ahora". `skip()` behind this
                // button is unchanged either way (still links the fallback account, best-effort).
                Button(enabled = !skipping, onClick = onSkip) {
                    Text(
                        when {
                            skipping -> "…"
                            regionGeoBlocked -> "Continuar sin cuenta"
                            else -> "Omitir por ahora"
                        },
                    )
                }
            }
        }
    }
}
