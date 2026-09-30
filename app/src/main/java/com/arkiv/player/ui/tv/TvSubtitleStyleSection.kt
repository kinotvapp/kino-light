package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.ui.player.applyArkivSubtitleStyle
import com.arkiv.player.ui.settings.SubtitleStyleOptions as Opts

/**
 * "Estilo de subtítulos" on the TV: the system's own look by default, or the person's own
 * (size, text colour, background, border) with one OK-cycling row each -- a remote has no slider.
 * A live preview shows the result with the same [SubtitleView] styling the player applies.
 * Stateless: [onChange] gets the edited prefs.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSubtitleStyleSection(prefs: PlaybackPrefs, onChange: (PlaybackPrefs) -> Unit) {
    Text("Estilo de subtítulos", style = MaterialTheme.typography.titleMedium, color = Color.White)
    TvActionOption(
        if (prefs.tvCustomStyle) {
            "Estilo: personalizado (toca para usar el del sistema)"
        } else {
            "Estilo: del sistema (toca para personalizar)"
        },
    ) { onChange(prefs.copy(tvCustomStyle = !prefs.tvCustomStyle)) }

    if (prefs.tvCustomStyle) {
        TvActionOption("Tamaño: ${prefs.sizePercent} %") {
            onChange(prefs.copy(sizePercent = Opts.nextSize(prefs.sizePercent)))
        }
        TvActionOption("Color del texto: ${Opts.label(Opts.COLORS, prefs.textColor)}") {
            onChange(prefs.copy(textColor = Opts.next(Opts.COLORS, prefs.textColor)))
        }
        TvActionOption("Fondo: ${Opts.label(Opts.BACKGROUNDS, prefs.backgroundColor)}") {
            onChange(prefs.copy(backgroundColor = Opts.next(Opts.BACKGROUNDS, prefs.backgroundColor)))
        }
        TvActionOption("Borde: ${Opts.label(Opts.EDGES, prefs.edge)}") {
            onChange(prefs.copy(edge = Opts.next(Opts.EDGES, prefs.edge)))
        }
    }

    Box(
        Modifier
            .fillMaxWidth(0.6f)
            .height(120.dp)
            .background(Color(0xFF3A4A5A))
            .padding(8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                SubtitleView(ctx).apply {
                    setCues(listOf(Cue.Builder().setText("Ejemplo de subtítulo").build()))
                }
            },
            update = { it.applyArkivSubtitleStyle(prefs, isTv = true) },
        )
    }
}
