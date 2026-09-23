package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.magis.MagisAccount
import com.arkiv.player.data.magis.MagisException
import com.arkiv.player.data.magis.MagisSession
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The offer's own steps, shown in this order for someone who came in with no account and no
 * device to reuse: an explicit choice first (log in to an existing account, create a new one, or
 * skip), THEN whichever form that choice needs. Reused from the pre-`no server of our own` design
 * this project had before (see `MagisSession.sendRegistrationCode`'s KDoc for why account
 * creation can be 100% client-direct again), with one deliberate change: that version jumped
 * straight into the login form and buried "Crear cuenta"/"Ahora no" as secondary buttons beside
 * it. Presenting the choice up front first is clearer about what's actually being asked.
 */
private enum class MagisOfferStep { CHOICE, LOGIN, REGISTER_EMAIL, REGISTER_CODE }

/** Which field receives the on-screen keyboard's keys. CODE only exists in [MagisOfferStep.REGISTER_CODE]. */
private enum class MagisOfferField { EMAIL, PASSWORD, CODE }

/**
 * Lets someone link (or create) a Magis/Xuper account. Never shown on entering the app -- the TV
 * starts as a guest, no account prompt -- only from two places that ask for it on purpose:
 * `TvSettingsScreen`'s "Vincular cuenta" (voluntary, from Ajustes -> Cuenta), and `PlayerScreen`'s
 * on-demand prompt when a live channel actually needs a linked account
 * (`PlayerViewModel.needsMagisAccount`, see `liveErrorMessage`'s KDoc for why live needs one and
 * VOD doesn't).
 *
 * ### Why account creation is back
 *
 * Registration (email -> code -> new password -> login) used to need this project's own gateway
 * to remember which freshly-minted device a pending registration belonged to between requesting
 * the code and confirming it, and was given up when that gateway was removed (no server of our
 * own). It turns out every one of the calls involved already goes straight to the real Magis
 * portal; the gateway's only real job was holding three strings (`userId`/`userToken`/`sn`) for
 * up to 15 minutes -- something the app can hold in memory itself for the length of one screen's
 * flow, no server needed. See [MagisSession.sendRegistrationCode]/[MagisSession.confirmRegistration]
 * for the mechanism.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvMagisLinkOffer(
    account: MagisAccount,
    onNotNow: () -> Unit,
    /** True once this device has actually seen the Magis portal reject account creation/login for
     *  this region (failure-driven, see `AppGraph.regionGeoBlocked` -- never a country check). When
     *  true, the CHOICE step hides "Iniciar sesión"/"Crear cuenta" (they would just error) and keeps
     *  only the skip control -- the way out stays reachable even in a blocked region. */
    regionGeoBlocked: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val fields = rememberTvFocusedFields(MagisOfferField.EMAIL)
    var step by remember { mutableStateOf(MagisOfferStep.CHOICE) }
    var passwordVisible by remember { mutableStateOf(false) }
    var keyboardMode by remember { mutableStateOf(TvKeyboardMode.LOWER) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Separate from `busy`: "Ahora no"/back never waited on the form's own submit before (see
    // TvOfferAction("Ahora no")'s own comment below), and linking the fallback account shouldn't
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

    // BACK EXITS THIS SCREEN, not the app. `ArkivTvRoot` composes this offer and `return`s before
    // reaching its own BackHandler, so while it's shown none was set and the system took over
    // back: it closed Kino entirely. For whoever didn't want to link Magis, the only way to
    // continue was to leave the app and come back in.
    //
    // Does the same as "Omitir"/"Ahora no" on purpose: they're the same intent -- "not this, right
    // now" -- and having the button and the remote do different things would be worse than either.
    BackHandler(onBack = ::skip)

    fun login() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                account.link(fields.value(MagisOfferField.EMAIL).trim(), fields.value(MagisOfferField.PASSWORD))
                // No need to "close" anything here: the caller (TvSettingsScreen/PlayerScreen)
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
                pending = account.sendRegistrationCode(fields.value(MagisOfferField.EMAIL).trim())
                fields.write(MagisOfferField.CODE, "")
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
                account.confirmRegistration(
                    current,
                    fields.value(MagisOfferField.EMAIL).trim(),
                    fields.value(MagisOfferField.PASSWORD),
                    fields.value(MagisOfferField.CODE).trim(),
                )
                // Same as login(): once confirmRegistration leaves the account Linked, this
                // screen stops composing itself. Nothing to close here.
            } catch (e: MagisException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    val canLogIn = !busy &&
        fields.value(MagisOfferField.EMAIL).isNotBlank() &&
        fields.value(MagisOfferField.PASSWORD).isNotBlank()
    val canSendCode = !busy && fields.value(MagisOfferField.EMAIL).isNotBlank()
    val canConfirmRegistration = !busy &&
        fields.value(MagisOfferField.CODE).isNotBlank() &&
        fields.value(MagisOfferField.PASSWORD).isNotBlank()

    if (step == MagisOfferStep.CHOICE) {
        MagisOfferChoice(
            onLogIn = { goTo(MagisOfferStep.LOGIN) },
            onCreateAccount = {
                fields.write(MagisOfferField.EMAIL, "")
                fields.write(MagisOfferField.PASSWORD, "")
                goTo(MagisOfferStep.REGISTER_EMAIL)
            },
            onSkip = ::skip,
            skipping = skipping,
            regionGeoBlocked = regionGeoBlocked,
        )
        return
    }

    TvKeyboardAndFields(
        title = when (step) {
            MagisOfferStep.LOGIN -> "Iniciar sesión en Xuper"
            MagisOfferStep.REGISTER_EMAIL -> "Crear tu cuenta de Xuper"
            MagisOfferStep.REGISTER_CODE -> "Confirma el código"
            MagisOfferStep.CHOICE -> "" // unreachable, handled above
        },
        subtitle = when (step) {
            MagisOfferStep.LOGIN -> "Tiene que ser una cuenta que ya exista. Da acceso a tu plan de Xuper desde Kino."
            MagisOfferStep.REGISTER_EMAIL -> "Te vamos a mandar un código a este email para crear la cuenta."
            MagisOfferStep.REGISTER_CODE -> "Mira el código que te llegó y tipéalo aquí, junto con la contraseña que quieres para esta cuenta nueva."
            MagisOfferStep.CHOICE -> ""
        },
        keyboardMode = keyboardMode,
        onMode = { keyboardMode = it },
        activeText = fields.activeValue(),
        onActiveTextChange = { fields.writeActive(it); error = null },
        // To keep `@`/`.` visible without switching layers while typing the email.
        extras = if (fields.active == MagisOfferField.EMAIL) listOf('@', '.') else emptyList(),
    ) { firstFieldFocus ->
        // The email field shows up on LOGIN and REGISTER_EMAIL, never on REGISTER_CODE -- by
        // then it's already fixed; to correct it, "Volver" goes back to REGISTER_EMAIL.
        if (step == MagisOfferStep.LOGIN || step == MagisOfferStep.REGISTER_EMAIL) {
            TvFieldChip(
                label = "Email de Xuper",
                value = fields.value(MagisOfferField.EMAIL),
                active = fields.active == MagisOfferField.EMAIL,
                onFocus = { fields.focus(MagisOfferField.EMAIL) },
                modifier = Modifier.focusRequester(firstFieldFocus),
            )
        }

        if (step == MagisOfferStep.LOGIN) {
            TvFieldChip(
                label = "Contraseña de Xuper",
                value = if (passwordVisible) {
                    fields.value(MagisOfferField.PASSWORD)
                } else {
                    "•".repeat(fields.value(MagisOfferField.PASSWORD).length)
                },
                active = fields.active == MagisOfferField.PASSWORD,
                // Masked ONLY while hidden: `PasswordVisualTransformation` masks the drawing, not
                // the accessibility semantics.
                masked = !passwordVisible,
                onFocus = { fields.focus(MagisOfferField.PASSWORD) },
            )
            TvPasswordVisibilityButton(visible = passwordVisible, onToggle = { passwordVisible = !passwordVisible })
        }

        if (step == MagisOfferStep.REGISTER_CODE) {
            Text(
                "Código enviado a ${fields.value(MagisOfferField.EMAIL)}",
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
            )
            Text(
                "Si no lo ves en unos minutos, revisa la carpeta de spam o correo no deseado.",
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
            )
            TvFieldChip(
                label = "Código",
                value = fields.value(MagisOfferField.CODE),
                active = fields.active == MagisOfferField.CODE,
                onFocus = { fields.focus(MagisOfferField.CODE) },
                modifier = Modifier.focusRequester(firstFieldFocus),
            )
            TvFieldChip(
                label = "Contraseña nueva de Xuper",
                value = if (passwordVisible) {
                    fields.value(MagisOfferField.PASSWORD)
                } else {
                    "•".repeat(fields.value(MagisOfferField.PASSWORD).length)
                },
                active = fields.active == MagisOfferField.PASSWORD,
                masked = !passwordVisible,
                onFocus = { fields.focus(MagisOfferField.PASSWORD) },
            )
            TvPasswordVisibilityButton(visible = passwordVisible, onToggle = { passwordVisible = !passwordVisible })
        }

        error?.let {
            Text(
                it,
                color = ArkivRed,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // FlowRow and not Row: in a `Row` that overflows, Compose CLIPS the last child -- and the
        // last one is exactly the exit button. See the same reasoning already measured for this
        // column in TvKeyboardAndFields' KDoc (KEYBOARD_WEIGHT) and this screen's own history.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            when (step) {
                MagisOfferStep.LOGIN -> {
                    TvOfferAction(if (busy) "Iniciando…" else "Iniciar sesión", enabled = canLogIn, onClick = ::login)
                    TvOfferAction("Volver", enabled = !busy, onClick = { goTo(MagisOfferStep.CHOICE) })
                }
                MagisOfferStep.REGISTER_EMAIL -> {
                    TvOfferAction(if (busy) "Enviando…" else "Enviar código", enabled = canSendCode, onClick = ::sendRegistrationCode)
                    TvOfferAction("Volver", enabled = !busy, onClick = { goTo(MagisOfferStep.CHOICE) })
                }
                MagisOfferStep.REGISTER_CODE -> {
                    TvOfferAction(if (busy) "Confirmando…" else "Confirmar", enabled = canConfirmRegistration, onClick = ::confirmRegistration)
                    TvOfferAction(if (busy) "…" else "Reenviar código", enabled = !busy, onClick = ::sendRegistrationCode)
                    TvOfferAction(
                        "Corregir email",
                        enabled = !busy,
                        onClick = {
                            fields.write(MagisOfferField.CODE, "")
                            pending = null
                            goTo(MagisOfferStep.REGISTER_EMAIL)
                        },
                    )
                }
                MagisOfferStep.CHOICE -> Unit // unreachable, handled above
            }
            // "Ahora no" doesn't depend on `busy`: if Magis is taking a while to respond, the
            // person still has to be able to leave -it's not a destructive button, so there's no
            // risk of leaving something half-done-. It has its own `skipping` instead (see skip()).
            TvOfferAction(if (skipping) "…" else "Ahora no", enabled = !skipping, onClick = ::skip)
        }
    }
}

/**
 * The initial choice, shown before any form: log in to an existing account, create a new one, or
 * skip entirely. No keyboard here -- just the three actions, so the ask is legible before typing
 * anything.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MagisOfferChoice(
    onLogIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onSkip: () -> Unit,
    skipping: Boolean,
    regionGeoBlocked: Boolean,
) {
    // Default focus lands on "Omitir por ahora", not "Iniciar sesión": linking is optional, and
    // starting focus there says so without reading the paragraph. Logging in or creating an
    // account still take one press to reach, to the left (when they're shown at all).
    val skipFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { skipFocus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Vincular tu cuenta de Xuper",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
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
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    "Da acceso a tu plan de Xuper desde Kino, y hace falta para el canal en vivo. " +
                        "Puedes vincularla más adelante desde Ajustes cuando quieras.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Es opcional: puedes seguir viendo películas y series sin vincular tu cuenta.",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 24.dp),
            ) {
                if (!regionGeoBlocked) {
                    TvOfferAction("Iniciar sesión", enabled = !skipping, onClick = onLogIn)
                    TvOfferAction("Crear cuenta", enabled = !skipping, onClick = onCreateAccount)
                }
                TvOfferAction(
                    when {
                        skipping -> "…"
                        regionGeoBlocked -> "Continuar sin cuenta"
                        else -> "Omitir por ahora"
                    },
                    enabled = !skipping,
                    onClick = onSkip,
                    modifier = Modifier.focusRequester(skipFocus),
                )
            }
        }
    }
}

/** This screen's action buttons, all with the same look. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvOfferAction(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // No `Box(Modifier.fillMaxSize())` wrapper on purpose: inside a FlowRow with no fillMaxWidth
    // sibling to constrain it against (the choice screen's three buttons, unlike this screen's
    // other steps where a TvFieldChip's own fillMaxWidth does that job), that wrapper let an
    // unbounded Surface stretch edge to edge -- measured on a real device, only the FOCUSED
    // button did it, since focus is what triggers the size recomputation that exposed it.
    // No fixed `.height(...)` either: it doesn't match the Text's own padding + line height, and
    // Surface's content box aligns top-start, so the leftover became a gap at the BOTTOM of the
    // pill instead of splitting evenly -- measured on a real device. Sized to content, all three
    // buttons land at the same height on their own: same text style, same padding.
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = arkivTvSurfaceColors(),
        border = arkivTvSurfaceBorder(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}
