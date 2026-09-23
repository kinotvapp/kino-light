package com.arkiv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Audio and subtitle language, and how subtitles look. It's the longest tab of all: on its own it
 * took up half the scroll when Ajustes was a single column.
 */
@Composable
internal fun SubtitlesTab() {
    val graph = rememberGraph()
    val style by graph.subtitlePrefs.prefs.collectAsStateWithLifecycle()

    // Change style: persists locally.
    fun onChange(s: PlaybackPrefs) {
        graph.subtitlePrefs.update(s)
    }

    // Preview.
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222)).padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Ejemplo de subtítulo",
            color = Color(style.textColor),
            fontSize = (16 * style.sizePercent / 100).sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.background(Color(style.backgroundColor)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }

    LanguageOrderEditor(
        title = "Idioma del audio (en orden de preferencia)",
        options = AUDIO_LANGUAGES,
        order = style.audioLangs,
        onChange = { onChange(style.copy(audioLangs = it)) },
    )

    LanguageChecklistEditor(
        title = "Idiomas que entiendo",
        subtitle = "Los subtítulos se prenden solos únicamente cuando el audio queda en un idioma " +
            "que no está en esta lista.",
        options = AUDIO_LANGUAGES,
        selected = style.understoodLangs,
        onChange = { onChange(style.copy(understoodLangs = it)) },
    )

    LanguageOrderEditor(
        title = "Idioma de los subtítulos (en orden de preferencia)",
        options = SUBTITLE_LANGUAGES,
        order = style.subtitleLangs,
        onChange = { onChange(style.copy(subtitleLangs = it)) },
    )

    Label("Cuándo mostrarlos")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Automático", style.subtitleMode == SubtitleMode.AUTO) {
            onChange(style.copy(subtitleMode = SubtitleMode.AUTO))
        }
        Chip("Desactivado", style.subtitleMode == SubtitleMode.OFF) {
            onChange(style.copy(subtitleMode = SubtitleMode.OFF))
        }
    }
    Text(
        "Automático: se prenden solo si el audio quedó en un idioma que no marcaste como entendido.",
        style = MaterialTheme.typography.bodySmall,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )

    // Size.
    Label("Tamaño: ${style.sizePercent}%")
    Slider(
        value = style.sizePercent.toFloat(),
        onValueChange = { onChange(style.copy(sizePercent = it.toInt())) },
        valueRange = 60f..200f,
        colors = SliderDefaults.colors(thumbColor = ArkivRed, activeTrackColor = ArkivRed),
    )

    // Text color.
    Label("Color del texto")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Swatch(0xFFFFFFFF, style.textColor) { onChange(style.copy(textColor = it)) }
        Swatch(0xFFFFEB3B, style.textColor) { onChange(style.copy(textColor = it)) } // yellow
        Swatch(0xFF00E5FF, style.textColor) { onChange(style.copy(textColor = it)) } // cyan
        Swatch(0xFF00E676, style.textColor) { onChange(style.copy(textColor = it)) } // green
    }

    // Box background.
    Label("Fondo")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Caja negra", style.backgroundColor == 0xCC000000L) { onChange(style.copy(backgroundColor = 0xCC000000L)) }
        Chip("Semi", style.backgroundColor == 0x80000000L) { onChange(style.copy(backgroundColor = 0x80000000L)) }
        Chip("Sin fondo", style.backgroundColor == 0x00000000L) { onChange(style.copy(backgroundColor = 0x00000000L)) }
    }

    // Border.
    Label("Borde del texto")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Contorno", style.edge == PlaybackPrefs.EDGE_OUTLINE) { onChange(style.copy(edge = PlaybackPrefs.EDGE_OUTLINE)) }
        Chip("Sombra", style.edge == PlaybackPrefs.EDGE_SHADOW) { onChange(style.copy(edge = PlaybackPrefs.EDGE_SHADOW)) }
        Chip("Ninguno", style.edge == PlaybackPrefs.EDGE_NONE) { onChange(style.copy(edge = PlaybackPrefs.EDGE_NONE)) }
    }
}
