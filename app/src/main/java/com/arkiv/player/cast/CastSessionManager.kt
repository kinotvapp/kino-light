package com.arkiv.player.cast

import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.arkiv.player.crash.Crash
import com.arkiv.player.crash.CastFailure
import com.arkiv.player.data.ArkivRepository
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owner of the [CastPlayer] and the Chromecast session, with application lifetime.
 *
 * Lives here and not in the player's composable because `CastPlayer.release()` calls
 * `SessionManager.endCurrentSession(false)` (verified in media3-cast 1.5.1's bytecode): releasing
 * it cuts the cast. While it lived inside the screen, leaving the player killed the session.
 *
 * Since the CastPlayer is built ONCE, the listener receives every session, which also gets rid of
 * an old problem: media3 doesn't re-fire `onCastSessionAvailable` for a session that was already
 * open when it was built.
 */
class CastSessionManager(
    private val castContext: CastContext,
    private val repository: ArkivRepository,
    private val scope: CoroutineScope,
    /**
     * Turns a foreground service on or off around a cast session (the name is the receiver's).
     *
     * With Chromecast the PHONE serves the video too (the remuxed MP4 comes from a server inside this app), and
     * Android cuts the app's network and freezes it soon after the person leaves Kino: measured on a Galaxy S24+,
     * the remux died with "Muxer error" 17.6 s and 17.9 s after the app went to the background, both times, and
     * the receiver stopped once the file stopped growing. A foreground service is exempt.
     */
    private val keepAlive: (on: Boolean, receiver: String) -> Unit = { _, _ -> },
    /**
     * Is this URL still answered by whatever on this device served it? False only for a URL into one
     * of our LAN servers whose port or token is gone (they are torn down with the session that used
     * them): such a request is never replayed on a reconnect -- see [CastReconnect].
     */
    private val stillServed: (uri: String) -> Boolean = { true },
    /**
     * The session ended: [intentional] when the person pressed stop. What the phone serves for the
     * TV (the growing remux and its export) is not needed any more -- nobody else stops it when the
     * player screen is closed (review 2026-10-01).
     */
    private val onEnded: (intentional: Boolean) -> Unit = {},
    /**
     * A (re)connect replays [request] from its `startPositionMs`: lets the server behind its URL
     * start its playlist there too (the remux's `#EXT-X-START` still said the first cast's start).
     */
    private val onReplay: (request: CastRequest) -> Unit = {},
) {
    // Fails fast and with an explicit cause if something builds this off the main thread, instead
    // of an obscure crash inside the Cast SDK (CastPlayer/CastContext require it, see class doc).
    init {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "CastSessionManager must be built on the main thread: CastPlayer and CastContext require it"
        }
    }

    // Custom converter, because the default one never states the duration -- see
    // [DurationAwareMediaItemConverter] for what that costs on a fragmented MP4 still being written.
    val player: CastPlayer = CastPlayer(castContext, DurationAwareMediaItemConverter())

    private val _casting = MutableStateFlow(false)
    val casting: StateFlow<Boolean> = _casting.asStateFlow()

    /** The last thing requested to cast; gets (re)loaded as soon as there's a session. */
    @Volatile private var pending: CastRequest? = null

    /**
     * [pending]'s generation: increases with every genuinely NEW [setMedia], never with a
     * reconnection that reloads the same request (the `onCastSessionAvailable` listener, below,
     * calls `load()` directly without going through here). Without this, re-casting the SAME
     * episode is indistinguishable from "what was playing before is still going", and the
     * Chromecast bar couldn't be restored in that case (see MarcaFuente/sellarMarca in
     * NowPlayingCoordinator).
     */
    @Volatile private var generation = 0

    /** What was requested from the receiver: title, artwork and episode for the bar come from here. */
    val currentRequest: CastRequest? get() = pending

    /** [currentRequest]'s generation -- see the comment next to `generation`. */
    val mediaGeneration: Int get() = generation

    /** True once THIS session already reported a failure to GlitchTip: a stalled receiver would
     *  otherwise report on every poll. Reset on every new session, see `onCastSessionAvailable`. */
    @Volatile private var sessionReported = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** The receiver dropping its media on its own, and what to do about it. See [CastIdleWatch]. */
    private val idleWatch = CastIdleWatch()

    /**
     * Something the phone has to tell about the cast: the receiver stopped by itself and is being
     * retried, or it stopped again and the person has to choose. Null when all is well.
     */
    data class Trouble(val title: String, val retrying: Boolean)

    private val _trouble = MutableStateFlow<Trouble?>(null)
    val trouble: StateFlow<Trouble?> = _trouble.asStateFlow()

    /** The receiver's status client this manager listens to, for the session in course. */
    private var statusClient: com.google.android.gms.cast.framework.media.RemoteMediaClient? = null

    /** Was the receiver loading/buffering/playing/paused since the last idle handled? Main thread. */
    private var receiverActive = false

    /** When an automatic or asked-for retry was sent and has not played yet, or 0. */
    @Volatile private var retryingSince = 0L

    /**
     * When the last load was handed to the receiver and it has not played since, or 0. The
     * watchdog over it ([onLoadStalled]) is what keeps "Cargando en el receptor…" from hanging: a
     * load that fails on the receiver without ever leaving idle reports nothing at all to the phone
     * (measured 2026-10-01: a live load refused with ERR_CONNECTION_REFUSED took the next load's
     * video element down with it, and the phone showed "Cargando…" with idle heartbeats for minutes).
     */
    @Volatile private var loadSentAt = 0L

    /**
     * The URL of the last load, so the watchdog over [loadSentAt] is only cleared by THAT media
     * playing: on a reload (another audio) the old item's PLAYING arrives first and disarmed it.
     */
    @Volatile private var loadedUri: String? = null

    /**
     * Player screens in the foreground right now. While one is, a (re)connect replays nothing: the
     * screen sends its own fresh load for what it shows the moment the session is up, and the
     * replay landed 6 ms ahead of it -- two LOADs on one connect, the older one stale (a live
     * channel left from before, whose failure killed the title the person had just opened).
     */
    private val openScreens = java.util.concurrent.atomic.AtomicInteger(0)

    /** Last receiver state/idle reason written to [CastDiag], so only the changes are. */
    private var lastDiagStatus = ""

    private fun diagStatus(playerState: Int, idleReason: Int, positionMs: Long) {
        val key = "$playerState/$idleReason"
        if (key == lastDiagStatus) return
        lastDiagStatus = key
        CastDiag.i(
            "receiver status state=$playerState (1=idle 2=playing 3=paused 4=buffering 5=loading) " +
                "idleReason=$idleReason (1=finished 2=cancelled 3=interrupted 4=error) pos=${positionMs}ms active=$receiverActive",
        )
    }

    private val statusCallback = object : com.google.android.gms.cast.framework.media.RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = onReceiverStatus()
    }

    /**
     * Sends ONE [CastFailure] for the current session, with whatever the receiver's own diagnostics
     * already know: model, what was asked of it, and the video track it ended up with (or didn't).
     */
    private fun reportFailure(reason: String, error: androidx.media3.common.PlaybackException? = null) {
        // Everything below reads the CastPlayer, and the Cast SDK asserts the main thread on those
        // reads (`currentPosition` -> RemoteMediaClient.getApproximateStreamPosition). A call from
        // the progress loop's background dispatcher used to crash the app (ERRORES-8E9), so any
        // off-main caller is bounced to the main looper instead.
        val hopped = MainThreadHop.run(
            onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper(),
            post = { mainHandler.post(it) },
        ) { reportFailure(reason, error) }
        if (hopped) return
        if (sessionReported) return
        sessionReported = true
        val device = runCatching { castContext.sessionManager.currentCastSession?.castDevice }.getOrNull()
        val videoFormat = runCatching {
            player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                ?.takeIf { it.length > 0 }?.getTrackFormat(0)
        }.getOrNull()
        Crash.report(
            error ?: CastFailure(reason),
            "cast-failure",
            extras = mapOf(
                "reason" to reason,
                "receiver" to (device?.friendlyName ?: ""),
                "receiver_model" to (device?.modelName ?: ""),
                "episode" to (pending?.episodeId ?: ""),
                "requested_mime" to (pending?.mimeType ?: ""),
                "video_codec" to (videoFormat?.sampleMimeType ?: ""),
                "video_size" to (videoFormat?.let { "${it.width}x${it.height}" } ?: ""),
                "playback_state" to player.playbackState.toString(),
                "position_ms" to player.currentPosition.toString(),
            ),
        )
    }

    /**
     * Receiver diagnostics. Without this, a TV that REJECTS the media (a container or codec it
     * doesn't support) fails in absolute silence: nothing happens on the phone and there's nothing
     * to see or hear on the TV, with not a single clue why. It's the blind spot that already made
     * us misdiagnose something once.
     *
     * Lives here and not on the screen because the CastPlayer belongs to the app: this way the
     * logs keep coming out when casting with the player closed, which is exactly what this feature
     * enabled.
     *
     * Declared BEFORE `init` on purpose: properties are initialized in declaration order, so a
     * `val` placed after it would still be null when hooking it up.
     */
    private val diagnostics = object : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e(
                TAG,
                "the receiver failed: code=${error.errorCode} (${error.errorCodeName}) · ${error.message}",
                error,
            )
            reportFailure("player_error: ${error.errorCodeName}", error)
        }

        override fun onPlaybackStateChanged(state: Int) {
            android.util.Log.i(TAG, "receiver state: $state (1=idle 2=buffering 3=ready 4=ended)")
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            android.util.Log.i(
                TAG,
                "receiver playing=$playing · pos=${player.currentPosition}ms · dur=${player.duration}ms",
            )
        }

        /**
         * The most informative thing for "it plays but doesn't sound": which tracks the receiver
         * ACCEPTED. If the TV drops the audio due to codec (DTS often isn't there), here you see
         * the selected video track and the missing or unsupported audio one, with no error popping
         * up.
         */
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (tracks.groups.isEmpty()) {
                android.util.Log.w(TAG, "the receiver isn't reporting ANY track")
                return
            }
            var hasVideo = false
            var videoSupported = false
            var hasAudio = false
            var audioSupported = false
            tracks.groups.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    android.util.Log.i(
                        TAG,
                        "track type=${g.type} codec=${f.codecs} mime=${f.sampleMimeType} " +
                            "language=${f.language} supported=${g.isTrackSupported(i)} selected=${g.isTrackSelected(i)}",
                    )
                    when (g.type) {
                        C.TRACK_TYPE_VIDEO -> { hasVideo = true; videoSupported = videoSupported || g.isTrackSupported(i) }
                        C.TRACK_TYPE_AUDIO -> { hasAudio = true; audioSupported = audioSupported || g.isTrackSupported(i) }
                    }
                }
            }
            // Sound but no picture: the receiver took the audio and rejected every video track it was
            // offered. Silent otherwise -- nothing on the phone, nothing on the TV, no clue why -- until
            // this. Typically an older Chromecast whose decoder can't take the profile.
            if (hasVideo && !videoSupported && hasAudio && audioSupported) {
                reportFailure("video_track_unsupported")
            }
        }
    }

    init {
        player.addListener(diagnostics)
        player.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                _casting.value = true
                sessionReported = false
                val device = runCatching {
                    castContext.sessionManager.currentCastSession?.castDevice?.friendlyName
                }.getOrNull()
                android.util.Log.i(TAG, "session available · receiver=${device ?: "?"} · pending=${pending?.episodeId}")
                keepAlive(true, device ?: "el Chromecast")
                watchReceiverStatus()
                replayOnConnect()
            }

            override fun onCastSessionUnavailable() {
                android.util.Log.i(TAG, "session gone")
                _casting.value = false
                keepAlive(false, "")
                unwatchReceiverStatus()
                _trouble.value = null
                loadSentAt = 0L
                runCatching { onEnded(false) }
            }
        })
        // The one case the listener does NOT cover: starting the app with a session already alive
        // (it was killed and reopened while casting). It happened before we existed, so it's
        // adopted by hand.
        if (runCatching { castContext.sessionManager.currentCastSession?.isConnected }.getOrNull() == true) {
            _casting.value = true
            watchReceiverStatus()
        }
        startProgressLoop()
    }

    /** Requests casting this. Loads right away if there's already a session; otherwise stays pending until there is one. */
    fun setMedia(request: CastRequest) {
        generation++
        pending = request
        idleWatch.onNewMedia(request.episodeId, if (request.asLive) 0L else request.startPositionMs)
        retryingSince = 0L
        _trouble.value = null
        // Without this line "nothing was ever asked of the receiver" and "it was asked and refused"
        // look identical from a log: both end up as a session with an idle player.
        android.util.Log.i(
            TAG,
            "cast requested · ep=${request.episodeId} · mime=${request.mimeType} · from=${request.startPositionMs}ms " +
                "· session=${_casting.value} · ${com.arkiv.player.dlna.DlnaXml.safeUrl(request.uri)}",
        )
        if (_casting.value) scope.launch { load(request) } else {
            android.util.Log.i(TAG, "no session yet: it stays pending until one shows up")
        }
    }

    /**
     * Did the last session end because the user pressed "stop"?
     *
     * Exists because the disconnection path resumes local playback where the receiver left off --
     * correct when disconnecting from the cast button -- but that would be absurd after pressing
     * stop: the user asked for silence and the phone would start playing. Consumed exactly once.
     */
    @Volatile private var intentionalStopAtMs = 0L

    fun stopIntentionally() {
        intentionalStopAtMs = System.currentTimeMillis()
        idleWatch.onIntentionalStop()
        _trouble.value = null
        pending = null
        // The state turns off here instead of waiting for the listener: if onCastSessionUnavailable
        // somehow didn't arrive, the phone's bar would be left hanging, showing a dead cast.
        _casting.value = false
        keepAlive(false, "")
        runCatching { onEnded(true) }
        scope.launch {
            withContext(Dispatchers.Main) {
                // player.stop() is NOT called here: endCurrentSession(true) -- below -- already
                // turns off the receiver app (that's what the `true` is for), so stop() would be
                // redundant. And worse than redundant: the CastPlayer is deliberately left to NEVER
                // stop, because PlayerScreen.kt depends on `currentPosition` forever returning the
                // last reported position so it can resume the local player at the right spot (see
                // the comment there). Stopping it here would also race startProgressLoop: if the
                // stop landed exactly between that loop reading `_casting`/`pending` on IO and
                // jumping to Main to read the player, it could end up persisting a position of zero
                // or a rolled-back one over the user's real resume point.
                // Ends the session WITHOUT destroying the CastPlayer: release() would leave it
                // unusable and casting again would require restarting the app. `true` also turns
                // off the receiver app, which is what makes the TV go back to its own thing --
                // release() uses `false`.
                runCatching { castContext.sessionManager.endCurrentSession(true) }
            }
        }
    }

    /** Consumes the flag: the next disconnection resumes normally again. */
    fun consumeIntentionalStop(): Boolean {
        val at = intentionalStopAtMs
        intentionalStopAtMs = 0L
        // With a window, because whoever sets it and whoever consumes it don't always match: the
        // stop button lives on the bar and on "Now Playing", and the player isn't composed in
        // either of those, so normally NOBODY consumes it. Without a bound it would stay on
        // forever and get eaten by the next legitimate disconnection from the cast button,
        // skipping the local resume that re-bakes the :start-time.
        return at != 0L && System.currentTimeMillis() - at < STOP_WINDOW_MS
    }

    /**
     * Appends one more finished chunk to what the receiver is already playing.
     *
     * A title cast this way is a QUEUE of complete little mp4s rather than one file: each states
     * its own duration and never changes, so the receiver has nothing to recompute. See
     * `TsRemuxer.remuxChunk` for why a single growing file could not work.
     */
    fun enqueue(uri: String, episodeId: String, title: String) {
        // The duration rides along so the converter can set autoplay and preload on the queue item
        // -- without them the receiver announces each entry with a countdown.
        scope.launch(Dispatchers.Main) {
            runCatching {
                player.addMediaItem(
                    MediaItem.Builder()
                        .setUri(uri)
                        .setMimeType("video/mp4")
                        .setMediaId(episodeId)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                        .build(),
                )
                android.util.Log.i(TAG, "queued one more chunk · ${player.mediaItemCount} in the queue")
            }.onFailure { android.util.Log.w(TAG, "could not queue a chunk: $it") }
        }
    }

    private suspend fun load(r: CastRequest) = withContext(Dispatchers.Main) {
        idleWatch.onOwnLoad()
        loadedUri = r.uri
        loadSentAt = System.currentTimeMillis()
        android.util.Log.i(
            TAG,
            "loading on the receiver · mime=${r.mimeType} · from=${r.startPositionMs}ms · ${com.arkiv.player.dlna.DlnaXml.safeUrl(r.uri)}",
        )
        val loadOutcome = runCatching { player.setMediaItem(
            MediaItem.Builder()
                .setUri(r.uri)
                .setMimeType(r.mimeType)
                .setMediaId(r.episodeId)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setExtras(
                            android.os.Bundle().apply {
                                putLong(DurationAwareMediaItemConverter.KEY_DURATION_MS, r.durationMs)
                                putBoolean(DurationAwareMediaItemConverter.KEY_LIVE, r.asLive)
                                putBoolean(DurationAwareMediaItemConverter.KEY_HLS_FMP4, r.hlsFmp4)
                            },
                        )
                        .build(),
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(r.title)
                        .setArtist(r.subtitle)
                        .apply { if (r.artworkUrl.isNotBlank()) setArtworkUri(android.net.Uri.parse(r.artworkUrl)) }
                        .build(),
                )
                .build(),
            r.startPositionMs,
        ) }
        loadOutcome.onFailure { android.util.Log.e(TAG, "setMediaItem FAILED: $it", it) }
        player.playWhenReady = true
        runCatching { player.prepare() }
            .onFailure { android.util.Log.e(TAG, "prepare FAILED: $it", it) }
        android.util.Log.i(
            TAG,
            "load handed to the receiver · state=${player.playbackState} · item=${player.currentMediaItem?.mediaId}",
        )
    }

    /**
     * What a (re)connect loads: the pending request only while its URL is still served, resumed
     * where the receiver last was. A request whose server or token is gone is dropped instead --
     * replaying it gave the receiver a dead token or a closed port, and the right load only came
     * 30-40 s later. Whoever owns the title (the player screen) sends a fresh one on its own.
     */
    private fun replayOnConnect() {
        val r = pending
        if (r == null) {
            android.util.Log.w(TAG, "session available but nothing pending: nothing will be loaded")
            return
        }
        if (openScreens.get() > 0) {
            // Newest request wins, one LOAD per connect: the open player screen builds a fresh one
            // from what it shows (see [openScreens]). The old one is dropped, so a screen that
            // ends up not casting leaves no stale title behind for the bar or a later reconnect.
            android.util.Log.i(TAG, "session available · a player screen is open and sends its own load: NOT replaying ${r.episodeId}")
            pending = null
            return
        }
        val lastKnown = idleWatch.lastKnownMs(r.episodeId)
        if (CastReconnect.replay(r, stillServed, lastKnown) == null) {
            android.util.Log.w(
                TAG,
                "session available · the pending load's server/token is gone, NOT replaying it " +
                    "(${com.arkiv.player.dlna.DlnaXml.safeUrl(r.uri)}): waiting for a fresh one",
            )
            pending = null
            return
        }
        scope.launch {
            // Nothing known of this load (the receiver never reported, and it went out "from the
            // top"): the progress saved for the title is the best there is, never 0:00 by default.
            val saved = if (CastReconnect.needsSaved(r, lastKnown)) {
                runCatching { repository.getPlayback(r.episodeId) }.getOrNull()
                    ?.takeIf { !it.deleted }?.let { it.positionMs - r.offsetMs }
            } else {
                null
            }
            val replay = CastReconnect.replay(r, stillServed, lastKnown, saved) ?: return@launch
            android.util.Log.i(TAG, "session available · replaying ${r.episodeId} from ${replay.startPositionMs}ms")
            runCatching { onReplay(replay) }
            load(replay)
        }
    }

    private fun watchReceiverStatus() {
        val client = runCatching { castContext.sessionManager.currentCastSession?.remoteMediaClient }.getOrNull()
        if (client === statusClient) return
        unwatchReceiverStatus()
        statusClient = client
        runCatching { client?.registerCallback(statusCallback) }
    }

    private fun unwatchReceiverStatus() {
        runCatching { statusClient?.unregisterCallback(statusCallback) }
        statusClient = null
        receiverActive = false
        retryingSince = 0L
    }

    /**
     * The receiver's own status, straight from the Cast SDK: the only place a receiver that went
     * IDLE by itself shows up (see [CastIdleWatch]). Main thread, like every RemoteMediaClient call.
     */
    private fun onReceiverStatus() {
        val client = statusClient ?: return
        val status = client.mediaStatus ?: return
        val r = pending
        diagStatus(status.playerState, status.idleReason, client.approximateStreamPosition)
        when (status.playerState) {
            MediaStatus.PLAYER_STATE_UNKNOWN -> Unit
            MediaStatus.PLAYER_STATE_IDLE -> {
                // Only an idle that FOLLOWS activity: the SDK repeats the same idle status, and a
                // status still describing the media before our load must not count against it.
                if (!receiverActive) return
                receiverActive = false
                onReceiverIdle(CastIdleWatch.Idle.fromCast(status.idleReason), r)
            }
            else -> {
                // Playing, paused, buffering or loading.
                receiverActive = true
                val playing = status.playerState == MediaStatus.PLAYER_STATE_PLAYING
                // Paused counts too: the receiver got far enough to have a picture to pause on. Only
                // the media of the last load: the one before it still reports PLAYING meanwhile.
                if ((playing || status.playerState == MediaStatus.PLAYER_STATE_PAUSED) &&
                    CastIdleWatch.isLoadedMedia(status.mediaInfo?.contentUrl, loadedUri)
                ) {
                    loadSentAt = 0L
                }
                if (r != null && player.currentMediaItem?.mediaId == r.episodeId) {
                    idleWatch.onPosition(r.episodeId, client.approximateStreamPosition, playing)
                }
                if (playing && _trouble.value != null) {
                    _trouble.value = null
                    retryingSince = 0L
                }
            }
        }
    }

    private fun onReceiverIdle(reason: CastIdleWatch.Idle, r: CastRequest?) {
        val decision = idleWatch.onIdle(reason, r?.episodeId, r?.durationMs ?: 0L)
        android.util.Log.w(TAG, "receiver went IDLE by itself · reason=$reason · ep=${r?.episodeId} → $decision")
        if (r == null) return
        when (decision) {
            CastIdleWatch.Decision.Ignore -> Unit
            is CastIdleWatch.Decision.Retry -> {
                reportFailure("receiver_idle_${reason.name.lowercase()}")
                _trouble.value = Trouble(r.title, retrying = true)
                reload(r, decision.fromMs)
            }
            is CastIdleWatch.Decision.Ask -> _trouble.value = Trouble(r.title, retrying = false)
        }
    }

    /** Loads [r] again from [fromMs] (null: its own start). A live stream always from its start. */
    private fun reload(r: CastRequest, fromMs: Long?) {
        if (!stillServed(r.uri)) {
            android.util.Log.w(TAG, "retry skipped: the request's server/token is gone")
            _trouble.value = Trouble(r.title, retrying = false)
            return
        }
        val from = if (r.asLive || r.durationMs <= 0L) r.startPositionMs else fromMs ?: r.startPositionMs
        android.util.Log.w(TAG, "retrying the receiver from ${from}ms")
        retryingSince = System.currentTimeMillis()
        scope.launch { load(r.copy(startPositionMs = from)) }
    }

    /** A retry the receiver never got to play: ask instead of waiting on it forever. Main thread. */
    private fun onRetryStalled() {
        retryingSince = 0L
        val r = pending ?: return
        if (player.isPlaying) return
        val decision = idleWatch.onRetryStalled()
        android.util.Log.w(TAG, "the retry never played · ${RETRY_STALL_MS}ms → $decision")
        if (decision is CastIdleWatch.Decision.Ask) _trouble.value = Trouble(r.title, retrying = false)
    }

    /**
     * The freshest position known for [episodeId] while it is the cast title: what the receiver
     * last reported, else where its load started; null for anything else. Main thread.
     */
    fun lastKnownPositionMs(episodeId: String): Long? = idleWatch.lastKnownMs(episodeId)

    /**
     * A load the receiver never got to play within [LOAD_STALL_MS]: ask ("Reintentar" / "Ver en el
     * celular") instead of leaving "Cargando en el receptor…" up forever. A retry in course has its
     * own watch ([onRetryStalled]). Main thread.
     */
    private fun onLoadStalled() {
        loadSentAt = 0L
        val r = pending ?: return
        if (player.isPlaying || retryingSince > 0L) return
        val decision = idleWatch.onLoadStalled()
        android.util.Log.w(TAG, "the load never played · ${LOAD_STALL_MS}ms · ep=${r.episodeId} → $decision")
        if (decision is CastIdleWatch.Decision.Ask) {
            reportFailure("load_never_played")
            _trouble.value = Trouble(r.title, retrying = false)
        }
    }

    /**
     * A player screen came to the foreground showing [episodeId] (or left it, [episodeId] null).
     * Outside a cast, a pending request for anything else is stale -- left from a title the person
     * moved away from -- and is dropped so no later connect replays it. Inside a cast it stays:
     * the cast goes on with the player closed, and the bar and the progress save read it.
     */
    fun onScreenTitle(episodeId: String?) {
        if (_casting.value) return
        val p = pending ?: return
        if (p.episodeId == episodeId) return
        android.util.Log.i(TAG, "dropping the stale pending load of ${p.episodeId} (screen now on ${episodeId ?: "nothing"})")
        pending = null
    }

    /** A player screen entered ([open] true) or left the foreground. See [openScreens]. */
    fun onScreenForeground(open: Boolean) {
        if (open) openScreens.incrementAndGet() else openScreens.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** "Reintentar": one more load from where the receiver last was. */
    fun retryAfterTrouble() {
        val r = pending ?: run { _trouble.value = null; return }
        idleWatch.onUserRetry()
        _trouble.value = Trouble(r.title, retrying = true)
        reload(r, idleWatch.lastKnownMs(r.episodeId))
    }

    /**
     * "Ver en el celular": ends the session like the cast button does, so the player resumes on the
     * phone. The receiver no longer reports a position, so the phone picks up where its own player
     * was left; the progress saved while casting (up to the drop) is kept as it is.
     */
    fun watchOnPhone() {
        _trouble.value = null
        // The person chose the phone: nothing of this title may come back on its own at the next
        // connect (the replay would load it over whatever they pick next).
        pending = null
        loadSentAt = 0L
        scope.launch(Dispatchers.Main) { runCatching { castContext.sessionManager.endCurrentSession(true) } }
    }

    /** The message was dismissed without choosing: the session is left as it is. */
    fun dismissTrouble() {
        _trouble.value = null
        // A later stall of the same media asks again instead of leaving "Cargando…" up forever.
        idleWatch.onDismissed()
    }

    /**
     * Persists progress while casting, EVEN WITH the player closed. Without this, watching a whole
     * episode from the home screen would only save the position up to the moment the screen was
     * left: the same silent loss the rest of this feature exists to avoid.
     */
    private fun startProgressLoop() {
        scope.launch {
            // Consecutive ticks the receiver has had this same episode loaded without reaching
            // READY/ENDED: the OTHER way a cast "does nothing" (media WAS handed to it, unlike the
            // "nothing pending" case above) -- the idle logo sitting there because the receiver never
            // got past buffering, not because nothing was ever asked of it.
            var stuckTicks = 0
            while (true) {
                delay(PROGRESS_MS)
                if (!_casting.value) { stuckTicks = 0; continue }
                val since = retryingSince
                if (since > 0L && System.currentTimeMillis() - since > RETRY_STALL_MS) {
                    withContext(Dispatchers.Main) { onRetryStalled() }
                }
                val loadAt = loadSentAt
                if (loadAt > 0L && System.currentTimeMillis() - loadAt > LOAD_STALL_MS) {
                    withContext(Dispatchers.Main) { onLoadStalled() }
                }
                val request = pending
                if (request == null) {
                    // A session with nothing pending is one of the two ways a cast "does nothing",
                    // and the one that used to leave no trace at all: the receiver sits on its logo
                    // because it was never handed any media, not because it refused ours.
                    android.util.Log.w(TAG, "casting with NOTHING pending: the receiver was never given media")
                    stuckTicks = 0
                    continue
                }
                val epId = request.episodeId
                // mediaId, position and duration are read together in the same main-thread tick:
                // setMedia() updates `pending` synchronously but the receiver takes (network) time
                // to load the new item, so without this check the old episode's position/duration
                // could get saved under the new one's id.
                var notReady = false
                val (mediaId, pos, dur) = withContext(Dispatchers.Main) {
                    notReady = player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_ENDED
                    val id = player.currentMediaItem?.mediaId
                    // The last position the receiver reported for it: what a retry or a reconnect
                    // resumes from once the receiver has dropped its media and reports nothing.
                    if (id == epId && !notReady) idleWatch.onPosition(epId, player.currentPosition, player.isPlaying)
                    Triple(id, player.currentPosition, player.duration)
                }
                if (mediaId != epId) { stuckTicks = 0; continue }
                stuckTicks = if (notReady) stuckTicks + 1 else 0
                if (stuckTicks == STUCK_TICKS) {
                    android.util.Log.w(TAG, "receiver stuck loading · ${stuckTicks * PROGRESS_MS}ms with no READY")
                    // reportFailure reads the CastPlayer: main thread only (this loop runs on a
                    // background dispatcher).
                    withContext(Dispatchers.Main) { reportFailure("stuck_loading") }
                }
                // Without a transcoder the receiver reports the real position and duration; the
                // only reason not to save is a live stream, which sends TIME_UNSET.
                // The receiver reports no duration for a stream announced as live, and a remux
                // being written is announced exactly that way -- so without this, casting a title
                // saved nothing at all and "continue watching" quietly stopped working. The phone
                // knows the duration (it has been drawing the bar with it) and knows where the
                // remux was clipped, so both are supplied here rather than trusted from the TV.
                val durReal = if (dur > 0L) dur else request.durationMs
                val progress = CastProgress.toSave(
                    reportedPosMs = pos + request.offsetMs,
                    reportedDurMs = durReal,
                )
                if (progress == null) {
                    // Loud on purpose -- born diagnosing "torrent always restarts from zero" (a
                    // source removed in this branch's pruning); if this shows up for VOD, progress
                    // is NOT being saved and the culprit is the duration (the receiver sent
                    // TIME_UNSET, which live streams do and downloads should not).
                    android.util.Log.w(TAG, "progress NOT saved · pos=${pos}ms receiverDur=${dur}ms")
                    continue
                }
                runCatching { repository.savePlayback(epId, progress.positionMs, progress.durationMs) }
                    .onFailure { android.util.Log.w(TAG, "couldn't save the progress: ${it.message}") }
            }
        }
    }

    private companion object {
        const val TAG = "ArkivCast"
        const val PROGRESS_MS = 5_000L
        const val STOP_WINDOW_MS = 15_000L
        /** Ticks of [PROGRESS_MS] (30s) a loaded episode may sit short of READY before it counts as stuck. */
        const val STUCK_TICKS = 6
        /** How long a retry may go without playing before the person is asked. */
        const val RETRY_STALL_MS = 60_000L
        /** How long any load may go without playing (or pausing) before the person is asked. */
        const val LOAD_STALL_MS = 45_000L
    }
}
