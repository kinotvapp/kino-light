package com.arkiv.player.ui.player

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouter
import com.arkiv.player.AppGraph
import com.arkiv.player.cast.CastGaveUp
import com.arkiv.player.cast.CastRouteDedup
import com.arkiv.player.cast.DownloadForTvPolicy
import com.arkiv.player.cast.PlayerOnScreen
import com.arkiv.player.cast.PlayerReopen
import com.arkiv.player.cast.SendToTv
import com.arkiv.player.cast.TvTarget
import com.arkiv.player.data.local.LocalFileUse
import com.arkiv.player.dlna.DlnaDevice
import kotlinx.coroutines.delay

/**
 * Sends the title on screen to a TV when asked from outside the player ([SendToTv]): the Downloads
 * screen's "Enviar a la TV", the "Listo para la TV" notification, or a download prepared for a TV
 * that gave up ([com.arkiv.player.cast.DownloadForTv]).
 *
 * Waits until the player has opened the title FROM THE FILE (`LocalFileUse.playingEpisode`), so what
 * goes to the TV is the download, not the network: a screen that is being replaced by the reopened
 * one never takes the request. Then: a DLNA renderer that gave up gets the file directly (the same
 * path as "Probar por DLNA", [CastToDlnaHandoff]); a Chromecast session still up needs nothing (the
 * reopened player casts its item to it as it loads); otherwise the person picks the TV here, with
 * the same Chromecast chooser and DLNA list as the player's buttons.
 *
 * Mounted from [DlnaDevicesDialog] (always composed with the player), never from `PlayerContent`,
 * which is at ART's verifier limit.
 */
@Composable
internal fun SendToTvPrompt(dlna: DlnaState, graph: AppGraph) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    val request by SendToTv.requests.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        var waited = 0L
        while (waited < LOAD_WAIT_MS && LocalFileUse.playingEpisode != r.episodeId) {
            delay(POLL_MS)
            waited += POLL_MS
        }
        // Taken only after settling: a screen being replaced by the reopened one is gone by then.
        delay(SETTLE_MS)
        val screen = PlayerOnScreen.episodeId ?: return@LaunchedEffect
        val mine = SendToTv.take(r, screen) ?: return@LaunchedEffect
        val target = mine.target
        val device = target?.dlnaDevice
        when {
            device != null -> CastToDlnaHandoff.offer(device, null)
            target?.receiver == CastGaveUp.Receiver.CHROMECAST && graph.castSession?.casting?.value == true -> Unit
            else -> asking = true
        }
    }
    if (!asking) return
    val castAvailable = graph.castContext != null
    AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Enviar a la TV") },
        text = {
            Column {
                Text("Se envía el archivo descargado, sin depender de internet. ¿En qué TV lo quieres ver?")
                if (castAvailable) {
                    TextButton(onClick = {
                        asking = false
                        openCastChooser(context, graph)
                    }) { Text("Chromecast") }
                }
                TextButton(onClick = {
                    asking = false
                    dlna.discover()
                }) { Text("Otra TV (DLNA)") }
            }
        },
        confirmButton = { TextButton(onClick = { asking = false }) { Text("Ahora no") } },
    )
}

/**
 * The player's DLNA pick, preferring the download: when the title on screen is on the phone but this
 * player opened it from the network (before the download finished), it is reopened from the file and
 * that is what [device] gets ([DownloadForTvPolicy.reopenAsLocal]). True when it took over the pick.
 */
internal fun reopenLocalForDlna(graph: AppGraph, device: DlnaDevice): Boolean {
    val screen = PlayerOnScreen.episodeId ?: return false
    if (!DownloadForTvPolicy.reopenAsLocal(screen, graph.downloadForTv.downloaded, LocalFileUse.playingEpisode)) return false
    if (!LocalReopens.once(screen)) return false
    SendToTv.offer(screen, TvTarget(CastGaveUp.Receiver.DLNA, device, 0L))
    PlayerReopen.request(screen)
    return true
}

/** One reopen per title and process: a title whose file never loads must not loop. */
internal object LocalReopens {
    private val done = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun once(episodeId: String): Boolean = done.add(episodeId)
}

/** The Cast device chooser, as the player's Chromecast button opens it (one entry per TV). */
private fun openCastChooser(context: Context, graph: AppGraph) {
    runCatching {
        val selector = graph.castContext?.mergedSelector ?: return
        val themed = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat)
        val dialog = object : MediaRouteChooserDialog(themed) {
            override fun onFilterRoutes(routes: MutableList<MediaRouter.RouteInfo>) {
                super.onFilterRoutes(routes)
                CastRouteDedup.filterInPlace(routes)
            }
        }
        dialog.routeSelector = selector
        dialog.show()
    }
}

private const val LOAD_WAIT_MS = 10_000L
private const val POLL_MS = 250L
private const val SETTLE_MS = 1_500L
