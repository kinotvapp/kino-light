package com.arkiv.player.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.db.SkipMarkerEntity
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Converts "m:ss" or "mm:ss" (or loose seconds) to milliseconds; null if empty/invalid. */
fun parseTimeToMs(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return if (t.contains(':')) {
        val parts = t.split(':')
        val mins = parts[0].trim().toLongOrNull() ?: return null
        val secs = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: return null
        (mins * 60 + secs) * 1000
    } else {
        t.toLongOrNull()?.let { it * 1000 }
    }
}

/** Formats ms to "m:ss" for display in the field. */
fun formatMsToTime(ms: Long?): String {
    if (ms == null) return ""
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
fun MarkersDialog(
    current: SkipMarkerEntity?,
    onDismiss: () -> Unit,
    onSave: (openingStartMs: Long?, openingEndMs: Long?, endingStartMs: Long?) -> Unit,
) {
    var openingStart by remember { mutableStateOf(formatMsToTime(current?.openingStartMs)) }
    var openingEnd by remember { mutableStateOf(formatMsToTime(current?.openingEndMs)) }
    var endingStart by remember { mutableStateOf(formatMsToTime(current?.endingStartMs)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Marcadores de intro/outro") },
        text = {
            Column {
                Text(
                    "Define los tiempos en mm:ss. Aplican a todos los episodios de esta serie.",
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TimeField("Intro — desde", openingStart, "0:00") { openingStart = it }
                TimeField("Intro — hasta (fin del opening)", openingEnd, "1:30") { openingEnd = it }
                TimeField("Outro — desde (inicio del ending)", endingStart, "22:00") { endingStart = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    parseTimeToMs(openingStart),
                    parseTimeToMs(openingEnd),
                    parseTimeToMs(endingStart),
                )
                onDismiss()
            }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun TimeField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
