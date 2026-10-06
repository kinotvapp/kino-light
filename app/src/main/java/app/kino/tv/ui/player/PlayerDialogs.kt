package app.kino.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.kino.tv.ui.theme.KinoRed
import app.kino.tv.ui.theme.KinoSurface
import app.kino.tv.ui.theme.KinoTextSecondary
import kotlinx.coroutines.delay
import kotlin.math.abs

/** The copies of a video the "Servidor" section lists. */
internal val SERVERS = listOf("Opción 1", "Opción 2", "Opción 3")

/** One step of "Sincronizar subtítulos", and the bigger one. */
internal const val SUBTITLE_STEP_MS = 100
internal const val SUBTITLE_BIG_STEP_MS = 500

/** What the "Audio y subtítulos" menu shows for this video: the subtitle offset and the copy on screen. */
@Stable
internal class PlayerMenuState {
    var subtitleDelayMs by mutableIntStateOf(0)
    var server by mutableIntStateOf(0)

    /** The copy being opened after a pick, while its note shows; null when none is. */
    var trying by mutableStateOf<Int?>(null)
}

@Composable
internal fun rememberPlayerMenuState(): PlayerMenuState = remember { PlayerMenuState() }

/** "+0,5 s", "−1,2 s", "0 s". */
internal fun subtitleDelayLabel(ms: Int): String {
    if (ms == 0) return "0 s"
    val sign = if (ms > 0) "+" else "−"
    val tenths = abs(ms) / 100
    return "$sign${tenths / 10},${tenths % 10} s"
}

/** The note while another copy opens, as the automatic fallback words it. */
internal fun tryingServerNote(index: Int): String = "Probando otro servidor: ${SERVERS[index]} (${index + 1} de ${SERVERS.size})…"

/** "Audio y subtítulos": the copy (Servidor), the audio and subtitle tracks, and the subtitle sync. */
@Composable
internal fun AudioAndSubtitlesDialog(state: PlayerMenuState, onDismiss: () -> Unit) {
    val first = rememberDialogLanding()
    LaunchedEffect(state.trying) {
        val next = state.trying ?: return@LaunchedEffect
        delay(1_500)
        state.server = next
        state.trying = null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KinoSurface,
        title = { Text("Audio y subtítulos") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                MenuSectionTitle("Servidor", first = true)
                SERVERS.forEachIndexed { i, label ->
                    MenuOption(
                        (if (i == state.server) "✓ " else "") + label + (if (i == state.trying) " (abriendo)" else ""),
                        modifier = if (i == 0) first else Modifier,
                    ) { if (i != state.server && state.trying == null) state.trying = i }
                }
                state.trying?.let { MenuNote(tryingServerNote(it)) }

                MenuSectionTitle("Audio")
                MenuOption("✓ Original") {}

                MenuSectionTitle("Subtítulos del archivo")
                MenuNote("Este archivo no trae subtítulos embebidos.")
                MenuOption("✓ Desactivar") {}

                MenuSectionTitle("Sincronizar subtítulos: ${subtitleDelayLabel(state.subtitleDelayMs)}")
                MenuNote("Si el texto sale antes que la voz, súmale tiempo; si sale después, réstale.")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        -SUBTITLE_BIG_STEP_MS to "−0,5 s",
                        -SUBTITLE_STEP_MS to "−0,1 s",
                        SUBTITLE_STEP_MS to "+0,1 s",
                        SUBTITLE_BIG_STEP_MS to "+0,5 s",
                    ).forEach { (delta, label) ->
                        MenuOption(label, fill = false) { state.subtitleDelayMs += delta }
                    }
                }
                if (state.subtitleDelayMs != 0) MenuOption("Quitar el ajuste") { state.subtitleDelayMs = 0 }
            }
        },
        confirmButton = { MenuOption("Cerrar", fill = false, onClick = onDismiss) },
    )
}

/** "Corregir intro y outro": marks where the opening ends or the ending starts, at the current position. */
@Composable
internal fun SkipMarkersDialog(positionLabel: String, onPick: (intro: Boolean) -> Unit, onDismiss: () -> Unit) {
    val first = rememberDialogLanding()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KinoSurface,
        title = { Text("Corregir intro y outro") },
        text = {
            Column {
                MenuNote("Se marca en el punto donde vas ($positionLabel).")
                MenuOption("Setear intro (fin del opening)", modifier = first) { onPick(true) }
                MenuOption("Setear outro (inicio del ending)") { onPick(false) }
            }
        },
        confirmButton = { MenuOption("Cancelar", fill = false, onClick = onDismiss) },
    )
}

/**
 * The modifier for a dialog's first option: on a TV it takes the D-pad focus once the dialog's window has it (a few
 * frames late; earlier requests are dropped). On a touch screen nothing takes focus and this does nothing.
 */
@Composable
private fun rememberDialogLanding(): Modifier {
    val requester = remember { FocusRequester() }
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (held) return@LaunchedEffect
            runCatching { requester.requestFocus() }
            delay(50)
        }
    }
    return Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) held = true }
}

@Composable
private fun MenuSectionTitle(text: String, first: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = KinoRed,
        modifier = Modifier.padding(top = if (first) 0.dp else 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun MenuNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary, modifier = Modifier.padding(vertical = 4.dp))
}

/** A menu choice: tappable on a phone, and red while it holds the D-pad focus on a TV. */
@Composable
private fun MenuOption(label: String, modifier: Modifier = Modifier, fill: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = Color.White,
        maxLines = 2,
        modifier = modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) KinoRed else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}
