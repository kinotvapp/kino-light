package com.arkiv.player.ui.plugin

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.toPlaySource
import com.arkiv.player.ui.catalog.MagisSeasonDialog
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.accent
import com.arkiv.player.ui.catalog.isSeries
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback

/**
 * What tapping a plugin card on the phone's Home does — the same as in search: a movie is saved
 * and played, a series opens its chapter list. Hosts that list itself, like `rememberMagisActions`.
 */
@Composable
fun rememberPluginOpener(onPlay: (episodeId: String) -> Unit): (GatewayResult) -> Unit {
    val graph = rememberGraph()
    val context = LocalContext.current
    val playback = remember(graph) { SearchPlayback(graph) }
    val scope = rememberCoroutineScope()
    // A double tap on a movie card must not resolve, save and navigate twice.
    val single = remember(scope) { SinglePlayback(scope) }
    val currentOnPlay by rememberUpdatedState(onPlay)
    var season by remember { mutableStateOf<PlaySource.Plugin?>(null) }

    fun handle(result: PlaybackResult) {
        when (result) {
            is PlaybackResult.Ready -> currentOnPlay(result.episodeId)
            is PlaybackResult.Failed -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    season?.let { open ->
        MagisSeasonDialog(
            season = open.result,
            client = graph.contentSource,
            onDismiss = { season = null },
            onPlay = { chapters, chapter, series ->
                season = null
                single.start { handle(playback.playPluginSeason(open.result, chapters, chapter, series)) }
            },
            onSave = null,
            sourceLabel = open.pluginName,
            accent = open.accent,
        )
    }

    return remember(playback, single) {
        { result ->
            val source = result.toPlaySource() as? PlaySource.Plugin
            when {
                source == null -> Unit
                source.isSeries() -> season = source
                else -> single.start { handle(playback.playPlugin(result)) }
            }
        }
    }
}
