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
import com.arkiv.player.data.local.DownloadSource
import com.arkiv.player.data.local.EnqueueOutcome
import com.arkiv.player.ui.catalog.MagisSeasonDialog
import com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice
import com.arkiv.player.ui.offline.rememberPostNotificationsRequest
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.search.queuedDownloadToastText
import kotlinx.coroutines.launch

/**
 * What a Magis card on the phone home can do: [open] it (a movie plays, a series opens its chapter
 * list) or [download] it (a movie is enqueued, a series opens the same list to pick chapters). Both
 * reuse search's code as-is ([SearchPlayback]); nothing is saved to the library until something
 * plays or a download is enqueued -- exactly like a search result.
 *
 * The two handlers share ONE chapter dialog: a series routes through it either way (play OR save),
 * so long-pressing "Descargar" on a series lands on the same picker a tap would, only with the
 * download checkboxes in reach. [canDownload] mirrors search's gate: it's false when no Magis
 * download strategy is registered, and the caller hides "Descargar" then.
 */
class MagisCardActions(
    val open: (CatalogItem) -> Unit,
    val download: (CatalogItem) -> Unit,
    val canDownload: Boolean,
)

@Composable
fun rememberMagisActions(onPlay: (episodeId: String) -> Unit): MagisCardActions {
    val graph = rememberGraph()
    val context = LocalContext.current
    val playback = remember(graph) { SearchPlayback(graph) }
    val scope = rememberCoroutineScope()
    val currentOnPlay by rememberUpdatedState(onPlay)
    // Same helpers the search screen's download path uses, so the home behaves identically: the
    // API 33+ notification prompt and the "you already have that" notice on a duplicate.
    val askNotifications = rememberPostNotificationsRequest()
    val notifyDuplicates = rememberDuplicateDownloadNotice()
    // Whether Magis has a download strategy registered (today it always does). Same gate as search.
    val canDownload = remember { DownloadSource.hasStrategy("magis", graph.downloadStrategies.keys) }
    var season by remember { mutableStateOf<GatewayResult?>(null) }

    fun handle(result: PlaybackResult) {
        when (result) {
            is PlaybackResult.Ready -> currentOnPlay(result.episodeId)
            is PlaybackResult.Failed -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    // Enqueues a movie for a device download, same path as SearchScreen.downloadMagisMovie: resolve
    // the episode id, enqueue with the source the id maps to, and toast only on a fresh QUEUE (the
    // duplicate notice already covers the already-have cases -- showing both would mislead).
    fun downloadMovie(result: GatewayResult) {
        askNotifications()
        scope.launch {
            val epId = playback.magisEpisodeId(result)
            if (epId == null) {
                Toast.makeText(context, "No se pudo preparar la descarga.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val outcome = graph.localDownloads.enqueue(epId, DownloadSource.sourceFor(epId))
            notifyDuplicates(listOf(outcome))
            queuedDownloadToastText(outcome, result.title)?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            }
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
            // Download a season chapter by chapter, same as SearchScreen's season dialog: each is a
            // separate file and the queue groups them by series in Descargas. Disabled (null) when
            // no strategy is registered, exactly the gate the movie path uses.
            onSave = if (!canDownload) null else { _, chosen, series ->
                askNotifications()
                scope.launch {
                    var queued = 0
                    for (chapter in chosen) {
                        val epId = playback.magisEpisodeIdFor(open, chapter, series) ?: continue
                        if (graph.localDownloads.enqueue(epId, "magis") == EnqueueOutcome.QUEUED) queued++
                    }
                    val message = when {
                        queued == 0 -> "Esos capítulos ya estaban guardados."
                        queued == chosen.size -> "Descargando ${chosen.size} capítulo(s)…"
                        else -> "Se encolaron $queued de ${chosen.size} (el resto ya estaba)."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    return remember(playback, canDownload) {
        MagisCardActions(
            open = { item ->
                val result = item.toGatewayResult()
                if (item.isMagisSeries) season = result
                else scope.launch { handle(playback.playMagis(result)) }
            },
            download = { item ->
                val result = item.toGatewayResult()
                // A series can't be enqueued whole -- it opens the same picker so the person chooses
                // which chapters to save. A movie enqueues right away.
                if (item.isMagisSeries) season = result
                else downloadMovie(result)
            },
            canDownload = canDownload,
        )
    }
}

/**
 * What tapping a Magis card does on the phone: a movie plays right away, a series opens its
 * chapters. The long-press download lives in [rememberMagisActions]; this stays the tap-only entry
 * point for callers that don't offer downloads (e.g. the "Ver todo" browse grid).
 */
@Composable
fun rememberMagisOpener(onPlay: (episodeId: String) -> Unit): (CatalogItem) -> Unit =
    rememberMagisActions(onPlay).open
