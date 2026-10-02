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
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.arkiv.player.cast.CastStart
import com.arkiv.player.cast.CastAudioSwitch
import com.arkiv.player.dlna.DlnaAudioSwitch
import com.arkiv.player.dlna.DlnaController
import com.arkiv.player.dlna.DlnaDevice
import com.arkiv.player.dlna.DlnaLog
import com.arkiv.player.dlna.DlnaXml
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
    /** A new audio is being remuxed and re-sent ([audioChanged]): the bar says so instead of the TV's name. */
    var preparingAudio by mutableStateOf(false)
        private set

    /** What was sent to [active] and with which audio, for [audioChanged] to send it again. */
    private var sentEp: PlayerData? = null
    private var audioOnTv: Int? = null
    private var spuOnPhone: Int? = null

    fun markActive(device: DlnaDevice, ep: PlayerData? = null, audio: com.arkiv.player.cast.CastAudioChoice? = null) {
        active = device
        paused = false
        preparingAudio = false
        sentEp = ep
        audioOnTv = audio?.ordinal
        spuOnPhone = null
    }

    /**
     * Another audio picked in the phone's menu while the TV plays ([DlnaAudioSwitch]): on a remux
     * route the title is remuxed again with [choice] while the TV plays on with the old audio, and
     * sent again (the same `sendToRendererNow` as the first cast) from where the TV is once the new
     * remux covers it -- the Chromecast's staged hand-over, see [DlnaController.setUrlAndPlay]'s
     * `followTv`. "Detener" meanwhile stops the new remux too. On any other route only the phone
     * changes, and [notify] says so. The first audio seen after an audio-less send is only recorded:
     * tracks arriving after the cast began are not a choice the person made.
     */
    fun audioChanged(
        choice: com.arkiv.player.cast.CastAudioChoice?,
        phoneMs: Long?,
        lanIp: () -> String?,
        liveHlsProxy: LiveHlsProxy,
        notify: (String) -> Unit,
    ) {
        val dev = active ?: return
        val previous = audioOnTv
        audioOnTv = choice?.ordinal
        val ep = sentEp
        if (previous == null || ep == null) return
        val kind = dlna.activeKind()
        val action = DlnaAudioSwitch.onChoiceChanged(kind, previous, choice?.ordinal)
        DlnaLog.diag("audio while casting: #$previous → #${choice?.ordinal} on $kind · $action")
        when (action) {
            CastAudioSwitch.NONE -> Unit
            CastAudioSwitch.PHONE_ONLY ->
                notify("El audio cambió solo en el teléfono: la TV reproduce este video tal cual y suena el audio que trae por defecto")
            CastAudioSwitch.REMUX_AND_RELOAD -> {
                preparingAudio = true
                notify("Preparando el nuevo audio… la TV sigue con el anterior hasta que esté listo")
                scope.launch {
                    // Where the TV is, asked again at every step of the wait: it keeps playing meanwhile.
                    val follow = { DlnaAudioSwitch.startMs(dlna.tvPositionMs(), phoneMs, ep.startPositionMs) }
                    val (before, at) = withContext(Dispatchers.IO) { dlna.activeCastId() to follow() }
                    val ok = sendToRendererNow(dlna, dev, ep, lanIp, liveHlsProxy, choice, at, followTv = follow)
                    // Stopped (or another audio picked) meanwhile: whoever did that owns the bar now.
                    if (active !== dev || audioOnTv != choice?.ordinal) return@launch
                    preparingAudio = false
                    // A re-send that failed after the hand-over leaves the TV paused: "Reanudar" plays it on.
                    // One that failed before it left the TV playing the old audio, untouched.
                    paused = !ok && dlna.activeCastId() != before
                    if (!ok) notify(dlna.lastError ?: "No se pudo cambiar el audio en la TV")
                }
            }
        }
    }

    /** The menu's line about the TV while it is open during a DLNA cast ([castTracksNote]); null otherwise. */
    fun tracksNote(menuOpen: Boolean, externalSubtitles: List<ResolvedSub>?): String? =
        if (menuOpen && active != null) castTracksNote(true, DlnaAudioSwitch.routeOf(dlna.activeKind()), externalSubtitles) else null

    /**
     * Another subtitle picked in the phone's menu while the TV plays. The choice is the phone's
     * (TracksState already applied it there); the TV gets no subtitles yet. THE hook for sending
     * them: re-send the cast with [spuId] at the TV's position (`dlna.tvPositionMs()`), as
     * [audioChanged] does for the audio. The first value seen is only recorded.
     */
    fun subtitleChanged(spuId: Int) {
        val previous = spuOnPhone
        spuOnPhone = spuId
        if (active == null || previous == null || previous == spuId) return
        DlnaLog.i("subtitle while casting: #$previous → #$spuId (phone only: the TV gets no subtitles yet)")
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
            preparingAudio = false
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
    /**
     * The audio the phone has on. Only a remuxed MPEG-TS can honour it (the remux carries exactly
     * one audio track); a renderer handed the file as-is plays its own default.
     */
    audio: com.arkiv.player.cast.CastAudioChoice? = null,
    /** The phone player's position when the cast was asked for; see [dlnaStartMs]. */
    livePositionMs: Long? = null,
): Boolean = sendToRendererNow(dlna, device, ep, lanIp, liveHlsProxy, audio, dlnaStartMs(ep, livePositionMs)).also { accepted ->
    // The TV plays it now: the chapter's attempt started, even if the paused local player never does.
    if (accepted && ep != null) CastStarts.accepted(ep.episodeId)
}

private suspend fun sendToRendererNow(
    dlna: DlnaController,
    device: DlnaDevice,
    ep: PlayerData?,
    lanIp: () -> String?,
    liveHlsProxy: LiveHlsProxy,
    audio: com.arkiv.player.cast.CastAudioChoice?,
    startMs: Long,
    /** Another audio for what the TV plays: see [DlnaController.setUrlAndPlay]. */
    followTv: (() -> Long)? = null,
): Boolean = when (ep?.kind) {
    null -> {
        DlnaLog.w("sendToRenderer: nothing is playing (no item)")
        false
    }

    SourceKind.LIVE -> {
        val ip = lanIp()
        val lan = ip?.let { liveHlsProxy.lanUrl(it) }
        DlnaLog.i("sendToRenderer: kind=LIVE title='${ep.title.take(40)}' lanIp=${ip ?: "NONE"} liveProxyUrl=${DlnaXml.safeUrl(lan)}")
        if (lan != null) {
            withContext(Dispatchers.IO) {
                dlna.playRawUrl(device, lan, ep.title, "application/vnd.apple.mpegurl")
            }
        } else {
            // The proxy has no LAN URL to hand out (no WiFi/LAN address, or the live proxy isn't listening).
            withContext(Dispatchers.IO) {
                dlna.failedBeforeSending(
                    device, kind = "live-hls", stage = "no_lan_url",
                    userMessage = "No se pudo obtener la dirección de red del teléfono para enviar el canal",
                    detail = "lanIp=${ip ?: "none"} proxyUrl=none",
                )
            }
            false
        }
    }

    // A plugin title's HLS (PluginCastProxy's token playlist, or a direct url) and a direct file (an
    // mp4/webm with no headers on an allowed host, see pluginCastModeFor): the renderer fetches it
    // itself, like a live channel. A proxied plugin FILE falls to the branch below and goes through
    // DlnaProxyServer from PluginCastProxy's loopback url, like Magis does from its proxy -- never
    // a plugin URL fetched by DlnaProxyServer's own, ungated client.
    SourceKind.PLUGIN ->
        if (ep.mime == MIME_HLS || !ep.mediaUrl.startsWith("http://127.0.0.1:")) sendPluginHls(dlna, device, ep, lanIp, startMs)
        else sendThroughProxy(dlna, device, ep, audio, startMs, followTv)

    else -> sendThroughProxy(dlna, device, ep, audio, startMs, followTv)
}

/**
 * Where the TV should start a DLNA cast of [ep]: the Chromecast's own rule
 * ([CastStart.resumePointMs]), from the phone player's [livePositionMs] or else where the item was
 * told to open. A live channel has no position to start at: 0, and the TV is never sought.
 */
internal fun dlnaStartMs(ep: PlayerData?, livePositionMs: Long?): Long = when {
    ep == null || ep.kind == SourceKind.LIVE || com.arkiv.player.data.plugin.PluginIds.isLiveEpisode(ep.episodeId) -> 0L
    else -> CastStart.resumePointMs(receiverMs = null, livePositionMs = livePositionMs, itemStartMs = ep.startPositionMs)
}

/** [ep]'s HLS, or its direct file, to [device] as a raw url the renderer pulls itself (see [pluginCastUri]). */
private suspend fun sendPluginHls(dlna: DlnaController, device: DlnaDevice, ep: PlayerData, lanIp: () -> String?, startMs: Long): Boolean {
    val ip = lanIp()
    val url = pluginCastUri(ep, ip)
    DlnaLog.i("sendToRenderer: kind=PLUGIN hls title='${ep.title.take(40)}' lanIp=${ip ?: "NONE"} url=${DlnaXml.safeUrl(url)}")
    if (url != null) return withContext(Dispatchers.IO) { dlna.playRawUrl(device, url, ep.title, ep.mime.ifBlank { MIME_HLS }, startMs) }
    withContext(Dispatchers.IO) {
        dlna.failedBeforeSending(
            device, kind = "plugin-hls", stage = "no_lan_url",
            userMessage = "No se pudo obtener la dirección de red del teléfono para enviar el título",
            detail = "lanIp=${ip ?: "none"}",
        )
    }
    return false
}

/** Everything else: [DlnaController.setUrlAndPlay] re-serves [ep] to the TV through DlnaProxyServer. */
private suspend fun sendThroughProxy(
    dlna: DlnaController,
    device: DlnaDevice,
    ep: PlayerData,
    audio: com.arkiv.player.cast.CastAudioChoice?,
    startMs: Long,
    followTv: (() -> Long)? = null,
): Boolean {
    // Which URL the TV's proxy will pull from, and why it matters: `castUrl` (a CDN URL that may need
    // headers the proxy doesn't send) wins over `mediaUrl` (our own loopback proxy, which adds them).
    // Magis: `castUrl` is the raw CDN url, which answers 401 without the `Content-Auth`/`Content-License`
    // headers (see PlayerViewModel's note on it). The DLNA proxy doesn't send them either, so it must
    // pull from `mediaUrl`, OUR local proxy, which adds them. Everything else keeps `castUrl` first
    // (a downloaded file's `castUrl` is the local file server, reachable and header-free).
    val source = if (ep.kind == SourceKind.MAGIS) ep.mediaUrl else (ep.castUrl ?: ep.mediaUrl)
    DlnaLog.i(
        "sendToRenderer: kind=${ep.kind} title='${ep.title.take(40)}' chose=${if (source == ep.mediaUrl) "mediaUrl" else "castUrl"} " +
            "source=${DlnaXml.safeUrl(source)} (castUrl=${DlnaXml.safeUrl(ep.castUrl)}) start=${startMs / 1000}s",
    )
    // Magis is READ through the loopback proxy (it adds the CDN's headers) but its remux is FILED
    // under the CDN url, as the Chromecast's is: stable across token refreshes, and shared with it.
    val keyUrl = ep.castUrl?.takeIf { ep.kind == SourceKind.MAGIS && it.isNotBlank() } ?: source
    return withContext(Dispatchers.IO) { dlna.setUrlAndPlay(device, source, ep.title, audio, startMs, keyUrl, followTv) }
}

/** "Playing on <TV>" bar with pause/stop, visible while a renderer is active. */
@Composable
internal fun BoxScope.ActiveDlnaBar(
    state: DlnaState,
    /** Opens the phone's "Audio y subtítulos" menu: the controls overlay, where it lives, is hidden while casting. */
    onOpenTracks: () -> Unit,
) {
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
            if (state.preparingAudio) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = ArkivRed, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Default.Tv, contentDescription = null, tint = ArkivRed)
            }
            Text(
                if (state.preparingAudio) "Preparando el nuevo audio…" else "Reproduciendo en ${active.friendlyName}",
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onOpenTracks) {
                Icon(Icons.Default.ClosedCaption, contentDescription = "Subtítulos y audio", tint = Color.White)
            }
            TextButton(onClick = { state.togglePause() }, enabled = !state.preparingAudio) {
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
                    // One entry per TV: Play services lists the same Cast device twice.
                    it.dialogFactory = com.arkiv.player.cast.DedupedCastDialogFactory()
                }
            },
        )
    }
}

/**
 * Follows the phone's "Audio y subtítulos" menu while a DLNA cast plays: another audio goes to
 * [DlnaState.audioChanged], another subtitle to [DlnaState.subtitleChanged]. Its own composable so
 * PlayerContent's frame does not grow.
 */
@Composable
internal fun DlnaTracksFollower(
    state: DlnaState,
    tracks: TracksState,
    /** The phone player's position: where the cast began, the fallback when the TV cannot say where it is. */
    phoneMs: () -> Long?,
    lanIp: () -> String?,
    liveHlsProxy: LiveHlsProxy,
) {
    val context = LocalContext.current
    val active = state.active
    val audio = tracks.castAudioChoice
    LaunchedEffect(active, audio?.ordinal) {
        if (active == null) return@LaunchedEffect
        state.audioChanged(audio, phoneMs(), lanIp, liveHlsProxy) {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val spu = tracks.curSpu
    LaunchedEffect(active, spu) {
        if (active != null) state.subtitleChanged(spu)
    }
}
