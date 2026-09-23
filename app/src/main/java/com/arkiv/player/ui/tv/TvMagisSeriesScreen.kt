package com.arkiv.player.ui.tv

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.ui.home.toGatewayResult
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import kotlinx.coroutines.launch

/** Route pattern for [TvMagisSeriesScreen]; build it with [magisSeriesRoute]. */
const val MAGIS_SERIES_ROUTE =
    "magis_series/{id}?type={type}&title={title}&poster={poster}&backdrop={backdrop}&count={count}"

/** Everything the picker needs travels in the route, so it survives process death. */
fun magisSeriesRoute(item: CatalogItem): String =
    "magis_series/${Uri.encode(item.id)}?type=${Uri.encode(item.type)}" +
        "&title=${Uri.encode(item.title)}&poster=${Uri.encode(item.poster.orEmpty())}" +
        "&backdrop=${Uri.encode(item.backdrop.orEmpty())}&count=${item.episodeCount}"

/** Rebuilds the [CatalogItem] a [MAGIS_SERIES_ROUTE] entry carries; ref built like `tree()` does. */
fun magisSeriesItem(id: String, type: String, title: String, poster: String, backdrop: String, count: Int) =
    CatalogItem(
        id = id, title = title, poster = poster.ifBlank { null }, durationS = 0,
        ref = MagisRef(id, type, 0).encode(), type = type,
        backdrop = backdrop.ifBlank { null }, episodeCount = count,
    )

/**
 * A home card's series, on the TV: the same chapter picker search opens ([TvMagisSeasonContent]),
 * as a screen of its own. Picking a chapter saves the season and plays it (same
 * `SearchPlayback.playMagisSeason` search uses); nothing is saved before that. No "Guardar toda la
 * temporada" here (`onSaveAll = null`): the home is for watching.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvMagisSeriesScreen(item: CatalogItem, onPlay: (episodeId: String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val graph = rememberGraph()
    val playback = remember(graph) { SearchPlayback(graph) }
    val scope = rememberCoroutineScope()
    val season = remember(item) { item.toGatewayResult() }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        TvMagisSeasonContent(
            season = season,
            client = graph.contentSource,
            posterUrl = item.poster.orEmpty(),
            preparing = preparing,
            onPlayOne = { chapters, chapter, series ->
                preparing = true; error = null
                scope.launch {
                    when (val r = playback.playMagisSeason(season, chapters, chapter, series)) {
                        is PlaybackResult.Ready -> onPlay(r.episodeId)
                        is PlaybackResult.Failed -> { preparing = false; error = r.message }
                    }
                }
            },
            onSaveAll = null,
        )
        error?.let {
            Text(
                it,
                color = ArkivRed,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 48.dp, vertical = 28.dp),
            )
        }
    }
}
