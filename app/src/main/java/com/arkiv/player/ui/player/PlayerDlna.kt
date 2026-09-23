package com.arkiv.player.ui.player

import android.view.ContextThemeWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.arkiv.player.dlna.DlnaController
import com.arkiv.player.dlna.DlnaDevice
import com.arkiv.player.playback.LiveHlsProxy
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All of the player's DLNA: state, actions, and the three interface pieces that show it.
 *
 * Lives apart from [PlayerContent] because it's the only block on that screen that doesn't cross
 * paths with anything else: nothing about transport, cast, or live mode reads these variables.
 * The only thing the player needs to know is whether a renderer is active ([DlnaState.active]),
 * because that hides the local controls.
 */
@Stable
internal class DlnaState(
    private val dlna: DlnaController,
    private val scope: CoroutineScope,
    /** Whole-process scope: the only thing that survives the screen leaving composition. */
    private val appScope: CoroutineScope,
) {
    /** The device dialog is open. */
    var pickerOpen by mutableStateOf(false)
        private set

    /** Running the SSDP discovery (the dialog's spinner). */
    var searching by mutableStateOf(false)
        private set

    /** Renderers found in the last search. */
    var devices by mutableStateOf<List<DlnaDevice>>(emptyList())
        private set

    /** The renderer we're sending video to, or null if playing locally. */
    var active by mutableStateOf<DlnaDevice?>(null)
        private set

    /** The active renderer is paused (we know because we asked it to). */
    var paused by mutableStateOf(false)
        private set

    /**
     * Starts the search and opens the picker. One single path for VOD and live: both control
     * blocks (the Row above and live mode's own Row) trigger it the same way, the only
     * difference between them is the rest of the Row surrounding it (title/markers in VOD, "EN
     * VIVO" badge in live).
     */
    fun discover() {
        pickerOpen = true
        searching = true
        devices = emptyList()
        scope.launch {
            val found = withContext(Dispatchers.IO) { dlna.discover() }
            devices = found
            searching = false
        }
    }

    fun closePicker() {
        pickerOpen = false
    }

    /** The renderer accepted the video: from here on the controls drive the TV, not the local one. */
    fun markActive(device: DlnaDevice) {
        active = device
        paused = false
    }

    fun togglePause() {
        val dev = active ?: return
        scope.launch {
            withContext(Dispatchers.IO) { if (paused) dlna.play(dev) else dlna.pause(dev) }
            paused = !paused
        }
    }

    fun stop() {
        val dev = active ?: return
        scope.launch {
            withContext(Dispatchers.IO) { dlna.stop(dev) }
            active = null
        }
    }

    /**
     * Best-effort cutoff on leaving the screen: doesn't touch the state because it's already dying.
     *
     * Goes through [appScope] and NOT `scope`, and that isn't cosmetic: the caller is
     * PlayerScreen's `onDispose`, and Compose cancels `rememberCoroutineScope`'s scope in that
     * same apply-changes pass, right as `onDispose` finishes. The `launch` manages to get queued
     * (the scope is still active), but it's dispatched through the composition's dispatcher: it
     * doesn't run inline, and by the time it would start the scope is already cancelled. The
     * coroutine dies without running its first instruction and the Stop never goes out, so the TV
     * keeps playing.
     *
     * Verified on 2026-08-19 against a test MediaRenderer: with `scope` the renderer receives
     * NOTHING on exiting with Back (and a log inside the `launch` never gets printed, even though
     * the scope still reported `isActive == true` when queuing it); with [appScope] it receives
     * the Stop.
     */
    fun stopOnExit() {
        val dev = active ?: return
        appScope.launch { withContext(Dispatchers.IO) { runCatching { dlna.stop(dev) } } }
    }
}

@Composable
internal fun rememberDlnaState(dlna: DlnaController, appScope: CoroutineScope): DlnaState {
    val scope = rememberCoroutineScope()
    return remember(dlna, scope, appScope) { DlnaState(dlna, scope, appScope) }
}

/**
 * Hands [device] the URL that matches what we're playing and returns whether it accepted it.
 *
 * Live can NOT send `mediaUrl`: that's loopback (our own server listening on 127.0.0.1), which
 * resolves to nothing from the TV. It has to go out through the LAN IP of the server already
 * running here — [LiveHlsProxy]'s. And `castUrl` doesn't work for live either: there's never a
 * backup mp4 for a channel (see CastRequestBuilder's KDoc).
 */
internal suspend fun sendToRenderer(
    dlna: DlnaController,
    device: DlnaDevice,
    ep: PlayerData?,
    lanIp: () -> String?,
    liveHlsProxy: LiveHlsProxy,
): Boolean = when (ep?.kind) {
    null -> false

    SourceKind.LIVE -> {
        val lan = lanIp()?.let { liveHlsProxy.lanUrl(it) }
        if (lan != null) {
            withContext(Dispatchers.IO) {
                dlna.playRawUrl(device, lan, ep.title, "application/vnd.apple.mpegurl")
            }
        } else {
            false
        }
    }

    else -> withContext(Dispatchers.IO) { dlna.setUrlAndPlay(device, ep.castUrl ?: ep.mediaUrl, ep.title) }
}

/** "Playing on <TV>" bar with pause/stop, visible while a renderer is active. */
@Composable
internal fun BoxScope.ActiveDlnaBar(state: DlnaState) {
    val active = state.active ?: return
    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(16.dp),
        color = ArkivSurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Tv, contentDescription = null, tint = ArkivRed)
            Text(
                "Reproduciendo en ${active.friendlyName}",
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { state.togglePause() }) {
                Text(if (state.paused) "Reanudar" else "Pausar")
            }
            TextButton(onClick = { state.stop() }) { Text("Detener") }
        }
    }
}

/**
 * Dialog of found devices. Doesn't know what's playing: choosing a renderer only notifies
 * [onChoose], which is the one that builds the URL and confirms with [DlnaState.markActive].
 */
@Composable
internal fun DlnaDevicesDialog(
    state: DlnaState,
    onChoose: (DlnaDevice) -> Unit,
) {
    if (!state.pickerOpen) return
    AlertDialog(
        onDismissRequest = { state.closePicker() },
        title = { Text("Reproducir en TV (DLNA)") },
        text = {
            Column {
                when {
                    state.searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(end = 12.dp).size(20.dp),
                        )
                        Text("Buscando dispositivos…")
                    }

                    state.devices.isEmpty() -> Text(
                        "No se encontraron dispositivos DLNA. Asegúrate de que la TV esté encendida, " +
                            "en la misma red WiFi y con DLNA habilitado.",
                        color = ArkivTextSecondary,
                    )

                    else -> state.devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    state.closePicker()
                                    onChoose(device)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Tv, contentDescription = null, tint = ArkivRed)
                            Text(device.friendlyName, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { state.closePicker() }) { Text("Cerrar") } },
    )
}

/**
 * DLNA button + Chromecast button (MediaRouteButton), shared by VOD's controls Row and live
 * mode's own Row (Task 14/18): both offer exactly the same two buttons with the same visibility
 * criterion -- the only thing that changes between them is the rest of the Row surrounding them
 * (title/markers in VOD, the exit button in live), so THAT Row stays duplicated on purpose but
 * these two buttons don't.
 */
@Composable
internal fun DlnaCastButtons(
    casting: Boolean,
    castContext: CastContext?,
    onDiscoverDlna: () -> Unit,
) {
    // Not while casting: DLNA is ANOTHER renderer, and mixing the two leaves two TVs playing the
    // same thing at once. (The Chromecast button does stay visible: it's the only way to cut the
    // session.)
    if (!casting) {
        IconButton(onClick = onDiscoverDlna) {
            Icon(Icons.Default.Tv, contentDescription = "Reproducir en TV (DLNA)", tint = Color.White)
        }
    }
    if (castContext != null) {
        AndroidView(
            modifier = Modifier.padding(horizontal = 8.dp),
            factory = { ctx ->
                val themed = ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_DayNight)
                MediaRouteButton(themed).also {
                    CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, it)
                }
            },
        )
    }
}
