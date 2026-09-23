package com.arkiv.player.ui.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private const val TAG = "DituExo"

/**
 * Does this error get fixed by re-preparing the same stream?
 *
 * Same criterion the full app's Caracol player uses (`main` branch, commit 7caf2abc): network,
 * container, and `BEHIND_LIVE_WINDOW` errors. DRM and decoder ones are left out because against
 * the same license and the same codec they'd give the same result.
 */
private fun isRecoverable(error: PlaybackException): Boolean =
    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
        error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
        error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

/**
 * The Caracol player: MPEG-DASH with Widevine.
 *
 * ExoPlayer, same as [MagisExoPlayer] and [LiveExoPlayer], feeding the same [PlayerMirror] they
 * do. libVLC never negotiated Widevine licenses, so Caracol was always going to need ExoPlayer even
 * before the rest of the app dropped VLC.
 *
 * The `DrmSessionManager` is built by hand, like `main`'s Caracol player, to be able to turn off
 * the DRM session's keepalive (see the comment next to `setSessionKeepaliveMs`): media3's
 * `DefaultDrmSessionManagerProvider` has no way to change that.
 *
 * [drmLicenseHeaders] isn't optional in practice: it carries the `playback_token` cookie that
 * `CONTENT/VIDEOURL` returned, which is what authorizes the license (see `DituResolve` and
 * `DituClient`).
 *
 * There's no local proxy in the middle, unlike Magis: the headers Caracol requires are set by this
 * file's own `DefaultHttpDataSource`, and the SAME one is used for the manifest, the segments, and
 * the license request. It carries the `User-Agent` and `restful: yes` from `DituClient.HEADERS`
 * and, on top of that, [drmLicenseHeaders]: `main`'s player also sends the cookie to the manifest
 * and the segments, noting that without it the CDN returns HTML. It's copied here without having
 * measured it yet on this branch.
 *
 * The video goes on the `SurfaceView` that [PlayerView] uses by default, not on a `TextureView`
 * like [MagisExoPlayer]. On `main` it was measured that a Widevine-protected buffer can't be
 * painted on a `TextureView` (hwui aborts the process). The price is that Caracol loses frame
 * thumbnails: the screen passes `null` to `captureFrame`, and `FrameCapturer.capturar` returns
 * `false` with a null `TextureView`.
 *
 * Ads aren't filtered. On a recoverable error (see [isRecoverable]) the stream is re-prepared
 * while [requestReprepare] allows it; otherwise, the error's `errorCode` goes to [onError], and
 * `PlayerScreen` asks the ViewModel for a new URL. The caps for the two tiers don't live here but
 * in `DituState`, which only resets them after stable playback: [onPosition] passes it every
 * clock reading.
 *
 * Starts with the first frame, not before. It's prepared paused and
 * [StartOnFirstFrame] decides when to give it play: when the first frame is painted, or
 * after [MAX_FIRST_FRAME_WAIT_MS] without it, so it doesn't end up silent and
 * frozen. It used to start right away, and the audio could begin before the picture. Whether
 * ExoPlayer paints the first frame while paused hasn't been verified on a device on this branch:
 * if it didn't, what's left is that safety exit.
 *
 * [autoStart] set to `false` builds it prepared and paused, without starting on its own: that's
 * the case of reloading something that was paused. [onError] hands over, along with the code,
 * whether this player wanted to play ([StartOnFirstFrame.wantedToPlay]), which is
 * what the reload inherits.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun DituExoPlayer(
    mediaUrl: String,
    drmLicenseUrl: String,
    drmLicenseHeaders: Map<String, String>,
    mirror: PlayerMirror,
    /**
     * The chapter is downloaded to the device: the segments come from the cache and not the CDN.
     *
     * `null` = streaming, as usual. The LICENSE is requested over the network in both cases:
     * Caracol doesn't grant persistent licenses, so "downloaded" here means the gigabytes don't
     * travel again, not that it can be watched in airplane mode.
     */
    localDownload: com.arkiv.player.data.caracol.CaracolDownload? = null,
    store: com.arkiv.player.data.caracol.CaracolStore? = null,
    startPositionMs: Long = 0L,
    autoStart: Boolean = true,
    onPlayerReady: (Player?) -> Unit = {},
    onError: (code: Int, wantedToPlay: Boolean) -> Unit = { _, _ -> },
    requestReprepare: () -> Boolean,
    onPosition: (positionMs: Long, playing: Boolean) -> Unit = { _, _ -> },
    onTracksChanged: ((Tracks) -> Unit)? = null,
    onFirstFrame: (Boolean) -> Unit = {},
    zoom: Float = 1f,
) {
    val context = LocalContext.current

    // The license headers go into the key: two resolutions of the same episode carry the same URL
    // but a new `playback_token`, and the old player would keep requesting with the expired one.
    val exoPlayer = remember(mediaUrl, drmLicenseUrl, drmLicenseHeaders, localDownload) {
        Log.i(
            TAG,
            "Creating ExoPlayer DASH · url=${(localDownload?.mpd ?: mediaUrl).take(80)} " +
                "license=${drmLicenseUrl.take(60)} headers=${drmLicenseHeaders.keys} " +
                "startMs=$startPositionMs fromDisk=${localDownload != null} quality=${localDownload?.height}p",
        )

        val httpFactory = DefaultHttpDataSource.Factory()
            // Same values as `DituClient.HEADERS`: without them the CDN answers 403.
            .setUserAgent("okhttp/4.12.0")
            .setDefaultRequestProperties(mapOf("restful" to "yes") + drmLicenseHeaders)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)

        // Where the BYTES come from. Downloaded: the cache, hitting the network only for what's
        // missing (a chapter downloaded halfway is still watchable). Not downloaded: the CDN, as
        // usual. The license does NOT go through here -- it goes through `httpFactory`, below,
        // because it always has to reach the network.
        val fromDisk = localDownload != null && store != null
        val dataSourceFactory = if (fromDisk) store!!.factoryForPlayback(drmLicenseHeaders) else httpFactory

        // The saved URL WINS over the freshly resolved one. A cache is indexed by the URI it was
        // written with: opening with another -- even pointing at the same video -- misses every
        // byte and silently falls back to the network, exactly what the download exists to avoid.
        //
        // And the tracks are declared for the same reason: the manifest advertises EVERY quality
        // whether or not it's on disk, so without this filter the selector picks by bandwidth and
        // requests one nobody downloaded. Measured on 2026-09-13: it requested `init-f4-v1-x3` with
        // f1 on disk.
        val item = MediaItem.Builder()
            .setUri(Uri.parse(localDownload?.mpd ?: mediaUrl))
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .apply {
                localDownload?.let { d ->
                    setStreamKeys(d.keys.map { StreamKey(it.period, it.group, it.track) })
                }
            }
            .build()

        // The license is requested with the SAME `httpFactory`: without it, the callback would go
        // out through its own `DefaultHttpDataSource`, without the `User-Agent` or the
        // `restful: yes` above.
        val license = HttpMediaDrmCallback(drmLicenseUrl, httpFactory).also { cb ->
            drmLicenseHeaders.forEach { (k, v) -> cb.setKeyRequestProperty(k, v) }
        }
        val drmManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            // Ported from `main`'s Caracol player, which measured it on the Fire Stick on
            // 2026-08-22: that device has ONE single secure video path, and with the previous
            // channel's DRM session still retained, the new channel's secure decoder took 14 s to
            // get a surface (a black screen the whole time). By default media3 retains the session
            // 5 min after its last use; C.TIME_UNSET turns that keepalive off and it's released as
            // soon as it's unused. It matters here because every live channel and every reload from
            // an expired token arms a new player (`key(dPlay)` in `PlayerScreen`). Not tested on a
            // device on this branch.
            .setSessionKeepaliveMs(C.TIME_UNSET)
            .build(license)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DashMediaSource.Factory(dataSourceFactory).setDrmSessionManagerProvider { drmManager },
            )
            .build()
            .also { player ->
                player.setMediaItem(item)
                player.prepare()
                if (startPositionMs > 0L) player.seekTo(startPositionMs)
                // Paused: [StartOnFirstFrame] gives it play, with the first frame.
                player.playWhenReady = false
            }
    }

    // One per player: a reload (`key(dPlay)` in `PlayerScreen`) arms another and waits again,
    // unless what failed was paused ([autoStart]).
    val arranque = remember(exoPlayer) {
        StartOnFirstFrame(autoStart = autoStart).also { it.start(SystemClock.elapsedRealtime()) }
    }

    // If the app goes to the background while waiting for the first frame, the wait is suspended:
    // the clock's safety exit below doesn't check whether the app is in view, and could give it
    // play in the background. On return it resumes with its deadline counted again, so it never
    // ends up waiting with no deadline: it starts with the frame or with the safety exit. Same for
    // a live channel: `PlayerScreen` stops it on exit and primes it at the live edge on return (see
    // `onReturnToLive`), and the start gives it play. Something already started doesn't go through
    // here: that's decided by `onBackground`.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, arranque) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && arranque.waiting) {
                Log.i(TAG, "the app went to background while waiting for the first frame: the wait is suspended")
                arranque.suspendWait()
            }
            if (event == Lifecycle.Event.ON_START && arranque.isSuspended) {
                Log.i(TAG, "the app came back: resuming the wait for the first frame")
                arranque.resume(SystemClock.elapsedRealtime())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(exoPlayer) {
        onPlayerReady(exoPlayer)
        mirror.syncTransport(
            buffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
            playing = exoPlayer.isPlaying,
            wantsToPlay = exoPlayer.playWhenReady,
        )

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                mirror.updateBuffering(state == Player.STATE_BUFFERING)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                mirror.updatePlaying(isPlaying)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // If the intent changes while waiting for the frame, it wasn't the start —which
                // releases BEFORE calling `play()`, so by then it's no longer waiting—: it was
                // someone else, the person with play or pause. From there the start never touches
                // the player again.
                if (arranque.waiting) {
                    Log.i(TAG, "play/pause while waiting for the first frame (playWhenReady=$playWhenReady): the person decides")
                    arranque.personDecided()
                }
                mirror.updateWantsToPlay(playWhenReady)
            }

            override fun onTracksChanged(tracks: Tracks) {
                onTracksChanged?.invoke(tracks)
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "onRenderedFirstFrame · pos=${exoPlayer.currentPosition}ms")
                onFirstFrame(true)
                // With the picture already on screen: audio and picture start together.
                if (arranque.frameArrived()) exoPlayer.play()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "onPlayerError code=${error.errorCode} ${error.errorCodeName}", error)
                if (isRecoverable(error) && requestReprepare()) {
                    // After an error the player is left in IDLE: `prepare()` starts it again from
                    // the position it was at. Falling behind a live stream's window is the
                    // exception: there it has to go back to the edge, because that position no
                    // longer exists.
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        exoPlayer.seekToDefaultPosition()
                    }
                    exoPlayer.prepare()
                    return
                }
                onError(error.errorCode, arranque.wantedToPlay(exoPlayer.playWhenReady))
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            Log.i(TAG, "onDispose · pos=${exoPlayer.currentPosition}ms")
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            mirror.resetClock()
            mirror.syncTransport(buffering = false, playing = false, wantsToPlay = false)
            onPlayerReady(null)
            onFirstFrame(false)
        }
    }

    // Position and duration for the bar, same as [MagisExoPlayer]'s polling, and the same reading
    // for [onPosition], which is how `DituState` knows if playback is going.
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(500)
            // The start's safety exit: with no frame in time, it starts anyway. Goes in this clock
            // because it already checks every half second.
            if (arranque.expired(SystemClock.elapsedRealtime())) {
                Log.w(TAG, "no first frame within ${MAX_FIRST_FRAME_WAIT_MS}ms: starting anyway")
                exoPlayer.play()
            }
            val dur = exoPlayer.duration
            val pos = exoPlayer.currentPosition
            mirror.readClock(
                positionMs = pos,
                durationMs = if (dur > 0) dur else 0L,
            )
            onPosition(pos, exoPlayer.isPlaying)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                // The screen's own video view handles the D-pad keys and requests focus for that:
                // this view doesn't need to take it away.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        // In `update` and not `factory`: if the episode changes another ExoPlayer is created, but
        // the view is the same, and without this it would keep pointing at the one already released.
        update = { view ->
            view.player = exoPlayer
            // The screen's zoom, scaling the whole view. Not tested on a device.
            view.scaleX = zoom
            view.scaleY = zoom
        },
        onRelease = { view -> view.player = null },
    )
}
