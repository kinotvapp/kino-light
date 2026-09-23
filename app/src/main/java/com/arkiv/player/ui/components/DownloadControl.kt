package com.arkiv.player.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.local.DownloadAction
import com.arkiv.player.data.local.DownloadConfirmation
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.local.DownloadLabel
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.theme.NucDownloadedGreen

/**
 * A row's download state + what to do about it, to hand to a row in one go.
 *
 * Exists because in the source picker every row needs the same four things, and building them at
 * each call site (three source types × two screens) was pure repetition.
 */
@Immutable
data class RowDownload(
    val state: DownloadDisplayState,
    val onDownload: () -> Unit,
    val onRetry: () -> Unit,
    val onRequestAction: (DownloadAction) -> Unit,
)

/** See [DownloadControl]. */
@Composable
fun DownloadControl(download: RowDownload, enabled: Boolean = true) = DownloadControl(
    state = download.state,
    onDownload = download.onDownload,
    onRetry = download.onRetry,
    onRequestAction = download.onRequestAction,
    enabled = enabled,
)

/**
 * A row's download control, whether from the library or the source picker: a single 48dp slot
 * that says what the download is doing AND lets you do whatever fits that state.
 *
 * Lives here and not in each screen because both have to behave the same: the source picker
 * offering only "download" --no queue, no progress, no cancel-- was half the function in half
 * the app.
 */
@Composable
fun DownloadControl(
    state: DownloadDisplayState,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    /** Cancel / remove from queue / delete. Whoever receives this has to CONFIRM it before doing it. */
    onRequestAction: (DownloadAction) -> Unit,
    enabled: Boolean = true,
) {
    when (state) {
        // GREEN trash can, not a checkmark: the green still says "you already have it" and the icon
        // says what can be done about it. A checkmark occupied the slot without offering anything.
        DownloadDisplayState.Done -> IconButton(
            onClick = { onRequestAction(DownloadAction.DELETE) },
            enabled = enabled,
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Descargado. Tocar para borrarlo del dispositivo",
                tint = NucDownloadedGreen,
            )
        }
        // Progress ring with an X INSIDE: the X is the control, the ring is the state. With just the
        // percentage, nothing on screen said that number was a button.
        is DownloadDisplayState.Downloading -> IconButton(
            onClick = { onRequestAction(DownloadAction.CANCEL) },
            enabled = enabled,
        ) {
            RingWithX(state.fraction, ArkivRed, "Bajando. Tocar para cancelar la descarga")
        }
        DownloadDisplayState.Queued -> IconButton(
            onClick = { onRequestAction(DownloadAction.REMOVE_FROM_QUEUE) },
            enabled = enabled,
        ) {
            RingWithX(null, ArkivTextSecondary, "En cola. Tocar para sacarla de la cola")
        }
        // A failure has to be visible AND undoable right here. It used to go back to showing the
        // download button, identical to never having tried: you'd tap again, it would fail for the
        // same reason, and nothing on screen said so.
        is DownloadDisplayState.Failed -> IconButton(onClick = onRetry, enabled = enabled) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = state.reason?.let { "Falló: $it. Tocar para reintentar" }
                    ?: "Falló la descarga. Tocar para reintentar",
                tint = ArkivRed,
            )
        }
        // Not a failure and not downloading: waiting for the user to accept the size in Descargas,
        // which is where that confirmation lives.
        DownloadDisplayState.NeedsConfirmation -> Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Pesa mucho: confírmala en Descargas",
                tint = ArkivRed,
                modifier = Modifier.size(18.dp),
            )
        }
        DownloadDisplayState.NotDownloaded -> IconButton(onClick = onDownload, enabled = enabled) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Guardar en el dispositivo",
                tint = ArkivTextSecondary,
            )
        }
    }
}

/**
 * Progress ring with an X on top: state and cancel control in the same slot.
 * [fraction] null = indeterminate (queued, or downloading with no known total size).
 */
@Composable
private fun RingWithX(fraction: Float?, color: Color, description: String) {
    Box(contentAlignment = Alignment.Center) {
        if (fraction == null) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = color)
        } else {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = color,
                trackColor = Color(0x33FFFFFF),
            )
        }
        Icon(
            Icons.Default.Close,
            contentDescription = description,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Full-width download bar, to hang off a row's bottom edge.
 *
 * Indeterminate while there's no way to say how much is left (queued, or downloading with no known
 * total size) and determinate when there is: a bar stuck at 0% for minutes looks stuck. Gray for
 * what's waiting its turn, red for what's downloading now, full green for what's already on the
 * device, and full red for what failed -- the same colors as the Downloads screen.
 */
@Composable
fun DownloadBar(state: DownloadDisplayState) {
    val shape = Modifier.fillMaxWidth().height(3.dp)
    val track = Color(0x33FFFFFF)
    when (state) {
        DownloadDisplayState.NotDownloaded -> Unit
        DownloadDisplayState.Queued ->
            LinearProgressIndicator(color = ArkivTextSecondary, trackColor = track, modifier = shape)
        is DownloadDisplayState.Downloading -> {
            val fraction = state.fraction
            if (fraction == null) {
                LinearProgressIndicator(color = ArkivRed, trackColor = track, modifier = shape)
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = ArkivRed,
                    trackColor = track,
                    modifier = shape,
                )
            }
        }
        DownloadDisplayState.Done ->
            LinearProgressIndicator(progress = { 1f }, color = NucDownloadedGreen, trackColor = track, modifier = shape)
        is DownloadDisplayState.Failed ->
            LinearProgressIndicator(progress = { 1f }, color = ArkivRed, trackColor = track, modifier = shape)
        DownloadDisplayState.NeedsConfirmation ->
            LinearProgressIndicator(progress = { 1f }, color = ArkivRed.copy(alpha = 0.45f), trackColor = track, modifier = shape)
    }
}

/**
 * What the download is doing, IN WORDS ("Bajando 42%", "En cola", "Descargado", or the actual
 * failure reason). Shows nothing if there's no download.
 *
 * The ring and the bar say the same thing in colors and shapes, but that's only legible if you
 * already know what they mean; this reads without translating anything.
 */
@Composable
fun DownloadStatusLine(
    state: DownloadDisplayState,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    modifier: Modifier = Modifier,
) {
    val label = DownloadLabel.of(state) ?: return
    Text(
        label,
        style = style,
        color = when (state) {
            is DownloadDisplayState.Failed, DownloadDisplayState.NeedsConfirmation -> ArkivRed
            DownloadDisplayState.Done -> NucDownloadedGreen
            else -> ArkivTextPrimary
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The dialog that confirms a [DownloadAction]. `action` null = nothing to confirm. */
@Composable
fun DownloadConfirmDialog(
    action: DownloadAction?,
    chapterName: String?,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    if (action == null) return
    val text = DownloadConfirmation.text(action, chapterName)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(text.title) },
        text = { Text(text.body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(text.confirm) } },
        dismissButton = { TextButton(onClick = onClose) { Text(text.dismiss) } },
    )
}
