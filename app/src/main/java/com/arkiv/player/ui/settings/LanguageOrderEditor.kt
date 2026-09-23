package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkiv.player.playback.LangOrderEdits
import com.arkiv.player.playback.TrackLang
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Languages offered for audio. `DUAL` applies to releases' multi-audio tracks. */
val AUDIO_LANGUAGES = listOf(
    TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH,
    TrackLang.DUAL, TrackLang.ENGLISH, TrackLang.JAPANESE,
)

/** `DUAL` isn't offered for subtitles: there's no such thing as a "dual" text track. */
val SUBTITLE_LANGUAGES = listOf(
    TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH,
    TrackLang.ENGLISH, TrackLang.JAPANESE,
)

fun TrackLang.label(): String = when (this) {
    TrackLang.LATINO -> "Español latino"
    TrackLang.CASTELLANO -> "Castellano"
    TrackLang.SPANISH -> "Español (genérico)"
    TrackLang.DUAL -> "Dual / multi-audio"
    TrackLang.ENGLISH -> "Inglés"
    TrackLang.JAPANESE -> "Japonés"
    TrackLang.UNKNOWN -> "Desconocido"
}

/**
 * Ordered list of languages: the chosen ones on top and numbered (the player goes through them in
 * that order), the unchosen ones below in gray. Edited with a checkbox + arrows instead of
 * dragging, because the same pattern has to work with the TV remote.
 */
@Composable
fun LanguageOrderEditor(
    title: String,
    options: List<TrackLang>,
    order: List<TrackLang>,
    onChange: (List<TrackLang>) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
    Column {
        val unchosen = options.filterNot { it in order }
        order.forEachIndexed { i, lang ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = true,
                    onCheckedChange = { onChange(LangOrderEdits.toggle(order, lang)) },
                    colors = CheckboxDefaults.colors(checkedColor = ArkivRed),
                )
                Text("${i + 1}. ${lang.label()}", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(LangOrderEdits.moveUp(order, lang)) }, enabled = i > 0) {
                    Text("▲", color = if (i > 0) Color.White else ArkivTextSecondary)
                }
                TextButton(
                    onClick = { onChange(LangOrderEdits.moveDown(order, lang)) },
                    enabled = i < order.lastIndex,
                ) {
                    Text("▼", color = if (i < order.lastIndex) Color.White else ArkivTextSecondary)
                }
            }
        }
        unchosen.forEach { lang ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = false,
                    onCheckedChange = { onChange(LangOrderEdits.toggle(order, lang)) },
                    colors = CheckboxDefaults.colors(checkedColor = ArkivRed),
                )
                Text(lang.label(), color = ArkivTextSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * List of languages WITH NO order: here "I understand Japanese" is neither better nor worse than
 * "I understand English", so no arrows are offered — showing them would suggest a priority nobody
 * uses. Shares [LangOrderEdits.toggle] with the ordered editor to not repeat the "never empty the
 * list" rule.
 */
@Composable
fun LanguageChecklistEditor(
    title: String,
    subtitle: String,
    options: List<TrackLang>,
    selected: List<TrackLang>,
    onChange: (List<TrackLang>) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
    )
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    Column {
        options.forEach { lang ->
            val checked = lang in selected
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onChange(LangOrderEdits.toggle(selected, lang)) },
                    colors = CheckboxDefaults.colors(checkedColor = ArkivRed),
                )
                Text(
                    lang.label(),
                    color = if (checked) Color.White else ArkivTextSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
