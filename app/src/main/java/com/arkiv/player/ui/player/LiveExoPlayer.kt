package com.arkiv.player.ui.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.arkiv.player.crash.Crash
import com.arkiv.player.crash.LiveDecoderSwitched
import com.arkiv.player.playback.DecoderWatchdog
import com.arkiv.player.playback.InPlaceRecoveryBudget
import com.arkiv.player.playback.LiveDecoderMemory
import com.arkiv.player.playback.LiveErrorKind
import com.arkiv.player.playback.LiveLog
import com.arkiv.player.playback.LiveQualityMonitor
import com.arkiv.player.playback.liveRenderers
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.arkiv.player.AppGraph

private const val TAG = "LiveExo"

/** What kind of live error this is, from the error code and the exceptions behind it. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun errorKind(error: PlaybackException): LiveErrorKind {
    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return LiveErrorKind.BEHIND_LIVE_WINDOW
    var cause: Throwable? = error.cause
    var depth = 0
    while (cause != null && depth++ < 8) {
        when (cause) {
            is BehindLiveWindowException -> return LiveErrorKind.BEHIND_LIVE_WINDOW
            is HlsPlaylistTracker.PlaylistResetException -> return LiveErrorKind.PLAYLIST_RESET
            is HlsPlaylistTracker.PlaylistStuckException -> return LiveErrorKind.PLAYLIST_STUCK
        }
        cause = cause.cause
    }
    return LiveErrorKind.OTHER
}

/**
 * Plays the Magis live channel using ExoPlayer (Task 1, light-magis pruning -- the VLC gate).
 * Replaced VLC as the live player; see the KDoc of
 * [com.arkiv.player.ui.player.PlayerViewModel.openCurrentChannel] and the task brief for why.
 *
 * [mediaUrl] already arrives served by [com.arkiv.player.playback.LiveHlsProxy] on `127.0.0.1`,
 * with the CDN's `Content-Auth`/`Content-License` injected by the proxy on every request --
 * ExoPlayer downloads it as plain HTTP, the same way [StreamExoPlayer] does with the VOD proxy. No
 * need to pass it its own headers: the proxy exists precisely because VLC couldn't send those
 * headers, and ExoPlayer doesn't need them either since it never sees them -- the proxy sets them.
 *
 * Unlike [StreamExoPlayer]:
 * - No `startPositionMs`/resume: a live feed has no "where you were".
 * - No subtitles: the portal doesn't send any for the live feed.
 * - No custom [LoadControl]: the problem that motivated Magis's (a single giant TS with 8 badly
 *   interleaved audio tracks) doesn't exist here -- the proxy serves a properly segmented HLS, so
 *   ExoPlayer's defaults (built for live) are enough.
 * - `onError` must not show an overlay: [PlayerViewModel.onLiveExoError] sends it to
 *   `reopenLiveAfterCut()`, which retries on its own -- a live channel recovers almost always in
 *   a few seconds (see its KDoc), and showing an error on the first hiccup would over-alarm.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun LiveExoPlayer(
    mediaUrl: String,
    /**
     * Forces the ExoPlayer to be recreated even when [mediaUrl] doesn't change.
     *
     * [com.arkiv.player.playback.LiveHlsProxy.urlFor] ALWAYS returns the same URL
     * (`http://127.0.0.1:<port>/live.m3u8?t=<token>`) for the whole life of the proxy: the active
     * channel is decided by the proxy behind closed doors (its `session` field), not by the URL.
     * Switching to another channel, or reopening the same one after a cut, does NOT change
     * `mediaUrl` -- it only changes which playlist the proxy answers on that same route. Without
     * this key, `remember` would see the same URL and never recreate/re-prepare the player: the
     * screen would stay frozen on the old channel. Passing `liveItem.episodeId` (which does change
     * per channel) combined with `generacionVivo` (which goes up on every reopen, including of the
     * SAME channel after a cut -- see its KDoc in PlayerViewModel) covers both cases. It's the
     * ExoPlayer equivalent of what `controller.setMediaItems(...)+prepare()` used to achieve on the
     * old VLC player, forced by that same key.
     */
    key: Any,
    /** The channel being played, for [LiveDecoderMemory]: a channel that failed in hardware once opens in software. */
    channelCode: String,
    mirror: PlayerMirror,
    onPlayerReady: (Player?) -> Unit = {},
    onTextureViewReady: (TextureView?) -> Unit = {},
    onError: (String) -> Unit = {},
    onFirstFrame: (Boolean) -> Unit = {},
    zoom: Float = 1f,
) {
    val context = LocalContext.current

    // True once this channel's hardware decoder failed to paint (here or on an earlier visit): the player is then
    // recreated with a software decoder in front. Changing it changes the `remember` key below, which is what
    // swaps the player.
    var software by remember(channelCode) { mutableStateOf(LiveDecoderMemory.prefersSoftware(context, channelCode)) }

    // Which kind of Magis session this is (account / own / shared seed), read off the main thread: it goes on every
    // live report, to tell whether the conflicts (409, "logged in elsewhere") sit on the shared seeds.
    LaunchedEffect(channelCode) {
        LiveLog.sessionKind = withContext(Dispatchers.IO) { AppGraph.from(context).magisSessionKind() }
    }

    val exoPlayer = remember(key, software) {
        Log.i(TAG, "Creating ExoPlayer · url=${mediaUrl.take(80)} key=$key software=$software")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("okhttp/4.12.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(mediaUrl))
            .build()

        ExoPlayer.Builder(context, liveRenderers(context, preferSoftware = software))
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .also { player ->
                player.setMediaItem(mediaItem)
                player.prepare()
                player.playWhenReady = true
                Log.i(TAG, "ExoPlayer prepared")
            }
    }

    // What the viewer perceives as interference (freezes, dropped frames, audio glitches, decoder), written to the
    // `ArkivLive` log and, when a session turns out bad, sent to GlitchTip once. See LiveQualityMonitor.
    val quality = remember(exoPlayer) { LiveQualityMonitor(exoPlayer) }
    val paintedFirstFrame = remember(exoPlayer) { AtomicBoolean(false) }
    // Per channel, not per player: a feed that keeps falling behind must run out of tries even though every try
    // is a new player.
    val inPlaceBudget = remember(channelCode) { InPlaceRecoveryBudget() }

    // Hoisted out of the watchdog LaunchedEffect below so `onPlayerError` can call it too (a decoder that
    // REJECTS the format outright throws before the watchdog's frozen-picture check would ever fire).
    // Hardware decoder that takes the stream and never paints (or freezes, or refuses the format): swap it
    // for a software one, once. It reopens the channel through the `software` state, so the next visit
    // starts in software too.
    fun rescueInSoftware(reason: String, waitedMs: Long) {
        if (software) return
        val format = exoPlayer.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            ?.takeIf { it.length > 0 }?.getTrackFormat(0)
        LiveLog.w("decoder RESCUE: $reason after ${waitedMs}ms on ${quality.videoDecoder.ifEmpty { "?" }} -> reopening with a software decoder")
        LiveDecoderMemory.remember(context, channelCode)
        Crash.report(
            LiveDecoderSwitched("live decoder switched to software"),
            "live-decoder-switch",
            extras = mapOf(
                "reason" to reason,
                "waited_ms" to waitedMs.toString(),
                "channel" to channelCode,
                "session_kind" to LiveLog.sessionKind,
                "video_decoder" to quality.videoDecoder,
                "video_codec" to (format?.sampleMimeType ?: ""),
                "video_size" to (format?.let { "${it.width}x${it.height}" } ?: ""),
                "model" to android.os.Build.MODEL,
                "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
            ),
        )
        software = true
    }

    var videoAspectRatio by remember(exoPlayer) { mutableFloatStateOf(0f) }
    // Read inside the layout listener below, which is built once (`remember`) and outlives every
    // recomposition: a plain `zoom` capture would freeze at whatever it was on that first build.
    val currentZoom = rememberUpdatedState(zoom)

    val textureView = remember(exoPlayer) {
        TextureView(context).apply {
            isOpaque = false
            // Re-applies the aspect transform on any real size change (a rotation, chiefly):
            // `update`'s `fitAspect` call only re-runs on recomposition, and neither
            // `videoAspectRatio` nor `zoom` change just because Android relaid out the view at its
            // new fillMaxSize() bounds -- without this, the picture stayed squashed with the
            // PREVIOUS orientation's transform. Same fix as StreamExoPlayer's TextureView.
            addOnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (r - l != oldR - oldL || b - t != oldB - oldT) {
                    (v as TextureView).fitAspect(videoAspectRatio, currentZoom.value)
                }
            }
        }
    }

    DisposableEffect(exoPlayer) {
        exoPlayer.setVideoTextureView(textureView)
        onPlayerReady(exoPlayer)
        onTextureViewReady(textureView)
        mirror.syncTransport(
            buffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
            playing = exoPlayer.isPlaying,
            wantsToPlay = exoPlayer.playWhenReady,
        )
        Log.i(TAG, "DisposableEffect hooked · state=${exoPlayer.playbackState}")

        // Telemetry rate-limit: live HLS can loop the SAME error (a CDN playlist reset re-fires
        // onPlayerError on every retry), which flooded GlitchTip with 1000+ identical "Source error"
        // reports from one bad box. Report only the FIRST error of this player session; the on-screen
        // error still shows every time. Reset per session (this effect re-runs per exoPlayer).
        var errorReported = false

        val listener = object : Player.Listener {

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val ratio = if (videoSize.height > 0)
                    videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                else 0f
                if (ratio > 0f) videoAspectRatio = ratio
            }

            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?"
                }
                Log.i(TAG, "onPlaybackStateChanged → $name · isPlaying=${exoPlayer.isPlaying} pos=${exoPlayer.currentPosition}ms")
                mirror.updateBuffering(state == Player.STATE_BUFFERING)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                mirror.updatePlaying(playing)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                mirror.updateWantsToPlay(playWhenReady)
            }

            override fun onRenderedFirstFrame() {
                paintedFirstFrame.set(true)
                Log.i(TAG, "onRenderedFirstFrame · pos=${exoPlayer.currentPosition}ms")
                // The number a person waits through when zapping: from choosing the channel to the first picture.
                LiveLog.i("first frame: ${LiveLog.sinceZapMs()}ms after the channel was chosen")
                onFirstFrame(true)
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = error.message ?: "Error de reproducción (${error.errorCode})"
                Log.e(TAG, "onPlayerError errorCode=${error.errorCode} msg=$msg", error)
                // The hardware decoder rejected the stream's format outright (seen on MediaTek/Hisilicon/other
                // chips against certain live channels since the Media3 1.11 upgrade): a playlist refresh or a
                // plain reopen just hits the same wall again. Same rescue as the frozen-picture watchdog below,
                // triggered here instead because the decoder throws before that watchdog would ever see it.
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED &&
                    msg.contains("NO_EXCEEDS_CAPABILITIES")
                ) {
                    rescueInSoftware("decoder rejected the format", 0L)
                    return
                }
                // Fell behind the window, or the playlist reset / froze: a fresh look at the playlist at the live
                // edge fixes it, no need to re-resolve the whole channel. Only a few tries a minute; then the
                // full reopen (and its warning) takes over.
                val kind = errorKind(error)
                if (kind.recoverableInPlace && inPlaceBudget.tryConsume(SystemClock.elapsedRealtime())) {
                    LiveLog.w("in-place recovery: $kind ($msg) -> seek to the live edge and prepare again")
                    // Once per recovery, not per retry: a stuck playlist keeps hitting the same error while it's
                    // stuck, and the budget above already bounds how many of these a single channel can fire.
                    com.arkiv.player.crash.Crash.report(
                        com.arkiv.player.crash.LiveInPlaceRecovery("live in-place recovery"),
                        "live-in-place-recovery",
                        extras = mapOf(
                            "channel" to channelCode,
                            "session_kind" to LiveLog.sessionKind,
                            "error_kind" to kind.name,
                        ),
                    )
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    return
                }
                if (!errorReported) {
                    errorReported = true
                    com.arkiv.player.crash.Crash.report(
                        error,
                        "live-playback-${PlaybackException.getErrorCodeName(error.errorCode)}",
                        extras = mapOf(
                            "channel" to channelCode,
                            "session_kind" to LiveLog.sessionKind,
                            "error_kind" to errorKind(error).name,
                            "video_decoder" to quality.videoDecoder,
                            "software_forced" to software.toString(),
                        ),
                    )
                }
                onError(msg)
            }
        }
        exoPlayer.addListener(listener)
        exoPlayer.addAnalyticsListener(quality)

        onDispose {
            Log.i(TAG, "onDispose · pos=${exoPlayer.currentPosition}ms isPlaying=${exoPlayer.isPlaying}")
            // The session summary (and the report, if it was a bad one) while the player is still alive to ask.
            quality.finish()
            exoPlayer.removeAnalyticsListener(quality)
            exoPlayer.removeListener(listener)
            exoPlayer.clearVideoTextureView(textureView)
            exoPlayer.release()
            mirror.resetClock()
            mirror.syncTransport(buffering = false, playing = false, wantsToPlay = false)
            onPlayerReady(null)
            onTextureViewReady(null)
            onFirstFrame(false)
        }
    }

    // Position polling + the same staggered rescue as StreamExoPlayer (see its long KDoc): the
    // local proxy can cut a connection mid-segment (network change, CDN down) and leave ExoPlayer
    // with the clock running free with not a single new frame. Here the rescue is the ONLY silent
    // recovery mechanism between minor hiccups and `onError`'s explicit warning →
    // `reopenLiveAfterCut()` (which does re-resolve the whole session against the gateway) --
    // that's why it's worth keeping it live too, even though the original reason (Magis's
    // MPEG-TS) doesn't apply here.
    LaunchedEffect(exoPlayer) {
        var lastPos = -1L
        var lastFrames = -1L
        var frozenSinceMs = 0L
        var lastRescueMs = 0L
        var lastQualityLogMs = SystemClock.elapsedRealtime()
        var videoSeenAtMs = 0L

        while (true) {
            delay(500)
            // The 10 s health line: buffer level, distance from the live edge, freezes, dropped frames.
            if (SystemClock.elapsedRealtime() - lastQualityLogMs >= 10_000L) {
                lastQualityLogMs = SystemClock.elapsedRealtime()
                quality.logSummary()
            }
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            val playing = exoPlayer.isPlaying
            val state = exoPlayer.playbackState
            val frames = exoPlayer.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: -1L

            val clockAdvanced = lastPos >= 0 && pos > lastPos
            val noFrames = lastFrames >= 0 && frames == lastFrames
            val now = SystemClock.elapsedRealtime()

            // Sound but no picture: the video track is known and ten seconds later not one frame has been painted.
            val hasVideo = exoPlayer.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
            if (hasVideo && videoSeenAtMs == 0L) videoSeenAtMs = now
            val waitedForPicture = if (videoSeenAtMs == 0L) -1L else now - videoSeenAtMs
            if (DecoderWatchdog.shouldReloadInSoftware(
                    waitingMs = waitedForPicture,
                    renderedFirstFrame = paintedFirstFrame.get(),
                    videoTracks = if (hasVideo) 1 else 0,
                    wantsToPlay = exoPlayer.playWhenReady,
                    hasSurface = true,
                    hasError = exoPlayer.playerError != null,
                    alreadySoftware = software,
                )
            ) {
                rescueInSoftware("no picture", waitedForPicture)
                continue
            }

            if (playing && state == Player.STATE_READY && clockAdvanced && noFrames && frames >= 0) {
                if (frozenSinceMs == 0L) {
                    frozenSinceMs = now
                    Log.w(TAG, "VIDEO WITHOUT FRAMES · starts · pos=${pos}ms frames=$frames")
                }
                val frozenMs = now - frozenSinceMs
                if (frozenMs >= 5_000 && now - lastRescueMs >= 8_000) {
                    lastRescueMs = now
                    frozenSinceMs = 0L
                    Log.w(TAG, "VIDEO FROZEN ${frozenMs}ms · prepare() at $pos")
                    if (!software) {
                        rescueInSoftware("frozen picture", frozenMs)
                        continue
                    }
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            } else {
                if (frozenSinceMs != 0L) {
                    Log.i(TAG, "VIDEO WITHOUT FRAMES · recovered after ${now - frozenSinceMs}ms · frames=$frames")
                }
                frozenSinceMs = 0L
            }

            lastPos = pos
            lastFrames = frames

            mirror.readClock(
                positionMs = pos,
                durationMs = if (dur > 0) dur else 0L,
            )
        }
    }

    // Same letterbox scheme as StreamExoPlayer: the ratio is applied by transforming the
    // TextureView's CONTENT (which always fills the whole screen), not the view's size -- see the
    // KDoc of [fitAspect] for why (avoids the Fire Stick's green stripe).
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // `key(exoPlayer)`: without this, on zapping `textureView` changes (it's
        // `remember(exoPlayer)` above) but `AndroidView` does NOT call `factory` again -- Compose
        // only invokes it once per position in the tree, so it keeps showing the old view (with
        // the previous channel's last frame) forever, while the audio DOES follow the new
        // ExoPlayer because it doesn't depend on any view. Wrapping in `key` forces Compose to
        // treat it as a new node on every zap, and there it does call `factory` again with the
        // freshly created `textureView`.
        key(exoPlayer) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { textureView },
                update = { it.fitAspect(videoAspectRatio, zoom) },
            )
        }

        val boxHeight = maxHeight
        val boxWidth = maxWidth
        if (videoAspectRatio > 0f && boxHeight > 0.dp && boxWidth > 0.dp) {
            val screenAspect = boxWidth / boxHeight
            if (videoAspectRatio > screenAspect) {
                val bar = (boxHeight - boxWidth / videoAspectRatio) / 2
                if (bar > 0.dp) {
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(bar).background(Color.Black))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bar).background(Color.Black))
                }
            } else if (videoAspectRatio < screenAspect) {
                val bar = (boxWidth - boxHeight * videoAspectRatio) / 2
                if (bar > 0.dp) {
                    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(bar).background(Color.Black))
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(bar).background(Color.Black))
                }
            }
        }
    }
}
