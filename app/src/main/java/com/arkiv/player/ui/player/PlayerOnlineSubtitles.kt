package com.arkiv.player.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.subtitles.OnlineSearchOutcome
import com.arkiv.player.data.subtitles.OnlineSubtitle
import com.arkiv.player.data.subtitles.OnlineSubtitleRules
import com.arkiv.player.data.subtitles.SubtitleResult
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The person-facing text of the online subtitle section, in one place (and for tests). */
internal object OnlineSubtitlesCopy {
    const val TITLE = "Buscar subtítulos en línea"
    const val SEARCH = "Buscar"
    const val SEARCH_AGAIN = "Buscar de nuevo"
    const val SEARCHING = "Buscando…"
    const val DOWNLOADING = "Descargando…"
    const val ADDED = "Subtítulo agregado"
    const val NO_RESULTS = "Sin resultados"
    const val NO_KEY = "Para buscar subtítulos en línea agrega tu llave de OpenSubtitles o SubDL en Ajustes → Subtítulos."
    const val HASH_BADGE = "Coincide con tu archivo"
    const val UNIDENTIFIED = "No pudimos identificar este título para buscar sus subtítulos."

    /** One result's line: language, release and how many downloaded it. */
    fun resultLine(s: OnlineSubtitle): String = buildString {
        append(OnlineSubtitleRules.languageName(s.language))
        if (s.hearingImpaired) append(" (SDH)")
        if (s.release.isNotBlank()) append(" · ").append(s.release)
        if (s.downloads > 0) append(" · ").append(s.downloads).append(" descargas")
    }
}

/**
 * Online search fits only what an in-screen ExoPlayer plays from a Xuper/Magis or plugin title, and
 * never a live channel (it has no subtitles to find). A downloaded file plays on the service player,
 * which cannot take a new track.
 */
internal fun onlineSearchFits(state: TracksState): Boolean {
    if (!state.playsInScreen) return false
    val kind = PlayerSource.kindFor(state.title)
    return (kind == SourceKind.MAGIS || kind == SourceKind.PLUGIN) && !PluginIds.isLiveEpisode(state.title)
}

/**
 * "Buscar subtítulos en línea" inside the audio and subtitles menu (phone and TV: plain buttons, so
 * the D-pad walks them): a search button, the results grouped by provider (language, release,
 * downloads), and a tap downloads one, adds it as a track ([onAdded]) and turns it on
 * ([TracksState.selectWhenReported]). Hidden behind a hint when no provider has a key.
 */
@Composable
internal fun OnlineSubtitlesSection(state: TracksState, onAdded: (String, ResolvedSub) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val title = state.title
    var available by remember(title) { mutableStateOf<Boolean?>(null) }
    var outcome by remember(title) { mutableStateOf<OnlineSearchOutcome?>(null) }
    var busy by remember(title) { mutableStateOf<String?>(null) }
    var note by remember(title) { mutableStateOf<String?>(null) }
    LaunchedEffect(title) {
        available = withContext(Dispatchers.IO) { graph.onlineSubtitles.available() }
        // Lazily, once the section is open: the file's hash (cached per title), so a search finds it ready.
        if (available == true) runCatching { graph.onlineSubtitles.hashFor(title, graph.playingFile) }
    }

    SectionTitle(OnlineSubtitlesCopy.TITLE)
    when (available) {
        null -> return
        false -> {
            Hint(OnlineSubtitlesCopy.NO_KEY)
            return
        }
        true -> Unit
    }
    Column {
        busy?.let { Hint(it) }
        note?.let { Hint(it) }
        // Buttons stay enabled while busy (a click is ignored): on a TV a button that turns
        // disabled under the D-pad's focus throws the focus out of the dialog.
        run {
            TextButton(onClick = {
                if (busy != null) return@TextButton
                busy = OnlineSubtitlesCopy.SEARCHING
                note = null
                scope.launch {
                    outcome = runCatching { graph.onlineSubtitles.search(title, graph.playingFile) }.getOrNull() ?: OnlineSearchOutcome.Unidentified
                    busy = null
                }
            }) { Text(if (outcome == null) OnlineSubtitlesCopy.SEARCH else OnlineSubtitlesCopy.SEARCH_AGAIN, color = Color.White) }
        }
        when (val o = outcome) {
            null -> Unit
            OnlineSearchOutcome.NoKey -> Hint(OnlineSubtitlesCopy.NO_KEY)
            OnlineSearchOutcome.Unidentified -> Hint(OnlineSubtitlesCopy.UNIDENTIFIED)
            is OnlineSearchOutcome.Found -> o.groups.forEach { group ->
                Text(
                    group.provider.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(top = 6.dp, start = 8.dp),
                )
                when {
                    group.failure != null -> Hint(group.failure.message)
                    group.results.isEmpty() -> Hint(OnlineSubtitlesCopy.NO_RESULTS)
                }
                group.results.forEach { sub ->
                    TextButton(onClick = {
                        if (busy != null) return@TextButton
                        busy = OnlineSubtitlesCopy.DOWNLOADING
                        note = null
                        scope.launch {
                            when (val r = runCatching { graph.onlineSubtitles.download(title, sub) }.getOrNull()) {
                                is SubtitleResult.Ok -> {
                                    val track = onlineSub(r.value)
                                    onAdded(title, track)
                                    state.selectWhenReported(track.label)
                                    note = OnlineSubtitlesCopy.ADDED
                                }
                                is SubtitleResult.Failed -> note = r.failure.message
                                null -> note = com.arkiv.player.data.subtitles.SubtitleFailure.UNAVAILABLE.message
                            }
                            busy = null
                        }
                    }) {
                        Column {
                            if (sub.hashMatch) {
                                Text(
                                    OnlineSubtitlesCopy.HASH_BADGE,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF7BD88F),
                                )
                            }
                            Text(
                                OnlineSubtitlesCopy.resultLine(sub),
                                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = ArkivTextSecondary, modifier = Modifier.padding(8.dp))
}
