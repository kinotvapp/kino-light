package com.arkiv.player.ui.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.SubtitleView
import com.arkiv.player.ui.rememberGraph
import kotlinx.coroutines.delay

private const val TAG = "MagisExo"

/**
 * Plays a Magis stream using ExoPlayer.
 *
 * The URL already arrives proxied by [archiveCacheProxy] (http://127.0.0.1:...), which injects the
 * CDN's authentication headers transparently. ExoPlayer downloads it as plain HTTP.
 *
 * [DefaultMediaSourceFactory] auto-detects HLS, DASH or progressive (MP4/TS) based on the content
 * type. For the progress bar and controls it uses the same [PlayerMirror] VLC used to.
 *
 * Uses [TextureView] directly so [onTextureViewReady] exposes the surface and `captureFrame`
 * works the same way it did with VLC. The aspect ratio is kept in sync by listening to
 * [Player.Listener.onVideoSizeChanged]: in portrait the video stays centered in landscape format.
 *
 * The portal's external subtitles are passed as [subtitleConfigs] and ExoPlayer loads them
 * automatically; the overlaid [SubtitleView] renders them on screen. Detected audio and subtitle
 * tracks are reported via [onTracksChanged] so [TracksState] can expose them in the menu.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MagisExoPlayer(
    mediaUrl: String,
    mirror: PlayerMirror,
    startPositionMs: Long = 0L,
    subtitleConfigs: List<MediaItem.SubtitleConfiguration> = emptyList(),
    onPlayerReady: (Player?) -> Unit = {},
    onTextureViewReady: (TextureView?) -> Unit = {},
    onError: (String) -> Unit = {},
    onTracksChanged: ((Tracks) -> Unit)? = null,
    onFirstFrame: (Boolean) -> Unit = {},
    /**
     * The episode reached its end.
     *
     * Magis plays here and not on the service player, and the screen's own end-of-episode listener
     * deliberately stays quiet while an ExoPlayer is active -- its STATE_ENDED would belong to a
     * local player holding nothing. That left NOBODY watching for the end of a Magis episode, so
     * it simply stopped at the last frame and the next one had to be started by hand. The comment
     * excusing it said "the ExoPlayer handles its own end"; it never did.
     */
    onChapterEnd: () -> Unit = {},
    zoom: Float = 1f,
    isTv: Boolean = false,
) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val subtitleStyle by graph.subtitlePrefs.prefs.collectAsStateWithLifecycle()

    val exoPlayer = remember(mediaUrl, subtitleConfigs) {
        Log.i(TAG, "Creating ExoPlayer · url=${mediaUrl.take(80)} startMs=$startPositionMs subs=${subtitleConfigs.size}")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("okhttp/4.12.0")
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(mediaUrl))
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

        // Magis's CDN delivers at 70–230 KB/s and its files carry 8 badly interleaved audio
        // tracks: the video lives in one zone and the audio 13 MB away, so the player jumps
        // between the two and each jump costs between 1.6 s and 3.9 s of waiting. With the
        // factory buffer —50 s ceiling and 2.5 s to start— it runs dry every two or three
        // seconds and the picture stutters, so it's given ample margin ahead.
        //
        // But only as far as it fits on a phone. Tested at 300 s and a 96 MB ceiling and it was
        // worse than the original problem: at 236 KB/s bitrate that's ~70 MB retained, the heap
        // went from 107 MB to 142 MB, the GC looped, and the picture froze every 15 s like
        // clockwork. A 60 s ceiling is about 14 MB, which comfortably covers the slowest jump
        // measured. What accumulates BEFORE resuming after a cut is 4 s and not 8: with this CDN
        // those extra seconds cost dearly. Measured on the Fire Stick with the source at
        // 36 KB/s —a sixth of what the video asks for— a rebuffer cost 85 s of waiting, because
        // gathering 8 s of content at that rate is almost 2 MB. At 4 s the wait is cut in half and
        // there's still cushion for a normal hiccup.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .setTargetBufferBytes(24 * 1024 * 1024)
            // Sends duration and not size: with 8 audio tracks the byte ceiling is reached well
            // before the seconds of video needed to cover a jump.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build()
            .also { player ->
                player.setMediaItem(mediaItem)
                player.prepare()
                if (startPositionMs > 0L) player.seekTo(startPositionMs)
                player.playWhenReady = true
                Log.i(TAG, "ExoPlayer prepared · seekTo=$startPositionMs")
            }
    }

    var videoAspectRatio by remember(exoPlayer) { mutableFloatStateOf(0f) }
    // Read inside the layout listener below, which is built once (`remember`) and outlives every
    // recomposition: a plain `zoom` capture would freeze at whatever it was on that first build.
    val currentZoom = rememberUpdatedState(zoom)

    val textureView = remember(exoPlayer) {
        TextureView(context).apply {
            // No opacity, so whatever has no picture lets the background show through. On its own
            // it did NOT remove the green stripe —tested— but it's correct for a view that doesn't
            // fill its slot, and it costs nothing.
            isOpaque = false
            // Where it ends up placed. This uncovered that the view was shrinking halfway through
            // (1920x1080 on mount, 1920x800 once the video's ratio arrives), and stays here in case
            // some device does something odd with the size again.
            //
            // Also re-applies the aspect transform on any real size change (a rotation, chiefly):
            // `update`'s `fitAspect` call only re-runs on recomposition, and neither `videoAspectRatio`
            // nor `zoom` change just because Android relaid out the view at its new fillMaxSize()
            // bounds -- without this, the picture stayed squashed with the PREVIOUS orientation's
            // transform until something unrelated forced a recomposition.
            addOnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
                Log.i(TAG, "TextureView placed at [$l,$t]-[$r,$b] · ${r - l}x${b - t}")
                if (r - l != oldR - oldL || b - t != oldB - oldT) {
                    (v as TextureView).fitAspect(videoAspectRatio, currentZoom.value)
                }
            }
        }
    }
    val subtitleView = remember(exoPlayer) { SubtitleView(context) }
    LaunchedEffect(subtitleView, subtitleStyle, isTv) {
        subtitleView.applyArkivSubtitleStyle(subtitleStyle, isTv)
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
        Log.i(TAG, "DisposableEffect hooked · state=${exoPlayer.playbackState} isPlaying=${exoPlayer.isPlaying}")

        val listener = object : Player.Listener {

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val ratio = if (videoSize.height > 0)
                    videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                else 0f
                Log.i(TAG, "onVideoSizeChanged · ${videoSize.width}x${videoSize.height} sar=${videoSize.pixelWidthHeightRatio} → ratio=$ratio")
                if (ratio > 0f) videoAspectRatio = ratio
            }

            override fun onTracksChanged(tracks: Tracks) {
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val text  = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }

                Log.i(TAG, "onTracksChanged · video=${video.size} groups / audio=${audio.size} groups / subs=${text.size} groups")

                audio.forEachIndexed { gi, group ->
                    for (ti in 0 until group.length) {
                        val fmt = group.getTrackFormat(ti)
                        Log.i(TAG, "  audio[$gi][$ti] lang=${fmt.language} label=${fmt.label} codec=${fmt.sampleMimeType} ch=${fmt.channelCount} selected=${group.isTrackSelected(ti)}")
                    }
                }
                text.forEachIndexed { gi, group ->
                    for (ti in 0 until group.length) {
                        val fmt = group.getTrackFormat(ti)
                        Log.i(TAG, "  subs[$gi][$ti] lang=${fmt.language} label=${fmt.label} mime=${fmt.sampleMimeType} selected=${group.isTrackSelected(ti)}")
                    }
                }

                onTracksChanged?.invoke(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitleView.setCues(stackOverlappingCues(cueGroup.cues))
            }

            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE     -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY    -> "READY"
                    Player.STATE_ENDED    -> "ENDED"
                    else                  -> "?"
                }
                Log.i(TAG, "onPlaybackStateChanged → $name · isPlaying=${exoPlayer.isPlaying} pos=${exoPlayer.currentPosition}ms dur=${exoPlayer.duration}ms")
                mirror.updateBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_ENDED) {
                    Log.w(TAG, "episode ended at ${exoPlayer.currentPosition}ms of ${exoPlayer.duration}ms")
                    onChapterEnd()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                Log.i(TAG, "onIsPlayingChanged → playing=$playing · pos=${exoPlayer.currentPosition}ms playWhenReady=${exoPlayer.playWhenReady}")
                mirror.updatePlaying(playing)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.i(TAG, "onPlayWhenReadyChanged → playWhenReady=$playWhenReady reason=$reason · isPlaying=${exoPlayer.isPlaying}")
                mirror.updateWantsToPlay(playWhenReady)
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "onRenderedFirstFrame · pos=${exoPlayer.currentPosition}ms")
                onFirstFrame(true)
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = error.message ?: "Error de reproducción (${error.errorCode})"
                Log.e(TAG, "onPlayerError errorCode=${error.errorCode} msg=$msg", error)
                // Also to Sentry: VOD playback failures (codec init, source, decoder) used to vanish
                // into Logcat -- this is proactive signal on which content/devices can't play.
                com.arkiv.player.crash.Crash.report(error, "magis-playback-${androidx.media3.common.PlaybackException.getErrorCodeName(error.errorCode)}")
                onError(msg)
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            Log.i(TAG, "onDispose · pos=${exoPlayer.currentPosition}ms isPlaying=${exoPlayer.isPlaying}")
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

    // Position polling. Also watches for two pathologies the clock alone doesn't reveal:
    //  · the position advances while !isPlaying (transport bug);
    //  · the clock advances but the renderer doesn't produce a single frame — the picture stays
    //    frozen with the bar running. Happens when the network changes underneath: the proxy
    //    abandons its connections to the origin, the response feeding ExoPlayer cuts off mid-
    //    download, and ExoPlayer reads it as a legitimate end of stream. It stays in READY without
    //    requesting more data, the AudioTrack stops, and media3 falls back to its internal clock,
    //    which runs free even though not a single byte arrives.
    //
    //    Measured with the decoder's counters, not the clock: they're the only proof a frame
    //    actually reached the screen. The rescue is staggered because the two causes call for
    //    different remedies: first a seek (cheap, unsticks a stuck decoder), and if the counter is
    //    still pinned, prepare(), the only thing that rebuilds the source and reopens the HTTP
    //    connection — a seek reopens nothing when the player thinks the stream already ended.
    LaunchedEffect(exoPlayer) {
        var lastPos = -1L
        var lastFrames = -1L
        var frozenSinceMs = 0L
        var lastRescueMs = 0L
        var consecutiveRescues = 0

        while (true) {
            delay(500)
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            val playing = exoPlayer.isPlaying
            val wantPlay = exoPlayer.playWhenReady
            val state = exoPlayer.playbackState
            val frames = exoPlayer.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: -1L

            if (!playing && !wantPlay && pos != lastPos && lastPos >= 0) {
                Log.w(TAG, "POSITION ADVANCES WHILE PAUSED · pos=$pos lastPos=$lastPos state=$state")
            }

            // The clock is genuinely running (not a seek, not a pause) but no frame came in.
            val clockAdvanced = lastPos >= 0 && pos > lastPos
            val noFrames = lastFrames >= 0 && frames == lastFrames
            val now = SystemClock.elapsedRealtime()

            if (playing && state == Player.STATE_READY && clockAdvanced && noFrames && frames >= 0) {
                if (frozenSinceMs == 0L) {
                    frozenSinceMs = now
                    Log.w(TAG, "VIDEO WITHOUT FRAMES · starts · pos=${pos}ms frames=$frames")
                }
                val frozenMs = now - frozenSinceMs
                // 5 s margin: below that it's indistinguishable from the CDN's normal hiccups,
                // which can last up to 4 s and recover on their own.
                //
                // The rescue attacks the three points where the freeze was measured, from
                // cheapest to costliest, because each one cures a case the previous one doesn't:
                //  1. seekTo — unsticks a stuck decoder. Sometimes it's enough (a recovery was
                //     measured at 190 ms), but in the hard freeze the frame counter stays pinned
                //     at the same number after a perfectly successful seek.
                //  2. prepare() — rebuilds the source and reopens the HTTP connection. Cures it
                //     when the player read the proxy's cut as end of stream and stopped
                //     requesting data.
                //  3. re-hooking the TextureView — the surface stopped draining and the decoder
                //     ran out of output buffers. A run was measured where not even prepare()
                //     moved the counter: neither the source nor the decoder was missing there,
                //     what was missing was somewhere to paint, and only detaching and re-setting
                //     the surface fixed it.
                if (frozenMs >= 5_000 && now - lastRescueMs >= 8_000) {
                    lastRescueMs = now
                    frozenSinceMs = 0L
                    consecutiveRescues++
                    if (consecutiveRescues <= 1) {
                        Log.w(TAG, "VIDEO FROZEN ${frozenMs}ms · rescue 1: prepare() at $pos")
                        exoPlayer.seekTo(pos)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    } else {
                        Log.w(TAG, "VIDEO FROZEN ${frozenMs}ms · rescue $consecutiveRescues: re-hooking the surface at $pos")
                        exoPlayer.clearVideoTextureView(textureView)
                        exoPlayer.setVideoTextureView(textureView)
                        exoPlayer.seekTo(pos)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }
                }
            } else {
                if (frozenSinceMs != 0L) {
                    Log.i(TAG, "VIDEO WITHOUT FRAMES · recovered after ${now - frozenSinceMs}ms · frames=$frames")
                }
                frozenSinceMs = 0L
                // Only counts as recovered if new frames genuinely came in, not from a BUFFERING
                // flash between two frozen stretches.
                if (frames > lastFrames && lastFrames >= 0) consecutiveRescues = 0
            }

            lastPos = pos
            lastFrames = frames

            mirror.readClock(
                positionMs = pos,
                durationMs = if (dur > 0) dur else 0L,
            )
        }
    }

    // The ratio is applied on the containing Box, NOT on the AndroidView: if the AndroidView's
    // Modifier changed (fillMaxSize → aspectRatio), Compose may briefly reattach the TextureView,
    // destroying its SurfaceTexture and leaving the video black with audio.
    // With a Box wrapper the TextureView always has fillMaxSize() → a stable surface.
    // The TextureView NEVER changes size: it always fills the whole screen and the ratio is
    // achieved by transforming its content (see [fitAspect]).
    //
    // The surrounding Box used to be given the aspect, and that shrank the view halfway through:
    // it was placed at 1920x1080 —the ratio isn't known until the decoder starts— and once
    // onVideoSizeChanged arrived it switched to 1920x800. But its SurfaceTexture had been created
    // at 1080, and the leftover 280 px stayed there with an unused buffer: a GREEN stripe under
    // the video in every widescreen movie. Measured on the Fire Stick with a 2.4:1, and it never
    // showed on 16:9 precisely because there the view already filled the screen and never shrank.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { textureView },
            update = { it.fitAspect(videoAspectRatio, zoom) },
        )

        // BLACK BARS ON TOP, covering what's left over from the video.
        //
        // It's a patch and worth knowing that: in a widescreen movie the bottom half of the gap
        // came out GREEN —the surface's unused buffer— and the cause was never found. These were
        // ruled out, each measured on the Fire Stick: the zoom's composition layer, the
        // TextureView's opacity, the view changing size halfway through, and the content
        // transform. With all of those the video landed EXACTLY where it should (measured:
        // y=138..941 for a 2.4:1 on 1080) and the stripe stayed the same. The strangest part is
        // that the TOP bar always came out black and only the bottom one green, with the same
        // surface.
        //
        // So black gets painted on top of both bars. It doesn't fix the buffer, but a widescreen
        // movie's gap has to be black and this way it is.
        // The bars go wherever they belong: top and bottom if the video is WIDER than the screen
        // (a widescreen movie on TV), on the sides if it's NARROWER (a 4:3 on TV, or anything on a
        // phone held upright). Only one of the two branches can happen at a time, and with video
        // in exactly the same format neither one is painted.
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

        // Overlaid SubtitleView: renders VTT/SRT cues loaded via SubtitleConfiguration.
        AndroidView(modifier = Modifier.matchParentSize(), factory = { subtitleView })
    }
}

/**
 * Fits the video into the view without distorting it, moving the CONTENT and not the view.
 *
 * ExoPlayer stretches the video to fill the TextureView, so a 2.4:1 movie on a 16:9 screen comes
 * out squashed. Fixed with the surface's matrix: how much overflows on the axis that doesn't fit
 * is calculated and shrunk there, leaving the rest black like any letterbox. Same as what media3's
 * PlayerView does with a TextureView.
 *
 * [zoom] multiplies at the end, so the zoom gesture keeps working on top of the result.
 *
 * `internal` (not `private`): [LiveExoPlayer] reuses it as-is for live's same letterbox.
 */
internal fun TextureView.fitAspect(videoAspect: Float, zoom: Float) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (videoAspect <= 0f || w <= 0f || h <= 0f) return

    val viewAspect = w / h
    // Only the axis that overflows gets SHRUNK: enlarging the other would crop the picture.
    val scaleX = if (videoAspect > viewAspect) 1f else videoAspect / viewAspect
    val scaleY = if (videoAspect > viewAspect) viewAspect / videoAspect else 1f

    setTransform(
        android.graphics.Matrix().apply {
            setScale(scaleX * zoom, scaleY * zoom, w / 2f, h / 2f)
        },
    )
}

/**
 * A subtitle's type from its path. VTT by default: what magis's portal serves; the .srt case is
 * there in case some source ever names one that way with that extension.
 */
private fun subtitleMimeType(path: String): String = when {
    path.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    else -> MimeTypes.TEXT_VTT
}

/** Converts the portal's subtitle list to ExoPlayer's SubtitleConfiguration. */
internal fun List<ResolvedSub>.toExoSubtitleConfigs(): List<MediaItem.SubtitleConfiguration> =
    map { sub ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
            .setMimeType(subtitleMimeType(sub.url))
            .setLanguage(sub.lang)
            .build()
    }
