package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/**
 * On-screen keyboard + remote control for TV screens that type text. Today the only caller is
 * [TvMagisLinkOffer] (Magis email/password): this used to live duplicated inside `PanelDeLogin`
 * (Kino login, `TvPantallaDeEntrada.kt`) and was extracted here to avoid repeating a focus,
 * keyboard, or field-chip fix across the two screens -`PanelDeLogin` and `TvPantallaDeEntrada.kt`
 * were removed entirely in Task 9 (sub-project 2B) along with the rest of Kino's login, so that
 * reason no longer applies, but the file stayed as-is in case sharing this with a second screen
 * is needed again-.
 */

/**
 * Which field has the on-screen keyboard's focus, and each one's value. Generic over the calling
 * screen's field enum (the Magis offer has two -email/password-) because the MECHANISM doesn't
 * depend on which they are: the keyboard writes onto "the field that currently has focus", with
 * no separate click to "enter" the field (same gesture as `TvSeasonChip` in `TvSearchScreen`).
 */
class TvFocusedFields<F>(initial: F) {
    var active: F by mutableStateOf(initial)
        private set
    private val values = mutableStateMapOf<F, String>()

    fun value(field: F): String = values[field].orEmpty()
    fun activeValue(): String = value(active)
    fun write(field: F, new: String) { values[field] = new }
    fun writeActive(new: String) = write(active, new)
    fun focus(field: F) { active = field }
}

/** `remember` for [TvFocusedFields] tied to the composition -same pattern as any other
 *  remembered Compose state, just packaged because they're two pieces (focus + values) that
 *  always travel together. */
@Composable
fun <F> rememberTvFocusedFields(initial: F): TvFocusedFields<F> = remember { TvFocusedFields(initial) }

/**
 * Width split between the keyboard and the fields (Task 11), by WEIGHT rather than a fixed dp: a
 * reference TV is 960.dp wide (1920px at density 320, measured on the Fire TV), and the row doesn't
 * wrap, so any fixed pair of widths that overshoots that draws off-screen — the fields column (and
 * the "Crear cuenta" button inside it) got clipped on the right, making the sign-up option look
 * like it didn't exist from the couch.
 *
 * The keyboard gets more weight than the fields on purpose: it's what gets used KEY BY KEY with the
 * remote -every extra dp on a key is a bigger, easier target at sofa distance- while the fields only
 * DISPLAY text already typed -legible at a glance is enough, they don't need to cross the screen-.
 */
internal const val KEYBOARD_WEIGHT = 1.2f
internal const val FIELDS_WEIGHT = 1f

/**
 * Two-column layout -keyboard fixed on the left, fields on the right- with initial focus on the
 * first field (with the same retry `PanelDeLogin` already used: requesting it on the first
 * composition fails silently because the node isn't placed yet, confirmed on the Fire TV).
 * [fields] receives the first field's [FocusRequester] so whoever builds the column decides which
 * chip it goes on -normally the first one-.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvKeyboardAndFields(
    title: String,
    subtitle: String,
    keyboardMode: TvKeyboardMode,
    onMode: (TvKeyboardMode) -> Unit,
    activeText: String,
    onActiveTextChange: (String) -> Unit,
    extras: List<Char> = emptyList(),
    fields: @Composable ColumnScope.(firstFieldFocus: FocusRequester) -> Unit,
) {
    val firstFieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { firstFieldFocus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }

    // Whether the system keyboard has taken over from the on-screen grid (TvKey.NativeKeyboard).
    // Lives here, not in TvKeyboard itself: TvKeyboard only reports the person asked for it, this
    // composable is the one that knows how to actually show a real text field in its place.
    var nativeKeyboardActive by remember { mutableStateOf(false) }
    if (nativeKeyboardActive) BackHandler { nativeKeyboardActive = false }

    // Full screen: with nothing on top, the keyboard gets its full height and its mode keys don't
    // get clipped against the bottom edge -which on a TV falls right in the overscan zone-.
    //
    // Title and subtitle live above the fields column, not in their own full-width row: sitting
    // there, above the keyboard, they read as squeezed against it. Above the fields they read as
    // what they are -context for what's about to be typed-.
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxHeight().weight(KEYBOARD_WEIGHT)
                    .padding(start = 48.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
            ) {
                if (nativeKeyboardActive) {
                    TvNativeKeyboardField(
                        text = activeText,
                        onTextChange = onActiveTextChange,
                        onBack = { nativeKeyboardActive = false },
                    )
                } else {
                    TvKeyboard(
                        text = activeText,
                        onTextChange = onActiveTextChange,
                        // The extras (e.g. `@`/`.` for email) are decided by the caller, based on
                        // which field is active: here they're just passed through as they arrive.
                        rows = tvKeyboardRows(keyboardMode, extras = extras),
                        onMode = onMode,
                        onNativeKeyboard = { nativeKeyboardActive = true },
                    )
                }
            }
            // Width by WEIGHT and not fixed: see KEYBOARD_WEIGHT's KDoc. With `width(520.dp)` the
            // column ran off the screen and took the create-account button with it.
            Column(
                Modifier.fillMaxHeight().weight(FIELDS_WEIGHT).padding(top = 24.dp, end = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
                fields(firstFieldFocus)
            }
        }
    }
}

/**
 * [TvKeyboard] with the same "switch to the system keyboard" door the login's [TvKeyboardAndFields]
 * offers, for the search screens that embed the grid directly (catalog, live guide, channel
 * drawer). They pass exactly what they passed to [TvKeyboard] before; this owns the ⌨ key's swap to
 * a real text field (auto-focused, so Android shows the TV's own keyboard) and back. The grid is
 * [TV_KEYBOARD_ROWS], which carries the ⌨ key; the swap state lives here, not in [TvKeyboard].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvKeyboardWithNative(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    firstKeyFocus: FocusRequester? = null,
) {
    var nativeKeyboardActive by remember { mutableStateOf(false) }
    if (nativeKeyboardActive) {
        BackHandler { nativeKeyboardActive = false }
        TvNativeKeyboardField(
            text = text,
            onTextChange = onTextChange,
            onBack = { nativeKeyboardActive = false },
        )
    } else {
        TvKeyboard(
            text = text,
            onTextChange = onTextChange,
            modifier = modifier,
            firstKeyFocus = firstKeyFocus,
            onNativeKeyboard = { nativeKeyboardActive = true },
        )
    }
}

/**
 * Stands in for [TvKeyboard] once [TvKey.NativeKeyboard] is pressed: a real text field, so
 * focusing it makes Android show the TV's own system keyboard -useful with a paired phone,
 * Bluetooth keyboard, or a voice-typing IME, all faster than the remote grid letter by letter.
 * Writes onto the exact same [text]/[onTextChange] the grid uses, so nothing downstream (the field
 * chips, validation) needs to know which one is active.
 *
 * [onBack] also fires on the remote's back button (see the `BackHandler` in [TvKeyboardAndFields]),
 * but a visible button next to the field says so on its own -- back undoing "whatever's open" isn't
 * obvious the first time, and there's nothing else on this screen to signal it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvNativeKeyboardField(text: String, onTextChange: (String) -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }
    Column {
        Text(
            "Escribiendo con el teclado del televisor.",
            style = MaterialTheme.typography.bodyMedium,
            color = ArkivTextSecondary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .background(ArkivSurfaceHigh, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .focusRequester(focus),
                textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                cursorBrush = SolidColor(ArkivRed),
                singleLine = true,
            )
            Surface(
                onClick = onBack,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = arkivTvSurfaceColors(),
                border = arkivTvSurfaceBorder(),
            ) {
                Text(
                    "Volver al teclado",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Field selector with the same gesture as `TvSeasonChip` (`TvSearchScreen`): moving focus there
 * ([onFocus]) is enough to make it the on-screen keyboard's destination, with no separate click
 * -but clicking also works, for whoever arrives with a direct click-. Shows the current value (or
 * a placeholder if empty) so the person can see what they've typed without guessing.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvFieldChip(
    label: String,
    value: String,
    active: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
) {
    Surface(
        onClick = onFocus,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() }
            .let { if (masked) it.semantics { password() } else it },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) ArkivRed.copy(alpha = 0.28f) else ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed.copy(alpha = 0.55f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
            Text(
                value.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/**
 * Show/hide password button, identical on both screens. The field that shows the value (a
 * [TvFieldChip] with `masked = !visible`) is the caller's responsibility -this is just the button
 * that toggles [visible]-.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvPasswordVisibilityButton(visible: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = arkivTvSurfaceColors(),
        border = arkivTvSurfaceBorder(),
    ) {
        Text(
            if (visible) "Ocultar contraseña" else "Mostrar contraseña",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
