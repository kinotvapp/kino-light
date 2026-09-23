package com.arkiv.player.cast

import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.arkiv.player.data.ArkivRepository
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
            tracks.groups.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    android.util.Log.i(
                        TAG,
                        "track type=${g.type} codec=${f.codecs} mime=${f.sampleMimeType} " +
                            "language=${f.language} supported=${g.isTrackSupported(i)} selected=${g.isTrackSelected(i)}",
                    )
                }
            }
        }
    }

    init {
        player.addListener(diagnostics)
        player.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                _casting.value = true
                val device = runCatching {
                    castContext.sessionManager.currentCastSession?.castDevice?.friendlyName
                }.getOrNull()
                android.util.Log.i(TAG, "session available · receiver=${device ?: "?"} · pending=${pending?.episodeId}")
                pending?.let { scope.launch { load(it) } }
                    ?: android.util.Log.w(TAG, "session available but nothing pending: nothing will be loaded")
            }

            override fun onCastSessionUnavailable() {
                android.util.Log.i(TAG, "session gone")
                _casting.value = false
            }
        })
        // The one case the listener does NOT cover: starting the app with a session already alive
        // (it was killed and reopened while casting). It happened before we existed, so it's
        // adopted by hand.
        if (runCatching { castContext.sessionManager.currentCastSession?.isConnected }.getOrNull() == true) {
            _casting.value = true
        }
        startProgressLoop()
    }

    /** Requests casting this. Loads right away if there's already a session; otherwise stays pending until there is one. */
    fun setMedia(request: CastRequest) {
        generation++
        pending = request
        // Without this line "nothing was ever asked of the receiver" and "it was asked and refused"
        // look identical from a log: both end up as a session with an idle player.
        android.util.Log.i(
            TAG,
            "cast requested · ep=${request.episodeId} · mime=${request.mimeType} · from=${request.startPositionMs}ms " +
                "· session=${_casting.value} · ${request.uri}",
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
        pending = null
        // The state turns off here instead of waiting for the listener: if onCastSessionUnavailable
        // somehow didn't arrive, the phone's bar would be left hanging, showing a dead cast.
        _casting.value = false
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
        android.util.Log.i(
            TAG,
            "loading on the receiver · mime=${r.mimeType} · from=${r.startPositionMs}ms · ${r.uri}",
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
     * Persists progress while casting, EVEN WITH the player closed. Without this, watching a whole
     * episode from the home screen would only save the position up to the moment the screen was
     * left: the same silent loss the rest of this feature exists to avoid.
     */
    private fun startProgressLoop() {
        scope.launch {
            while (true) {
                delay(PROGRESS_MS)
                if (!_casting.value) continue
                val request = pending
                if (request == null) {
                    // A session with nothing pending is one of the two ways a cast "does nothing",
                    // and the one that used to leave no trace at all: the receiver sits on its logo
                    // because it was never handed any media, not because it refused ours.
                    android.util.Log.w(TAG, "casting with NOTHING pending: the receiver was never given media")
                    continue
                }
                val epId = request.episodeId
                // mediaId, position and duration are read together in the same main-thread tick:
                // setMedia() updates `pending` synchronously but the receiver takes (network) time
                // to load the new item, so without this check the old episode's position/duration
                // could get saved under the new one's id.
                val (mediaId, pos, dur) = withContext(Dispatchers.Main) {
                    Triple(player.currentMediaItem?.mediaId, player.currentPosition, player.duration)
                }
                if (mediaId != epId) continue
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
    }
}
