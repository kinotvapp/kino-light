package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a field holds, which decides the shortcut keys the on-screen keyboard adds to its letter layers. */
enum class TvTextKind { TEXT, EMAIL, URL, NUMBER }

/**
 * One field of [TvTextInputDialog]. [initial] is what it starts with (a secret the app cannot show, like a
 * subtitle key, starts empty); [required] fields must be non-blank before "Guardar" turns on; [hint] is the
 * example shown while the field is empty.
 */
data class TvTextInput(
    val label: String,
    val initial: String = "",
    val secret: Boolean = false,
    val kind: TvTextKind = TvTextKind.TEXT,
    val required: Boolean = false,
    val hint: String = "",
)

/** Keys the keyboard adds to its letter layers for [kind], so `@`, `.` or `/` need no trip to the symbols layer. */
internal fun tvTextKeyboardExtras(kind: TvTextKind): List<Char> = when (kind) {
    TvTextKind.EMAIL -> listOf('@', '.')
    TvTextKind.URL -> listOf('.', '/', ':', '-')
    TvTextKind.TEXT, TvTextKind.NUMBER -> emptyList()
}

/** "Guardar" is on once every required field has something in it (optional fields may be left, or made, empty). */
internal fun tvTextCanSave(fields: List<TvTextInput>, values: List<String>): Boolean =
    fields.indices.all { i -> !fields[i].required || values.getOrNull(i).orEmpty().isNotBlank() }

/** What a field chip draws for [value]: dots while a secret is hidden, the text otherwise. */
internal fun tvTextChipValue(value: String, secret: Boolean, revealed: Boolean): String =
    if (secret && !revealed) "•".repeat(value.length) else value

/**
 * THE way to type text on TV: a full-screen dialog over whatever asked for it, with the app's on-screen
 * keyboard ([TvKeyboardAndFields], ⌨ key included for the TV's own keyboard) on the left and the [fields]
 * on the right. Exactly one field is active and receives the keys: moving the D-pad onto a field makes it the
 * active one. The text never lives in a system text field on the screen behind, so the D-pad is never
 * trapped by a closed IME there; that screen only shows buttons ([TvTextFieldButton]).
 *
 * [onTest] adds "Probar" (enabled while the first field has text): its answer is shown under the fields.
 * [onSave] gets the values trimmed, in [fields] order, and the dialog closes once it returns. "Cancelar" and
 * Back ([onDismiss]) close it without saving.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TvTextInputDialog(
    title: String,
    fields: List<TvTextInput>,
    onDismiss: () -> Unit,
    onSave: suspend (List<String>) -> Unit,
    subtitle: String = "",
    saveLabel: String = "Guardar",
    testLabel: String = "Probar",
    onTest: (suspend (List<String>) -> String)? = null,
) {
    val scope = rememberCoroutineScope()
    val state = remember {
        TvFocusedFields(0).also { s -> fields.forEachIndexed { i, f -> s.write(i, f.initial) } }
    }
    var keyboardMode by remember { mutableStateOf(TvKeyboardMode.LOWER) }
    var revealed by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val values = fields.indices.map { state.value(it) }
    val active = fields.getOrNull(state.active)

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = ArkivBlack),
        ) {
            TvKeyboardAndFields(
                title = title,
                subtitle = subtitle,
                keyboardMode = keyboardMode,
                onMode = { keyboardMode = it },
                activeText = state.activeValue(),
                onActiveTextChange = { state.writeActive(it); note = null },
                extras = tvTextKeyboardExtras(active?.kind ?: TvTextKind.TEXT),
                activeSecret = active?.secret == true && !revealed,
            ) { firstFieldFocus ->
                fields.forEachIndexed { i, f ->
                    TvFieldChip(
                        label = f.label + if (f.required) " *" else "",
                        value = tvTextChipValue(state.value(i), f.secret, revealed).ifEmpty { f.hint },
                        active = state.active == i,
                        masked = f.secret && !revealed,
                        onFocus = { state.focus(i) },
                        modifier = if (i == 0) Modifier.focusRequester(firstFieldFocus) else Modifier,
                    )
                }
                if (fields.any { it.secret }) {
                    TvPasswordVisibilityButton(visible = revealed, onToggle = { revealed = !revealed })
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary) }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    if (onTest != null) {
                        TvCompactAction(label = testLabel, enabled = !busy && values.firstOrNull().orEmpty().isNotBlank()) {
                            val v = values.map { it.trim() }
                            busy = true
                            note = "Probando…"
                            scope.launch {
                                try { note = onTest(v) } finally { busy = false }
                            }
                        }
                    }
                    TvCompactAction(label = "Cancelar", enabled = !busy, onClick = onDismiss)
                    TvCompactAction(label = saveLabel, enabled = !busy && tvTextCanSave(fields, values)) {
                        val v = values.map { it.trim() }
                        busy = true
                        scope.launch {
                            try { onSave(v) } finally { busy = false }
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

/** Mask a saved secret is drawn with on a [TvTextFieldButton]; the real value never is. */
internal const val TV_SECRET_MASK = "••••••••"

/** Mask for a secret the screen only knows is SET, never its value (the user's own subtitle keys). */
internal const val TV_SECRET_SAVED = "Guardada"

/**
 * The lines of a [TvTextFieldButton]: [label] small on top (null when empty, then [value] IS the label),
 * [value] large, [hint] a small line under an empty field, and [alert] when a required field is empty.
 */
data class TvFieldButtonText(val label: String?, val value: String, val hint: String?, val alert: Boolean)

/**
 * The text a field button shows. Empty: the label alone ("*" kept when required, which also raises [alert])
 * plus [hint] if any. Set: the label on top and the value, on one line, or [secretMask] for a [secret] so the
 * real value is never drawn.
 */
internal fun tvFieldButtonText(
    label: String,
    value: String,
    secret: Boolean = false,
    required: Boolean = false,
    hint: String = "",
    secretMask: String = TV_SECRET_MASK,
): TvFieldButtonText {
    val shownLabel = label + if (required) " *" else ""
    return if (value.isBlank()) {
        TvFieldButtonText(label = null, value = shownLabel, hint = hint.trim().ifEmpty { null }, alert = required)
    } else {
        val shown = if (secret) secretMask else value.trim().replace('\n', ' ')
        TvFieldButtonText(label = shownLabel, value = shown, hint = null, alert = false)
    }
}

/**
 * A text field as the TV shows it outside [TvTextInputDialog]: a focusable button with the label and the
 * current value ([tvFieldButtonText]); [onClick] opens the dialog. [error] (a validation message) is drawn
 * under it and, like an empty required field, gives it a red border.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvTextFieldButton(
    text: TvFieldButtonText,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    masked: Boolean = false,
) {
    val alert = text.alert || error != null
    Column(modifier) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().let { if (masked) it.semantics { password() } else it },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = ArkivSurfaceHigh,
                focusedContainerColor = ArkivRed,
                pressedContainerColor = ArkivRed,
                contentColor = Color.White,
                focusedContentColor = Color.White,
                pressedContentColor = Color.White,
            ),
            border = ClickableSurfaceDefaults.border(
                border = if (alert) Border(BorderStroke(2.dp, ArkivRed), shape = RoundedCornerShape(10.dp)) else Border.None,
                focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(10.dp)),
                pressedBorder = Border.None,
            ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                text.label?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(text.value, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                text.hint?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ArkivRed, modifier = Modifier.padding(top = 4.dp, start = 4.dp)) }
    }
}

/**
 * A single text field on TV, the common case: a [TvTextFieldButton] that opens a one-field
 * [TvTextInputDialog] with [value], and [onSave] with what was typed. Focus goes back to the button when the
 * dialog closes, so the D-pad continues where it was. [buttonText] overrides what the button shows (a secret
 * the screen only knows is set).
 */
@Composable
fun TvTextFieldEntry(
    label: String,
    value: String,
    onSave: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    kind: TvTextKind = TvTextKind.TEXT,
    required: Boolean = false,
    hint: String = "",
    error: String? = null,
    dialogTitle: String = label,
    dialogSubtitle: String = "",
    buttonText: TvFieldButtonText? = null,
    onTest: (suspend (String) -> String)? = null,
    initialInDialog: String = value,
) {
    var open by remember { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(false) }
    val buttonFocus = remember { FocusRequester() }
    TvTextFieldButton(
        text = buttonText ?: tvFieldButtonText(label, value, secret, required, hint),
        onClick = { open = true },
        modifier = modifier.focusRequester(buttonFocus),
        error = error,
        masked = secret,
    )
    if (open) {
        TvTextInputDialog(
            title = dialogTitle,
            subtitle = dialogSubtitle,
            fields = listOf(TvTextInput(label, initialInDialog, secret, kind, required, hint)),
            onDismiss = { open = false; refocus = true },
            onSave = { onSave(it[0]) },
            onTest = onTest?.let { test -> { v: List<String> -> test(v[0]) } },
        )
    }
    LaunchedEffect(refocus) {
        if (refocus) {
            refocusWhenReady(buttonFocus)
            refocus = false
        }
    }
}

/** Puts focus back on [requester] once the dialog window that held it is gone (retried: it takes a frame or two). */
internal suspend fun refocusWhenReady(requester: FocusRequester) {
    repeat(20) {
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
        delay(50)
    }
}
