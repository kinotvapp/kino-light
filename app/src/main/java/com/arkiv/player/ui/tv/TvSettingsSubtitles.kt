package com.arkiv.player.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.settings.AUDIO_LANGUAGES
import com.arkiv.player.ui.settings.SUBTITLE_LANGUAGES

/**
 * Audio and subtitle language on TV. The styling (size, colors, border) isn't here on purpose
 * -picking colors with the remote is awkward-, so it stays with whatever the TV already has
 * saved locally (no cloud sync, it no longer travels from the phone).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSettingsSubtitles() {
    val graph = rememberGraph()
    val prefs by graph.subtitlePrefs.prefs.collectAsStateWithLifecycle()

    // Persists locally (Task 5: with no cloud sync it no longer travels to the phone).
    fun setPrefs(p: PlaybackPrefs) {
        graph.subtitlePrefs.update(p)
    }

    Text("Audio y subtítulos", style = MaterialTheme.typography.titleMedium, color = Color.White)
    TvLanguageOrderEditor(
        title = "Idioma del audio (en orden de preferencia)",
        options = AUDIO_LANGUAGES,
        order = prefs.audioLangs,
        onChange = { setPrefs(prefs.copy(audioLangs = it)) },
    )
    TvLanguageChecklist(
        title = "Idiomas que entiendo",
        subtitle = "Los subtítulos se prenden solos únicamente cuando el audio queda en un " +
            "idioma que no está en esta lista.",
        options = AUDIO_LANGUAGES,
        selected = prefs.understoodLangs,
        onChange = { setPrefs(prefs.copy(understoodLangs = it)) },
    )
    TvLanguageOrderEditor(
        title = "Idioma de los subtítulos (en orden de preferencia)",
        options = SUBTITLE_LANGUAGES,
        order = prefs.subtitleLangs,
        onChange = { setPrefs(prefs.copy(subtitleLangs = it)) },
    )
    TvActionOption(
        if (prefs.subtitleMode == SubtitleMode.AUTO) {
            "Subtítulos: automáticos (toca para desactivar)"
        } else {
            "Subtítulos: desactivados (toca para automáticos)"
        },
    ) {
        val newMode = if (prefs.subtitleMode == SubtitleMode.AUTO) SubtitleMode.OFF else SubtitleMode.AUTO
        setPrefs(prefs.copy(subtitleMode = newMode))
    }
}
