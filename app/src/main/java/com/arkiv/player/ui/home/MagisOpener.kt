package com.arkiv.player.ui.home

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.ui.catalog.MagisSeasonDialog
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import kotlinx.coroutines.launch

/**
 * What tapping a Magis card does on the phone: a movie plays right away, a series opens its
 * chapters in the same dialog search uses. Nothing is saved to the library until something plays
 * -- same as a search result, whose code this reuses as-is ([SearchPlayback]).
 *
 * Returns the tap handler; the chapters dialog is drawn by this same composable while it's open.
 */
@Composable
fun rememberMagisOpener(onPlay: (episodeId: String) -> Unit): (CatalogItem) -> Unit {
    val graph = rememberGraph()
    val context = LocalContext.current
    val playback = remember(graph) { SearchPlayback(graph) }
    val scope = rememberCoroutineScope()
    val currentOnPlay by rememberUpdatedState(onPlay)
    var season by remember { mutableStateOf<GatewayResult?>(null) }

    fun handle(result: PlaybackResult) {
        when (result) {
            is PlaybackResult.Ready -> currentOnPlay(result.episodeId)
            is PlaybackResult.Failed -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    season?.let { open ->
        MagisSeasonDialog(
            season = open,
            client = graph.contentSource,
            onDismiss = { season = null },
            onPlay = { chapters, chapter, series ->
                season = null
                scope.launch { handle(playback.playMagisSeason(open, chapters, chapter, series)) }
            },
        )
    }

    return remember(playback) {
        { item: CatalogItem ->
            val result = item.toGatewayResult()
            if (item.isMagisSeries) {
                season = result
            } else {
                scope.launch { handle(playback.playMagis(result)) }
            }
            Unit
        }
    }
}
