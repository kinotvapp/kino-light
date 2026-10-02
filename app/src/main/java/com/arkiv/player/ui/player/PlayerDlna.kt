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
import com.arkiv.player.dlna.DlnaSubtitleSwitch
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** A new audio is being remuxed and re-sent ([audioChanged]): the bar says so instead of the TV's name. */
    var preparingAudio by mutableStateOf(false)
        private set

    /** Another subtitle is being sent to the TV ([subtitleChanged]): the bar says so. */
    var changingSubtitles by mutableStateOf(false)
        private set

    /** One subtitle re-send at a time: the next one compares against what the last one left on the TV. */
    private val subtitleSends = Mutex()

    /** What was sent to [active] and with which audio, for [audioChanged] to send it again. */
    private var sentEp: PlayerData? = null
    private var audioOnTv: Int? = null

    /** The renderer accepted the video: from here on the controls drive the TV, not the local one. */
    fun markActive(device: DlnaDevice, ep: PlayerData? = null, audio: com.arkiv.player.cast.CastAudioChoice? = null) {
        active = device
        paused = false
        preparingAudio = false
        changingSubtitles = false
        sentEp = ep
        audioOnTv = audio?.ordinal
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
        lan: DlnaLan,
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
                    val ok = sendToRendererNow(dlna, dev, ep, lan, choice, at, followTv = follow)
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
     * Another subtitle picked in the phone's menu while the TV plays ([DlnaSubtitleSwitch]): what
     * the TV was sent with is compared with what a send would carry now (the app-wide choice,
     * `CastSubtitles`, already updated by `CastSubtitlesSync`), and when they differ the cast is
     * sent again with it -- the audio switch's reload: the TV paused, the same URL re-sent with the
     * new subtitle in its DIDL/headers (none for "Desactivados"), and sought back to where it was
     * ([DlnaAudioSwitch.startMs]: the TV's position, else the phone's, never 0:00). While another
     * audio is being prepared it simply goes along with that send.
     */
    fun subtitleChanged(phoneMs: Long?, notify: (String) -> Unit) {
        val dev = active ?: return
        scope.launch {
            subtitleSends.withLock {
                if (active !== dev) return@withLock
                val (kind, onTv, wanted) = withContext(Dispatchers.IO) {
                    Triple(dlna.activeKind(), dlna.subtitleOnTv(), dlna.subtitleWanted())
                }
                val action = DlnaSubtitleSwitch.onChoiceChanged(kind, onTv, wanted, preparingAudio)
                if (action == DlnaSubtitleSwitch.Action.NONE) return@withLock
                DlnaLog.diag("subtitle while casting: ${if (onTv != null) "on" else "off"} → ${if (wanted != null) "on" else "off"} on $kind · $action")
                if (action == DlnaSubtitleSwitch.Action.WITH_AUDIO) return@withLock
                changingSubtitles = true
                val itemStartMs = sentEp?.startPositionMs ?: 0L
                val ok = withContext(Dispatchers.IO) {
                    dlna.resendForSubtitles(DlnaAudioSwitch.startMs(dlna.tvPositionMs(), phoneMs, itemStartMs))
                }
                // Stopped meanwhile: whoever did that owns the bar now.
                if (active !== dev) return@withLock
                changingSubtitles = false
                // A failed re-send leaves the TV paused (or stopped): the bar says so, "Reanudar" asks it to play.
                paused = !ok
                if (!ok) notify(dlna.lastError ?: "No se pudieron cambiar los subtítulos en la TV")
            }
        }
    }

    fun togglePause() {
        val dev = active ?: return
        scope.launch {
            withContext(Dispatchers.IO) { if (paused) dlna.play(dev) else dlna.pause(dev) }
            paused = !paused
        }
    }

    /**
     * Where the TV is, on the title's clock, while a renderer is [active]: the progress to save and
     * the phone's position for everything that needs one -- the phone's own player sits paused
     * where the cast began ([CastLocalHold]). Null with no DLNA cast. Non-blocking.
     */
    fun progressMs(): Long? = if (active != null) dlna.knownTvPositionMs() else null

    /** Where the TV was when the bar's "Detener" ended the cast, for the phone to resume there; null before. */
    var endedAtMs: Long? = null
        private set

    fun stop() {
        val dev = active ?: return
        scope.launch {
            withContext(Dispatchers.IO) { dlna.stop(dev) }
            endedAtMs = dlna.knownTvPositionMs()
            DlnaLog.diag("cast stopped from the phone: it resumes at ${endedAtMs?.div(1000)}s")
            active = null
            preparingAudio = false
            changingSubtitles = false
        }
    }

    /**
     * The last option for a cast that gave up for good ([com.arkiv.player.cast.CastGaveUp]), while its
     * question is up and it has one to offer ("Descargar y preparar para la TV"); null otherwise.
     */
    var gaveUp by mutableStateOf<DlnaController.Failure?>(null)
        private set

    fun dismissGaveUp() {
        gaveUp = null
    }

    /**
     * Follows the casts that give up for good ([DlnaController.failures]) while the screen is up. One
     * that failed after its send had already returned true -- a fallback stage started by the monitor,
     * or a TV that stopped before ever playing -- is told with [notify] and ended like "Detener" (the
     * phone resumes); one that failed during its send was already told by the sender. Either may offer
     * [gaveUp]'s last option.
     */
    suspend fun followFailures(notify: (String) -> Unit) {
        dlna.failures.collect { f ->
            if (f.lastResort != null) gaveUp = f
            if (!f.async) return@collect
            val dev = active ?: return@collect
            notify(f.message)
            withContext(Dispatchers.IO) { runCatching { dlna.stop(dev) } }
            endedAtMs = dlna.knownTvPositionMs()
            DlnaLog.diag("cast gave up after it was sent: the phone resumes at ${endedAtMs?.div(1000)}s")
            active = null
            preparingAudio = false
            changingSubtitles = false
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
    lan: DlnaLan,
    /**
     * The audio the phone has on. Only a remuxed MPEG-TS can honour it (the remux carries exactly
     * one audio track); a renderer handed the file as-is plays its own default.
     */
    audio: com.arkiv.player.cast.CastAudioChoice? = null,
    /** The phone player's position when the cast was asked for; see [dlnaStartMs]. */
    livePositionMs: Long? = null,
): Boolean = sendToRendererNow(dlna, device, ep, lan, audio, dlnaStartMs(ep, livePositionMs)).also { accepted ->
    // The TV plays it now: the chapter's attempt started, even if the paused local player never does.
    if (accepted && ep != null) CastStarts.accepted(ep.episodeId)
}

private suspend fun sendToRendererNow(
    dlna: DlnaController,
    device: DlnaDevice,
    ep: PlayerData?,
    lan: DlnaLan,
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
        val ip = lan.lanIp()
        val liveUrl = ip?.let { lan.liveHlsProxy.lanUrl(it) }
        DlnaLog.i("sendToRenderer: kind=LIVE title='${ep.title.take(40)}' lanIp=${ip ?: "NONE"} liveProxyUrl=${DlnaXml.safeUrl(liveUrl)}")
        if (ip != null && liveUrl != null) {
            withContext(Dispatchers.IO) {
                // The playlist to a renderer that lists HLS (or nothing); the channel as one continuous
                // TS body to one that lists a TS type and no HLS (the Philips of ERRORES-AMF).
                sendHlsRoute(dlna, device, ep, live = true, kind = "live-hls", playlist = liveUrl) { mime -> lan.liveHlsProxy.lanTsUrl(ip, mime) }
            }
        } else {
            // The proxy has no LAN URL to hand out (no WiFi/LAN address, or the live proxy isn't listening).
            withContext(Dispatchers.IO) {
                dlna.failedBeforeSending(
                    device, kind = "live-hls", stage = "no_lan_url",
                    userMessage = "No se pudo obtener la dirección de red del teléfono para enviar el canal",
                    detail = "lanIp=${ip ?: "none"} proxyUrl=none", episodeId = ep.episodeId, live = true,
                )
            }
            false
        }
    }

    // A plugin title's HLS: PluginCastProxy's token playlist on the LAN, or its continuous TS twin
    // ([sendPluginHls]). A plugin FILE goes through the whole VOD route chain ([sendPluginFile]):
    // its own URL when the TV may fetch it, then PluginCastProxy's loopback url through
    // DlnaProxyServer, like Magis does from its proxy -- never a plugin URL fetched by
    // DlnaProxyServer's own, ungated client -- then, for a TS, the remux.
    SourceKind.PLUGIN -> when {
        ep.mime == MIME_HLS -> sendPluginHls(dlna, device, ep, lan, startMs)
        !ep.mediaUrl.startsWith("http://127.0.0.1:") -> sendPluginFile(dlna, device, ep, lan, audio, startMs, followTv)
        else -> sendThroughProxy(dlna, device, ep, audio, startMs, followTv)
    }

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

/** [ep]'s HLS to [device] as a LAN url the renderer pulls itself (see [dlnaPluginUri]), or its continuous TS twin. */
private suspend fun sendPluginHls(dlna: DlnaController, device: DlnaDevice, ep: PlayerData, lan: DlnaLan, startMs: Long): Boolean {
    val ip = lan.lanIp()
    val url = dlnaPluginUri(ep, ip, lan.pluginProxy)
    val live = com.arkiv.player.data.plugin.PluginIds.isLiveEpisode(ep.episodeId)
    DlnaLog.i("sendToRenderer: kind=PLUGIN hls title='${ep.title.take(40)}' lanIp=${ip ?: "NONE"} url=${DlnaXml.safeUrl(url)}")
    if (url != null) {
        return withContext(Dispatchers.IO) {
            sendHlsRoute(dlna, device, ep, live, kind = "plugin-hls", playlist = url, startMs = startMs) { mime ->
                com.arkiv.player.playback.PluginCastProxy.continuousUrlOf(url, mime, if (live) 0L else startMs)
            }
        }
    }
    withContext(Dispatchers.IO) {
        dlna.failedBeforeSending(
            device, kind = "plugin-hls", stage = "no_lan_url",
            userMessage = "No se pudo obtener la dirección de red del teléfono para enviar el título",
            detail = "lanIp=${ip ?: "none"}", episodeId = ep.episodeId, live = live,
        )
    }
    return false
}

/**
 * An HLS stream ([playlist], a LAN url) to [device] by the route its sink list allows
 * ([DlnaRenderer.liveRoute]): the playlist itself to a renderer that lists HLS (or nothing), the
 * same stream as one continuous MPEG-TS body ([continuous] gives its url for the TS type the
 * renderer lists) to one that lists a TS type and no HLS, and to one that lists neither nothing at
 * all, with [DlnaRenderer.noHlsMessage] (a playlist such a renderer answers with a 500, ERRORES-AMF).
 * Blocking: on IO.
 */
private fun sendHlsRoute(
    dlna: DlnaController,
    device: DlnaDevice,
    ep: PlayerData,
    live: Boolean,
    kind: String,
    playlist: String,
    startMs: Long = 0L,
    continuous: (mime: String) -> String?,
): Boolean {
    val sink = dlna.sinkMimesOf(device)
    return when (val route = com.arkiv.player.dlna.DlnaRenderer.liveRoute(sink)) {
        com.arkiv.player.dlna.DlnaRenderer.HlsRoute.Playlist ->
            dlna.playRawUrl(device, playlist, ep.title, MIME_HLS, if (live) 0L else startMs, ep.episodeId, live)
        is com.arkiv.player.dlna.DlnaRenderer.HlsRoute.ContinuousTs -> {
            val url = continuous(route.mime)
            if (url != null) {
                dlna.playContinuousTs(device, url, ep.title, route.mime, live, if (live) 0L else startMs, ep.episodeId)
            } else {
                dlna.failedBeforeSending(
                    device, kind = kind, stage = "no_lan_url",
                    userMessage = "No se pudo obtener la dirección de red del teléfono para enviar el video",
                    detail = "continuous TS url unavailable", episodeId = ep.episodeId, live = live,
                )
                false
            }
        }
        com.arkiv.player.dlna.DlnaRenderer.HlsRoute.None -> {
            dlna.failedBeforeSending(
                device, kind = kind, stage = "no_hls_route",
                userMessage = com.arkiv.player.dlna.DlnaRenderer.noHlsMessage(live),
                detail = "renderer ${com.arkiv.player.dlna.DlnaRenderer.summary(sink)}", episodeId = ep.episodeId, live = live,
            )
            false
        }
    }
}

/**
 * A plugin's own FILE (an mp4, webm, TS... on the plugin's host) through the whole DLNA VOD route
 * chain ([DlnaController.setUrlAndPlay]): its own URL first when the TV may fetch it ([dlnaDirectUrl]),
 * then PluginCastProxy's loopback url through the phone's proxy, then, for a TS, the remux. The
 * remux and the sniff read the loopback url: the plugin's gated client, never a plain fetch.
 */
private suspend fun sendPluginFile(
    dlna: DlnaController,
    device: DlnaDevice,
    ep: PlayerData,
    lan: DlnaLan,
    audio: com.arkiv.player.cast.CastAudioChoice?,
    startMs: Long,
    followTv: (() -> Long)?,
): Boolean = withContext(Dispatchers.IO) {
    val loopback = if (ep.mediaUrl.startsWith("https://") || ep.mediaUrl.startsWith("http://")) lan.pluginProxy(ep) else null
    DlnaLog.i("sendToRenderer: kind=PLUGIN file mime=${ep.mime.ifBlank { "?" }} title='${ep.title.take(40)}' via ${DlnaXml.safeUrl(loopback)}")
    if (loopback == null) {
        dlna.failedBeforeSending(
            device, kind = "plugin-file", stage = "no_proxy_url",
            userMessage = "No se pudo preparar el título para enviarlo a la TV",
            detail = "source=${DlnaXml.safeUrl(ep.mediaUrl)}", episodeId = ep.episodeId,
        )
        return@withContext false
    }
    dlna.setUrlAndPlay(
        device, loopback, ep.title, audio, startMs, remuxKeyUrl = ep.mediaUrl, followTv = followTv,
        directUrl = dlnaDirectUrl(ep), episodeId = ep.episodeId,
    )
}

/**
 * A plugin file's own URL when a DLNA TV may fetch it itself: plain http (renderers take no https
 * and follow no redirects), no header the TV could not send, and a host the plugin's rules allow
 * ([directCastAllowed]); null otherwise -- then it always goes through the phone.
 */
internal fun dlnaDirectUrl(item: PlayerData): String? =
    item.mediaUrl.takeIf { it.startsWith("http://") && !it.startsWith("http://127.0.0.1:") && item.requestHeaders.isEmpty() && directCastAllowed(item) }

/**
 * What a DLNA renderer is handed for a castable plugin item: always a plain-http URL on the LAN.
 * A remote url is never sent as it is: renderers take no https and follow no redirects -- an LG
 * webOS answered `SetAVTransportURI` with 716 "Resource not found" for an Internet Archive mp4
 * whose https link 302s to a `dnNNN.us.archive.org` mirror (2026-10-01). [register] files it with
 * PluginCastProxy (the plugin's gated client, which follows the redirect upstream under the
 * plugin's host rules; null when the proxy can't take it) and the TV gets that token URL on
 * [lanIp]. A loopback token URL is only respelled ([pluginCastUri]).
 */
internal fun dlnaPluginUri(item: PlayerData, lanIp: String?, register: (PlayerData) -> String?): String? {
    if (lanIp == null) return null
    if (item.mediaUrl.startsWith("http://127.0.0.1:")) return pluginCastUri(item, lanIp)
    if (!item.mediaUrl.startsWith("https://") && !item.mediaUrl.startsWith("http://")) return null
    return register(item)?.let { pluginCastUri(item.copy(mediaUrl = it), lanIp) }
}

/**
 * What a DLNA send needs from the app beyond the renderer: the phone's LAN address, the live
 * channel's proxy and a way to put a direct plugin stream behind PluginCastProxy ([dlnaPluginUri]).
 */
internal class DlnaLan(
    val lanIp: () -> String?,
    val liveHlsProxy: LiveHlsProxy,
    val pluginProxy: (PlayerData) -> String?,
)

/** [DlnaLan] wired to [graph]. Out of `PlayerContent`, which is at ART's verifier limit. */
internal fun dlnaLan(graph: com.arkiv.player.AppGraph): DlnaLan = DlnaLan(
    lanIp = { graph.lanIp() },
    liveHlsProxy = graph.liveHlsProxy,
    pluginProxy = { item -> registerPluginCast(graph, item) },
)

/**
 * The phone's player when the bar's "Detener" ends a DLNA cast ([DlnaState.stop]): a video seeks
 * to where the TV got to ([DlnaState.endedAtMs]) and plays; a live channel primes at its edge and
 * plays ([CastLocalHold.resumeLive]). During the cast it sat silent ([CastLocalHold.hold]).
 */
@Composable
internal fun DlnaLocalHandBack(state: DlnaState, player: androidx.media3.common.Player, isLive: Boolean, magis: androidx.media3.common.Player?, live: androidx.media3.common.Player?) {
    val active = state.active
    var had by remember { mutableStateOf(false) }
    val current by androidx.compose.runtime.rememberUpdatedState(player)
    LaunchedEffect(active) {
        if (active != null) {
            had = true
            return@LaunchedEffect
        }
        if (!had) return@LaunchedEffect
        had = false
        if (isLive) {
            DlnaLog.diag("cast ended: the live channel comes back at its edge on the phone")
            CastLocalHold.resumeLive(magis, live, play = true)
            return@LaunchedEffect
        }
        val at = state.endedAtMs
        DlnaLog.diag("cast ended: the phone resumes at ${at?.div(1000)}s (where the TV was)")
        runCatching {
            if (at != null && at > 0L) current.seekTo(at)
            current.play()
        }
    }
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
    return withContext(Dispatchers.IO) { dlna.setUrlAndPlay(device, source, ep.title, audio, startMs, keyUrl, followTv, episodeId = ep.episodeId) }
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
            val busy = state.preparingAudio || state.changingSubtitles
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = ArkivRed, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Default.Tv, contentDescription = null, tint = ArkivRed)
            }
            Text(
                when {
                    state.preparingAudio -> "Preparando el nuevo audio…"
                    state.changingSubtitles -> "Cambiando subtítulos en la TV…"
                    else -> "Reproduciendo en ${active.friendlyName}"
                },
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onOpenTracks) {
                Icon(Icons.Default.ClosedCaption, contentDescription = "Subtítulos y audio", tint = Color.White)
            }
            TextButton(onClick = { state.togglePause() }, enabled = !busy) {
                Text(if (state.paused) "Reanudar" else "Pausar")
            }
            TextButton(onClick = { state.stop() }) { Text("Detener") }
        }
    }
}

/**
 * Dialog of found devices. Doesn't know what's playing: choosing a renderer only notifies
 * [onChoose], which is the one that builds the URL and confirms with [DlnaState.markActive]
 * (`atMs`: where the TV should start, null = where the phone's player is).
 *
 * Always composed with the player, so it also hosts what reaches the DLNA side from elsewhere:
 * the casts that give up after they were sent ([DlnaState.followFailures]), their last option
 * ([DlnaState.gaveUp]), and "Probar por DLNA en <TV>" from the Chromecast's trouble dialog
 * ([CastToDlnaHandoff]), which is [onChoose] with the TV it found and the Chromecast's position.
 * Also "Enviar a la TV" from outside the player ([SendToTvPrompt]); and a TV picked here for a title
 * downloaded since this player opened it reopens it from the file first ([reopenLocalForDlna]).
 */
@Composable
internal fun DlnaDevicesDialog(
    state: DlnaState,
    onChoose: (DlnaDevice, Long?) -> Unit,
) {
    val context = LocalContext.current
    val choose by androidx.compose.runtime.rememberUpdatedState(onChoose)
    LaunchedEffect(state) {
        state.followFailures { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(Unit) {
        CastToDlnaHandoff.requests.collect { r -> CastToDlnaHandoff.take(r)?.let { choose(it.device, it.atMs) } }
    }
    val graph = com.arkiv.player.ui.rememberGraph()
    SendToTvPrompt(state, graph)
    state.gaveUp?.let { f ->
        val action = f.lastResort
        if (action != null) {
            AlertDialog(
                onDismissRequest = { state.dismissGaveUp() },
                title = { Text("No se pudo reproducir en la TV") },
                text = {
                    Column {
                        Text(f.message)
                        Text(action.explanation, color = ArkivTextSecondary, modifier = Modifier.padding(top = 12.dp))
                    }
                },
                confirmButton = { TextButton(onClick = { state.dismissGaveUp(); action.start(f.exhausted) }) { Text(action.label) } },
                dismissButton = { TextButton(onClick = { state.dismissGaveUp() }) { Text("Cerrar") } },
            )
        }
    }
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
                                    // Downloaded since this player opened it: the TV gets the file.
                                    if (!reopenLocalForDlna(graph, device)) onChoose(device, null)
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
                // Always the dark theme: the button picks its icon from the theme it is given
                // (MediaRouterThemeHelper: a light one -> Theme.MediaRouter.Light -> the BLACK
                // mr_button_light). DayNight followed the phone, so on a phone in light mode (the
                // Redmi, 2026-10-01) the icon was black on the player's dark bar: invisible, yet
                // tappable. Dark -> mr_button_dark, white like the DLNA icon next to it.
                val themed = ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat)
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
    lan: DlnaLan,
) {
    val context = LocalContext.current
    val active = state.active
    val audio = tracks.castAudioChoice
    LaunchedEffect(active, audio?.ordinal) {
        if (active == null) return@LaunchedEffect
        state.audioChanged(audio, phoneMs(), lan) {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // The menu's choice as the cast sees it: the same key CastSubtitlesSync hands to CastSubtitles.
    val subtitle = tracks.castTextSelection
    LaunchedEffect(active, subtitle) {
        if (active == null) return@LaunchedEffect
        state.subtitleChanged(phoneMs()) {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * "Probar por DLNA en <TV>" from the Chromecast's trouble dialog (`CastTroubleDialog`, at the app's
 * root) to the player screen that shows the title ([DlnaDevicesDialog] takes it): the DLNA renderer
 * found at the Chromecast's address, and where the Chromecast was. Taken once; a request older than
 * [MAX_AGE_MS] (no player screen took it) is dropped.
 */
internal object CastToDlnaHandoff {
    class Request(val device: DlnaDevice, val atMs: Long?, val createdAt: Long = System.currentTimeMillis())

    val requests = kotlinx.coroutines.flow.MutableStateFlow<Request?>(null)

    const val MAX_AGE_MS = 30_000L

    fun offer(device: DlnaDevice, atMs: Long?) {
        requests.value = Request(device, atMs)
    }

    /** [r] when it is still the pending one and fresh; it is never handed out twice. */
    fun take(r: Request?, now: Long = System.currentTimeMillis()): Request? {
        if (r == null || !requests.compareAndSet(r, null)) return null
        return r.takeIf { now - it.createdAt <= MAX_AGE_MS }
    }
}
