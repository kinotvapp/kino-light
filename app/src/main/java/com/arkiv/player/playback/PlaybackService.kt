package com.arkiv.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.arkiv.player.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Reference to the episode currently playing (for the notification's deep-link). */
object NowPlaying {
    @Volatile
    var episodeId: String? = null

    /**
     * Is the player screen open on THIS device?
     *
     * Exists apart from [episodeId] on purpose: that value is never cleared —the notification's
     * deep-link and next/previous resolution read it, and they need it AFTER the player closes—.
     * Without this signal the TV publisher kept announcing the last episode as paused forever, and
     * the phone's bar showed something that hadn't been playing for a while.
     */
    @Volatile
    var playerOpen: Boolean = false

    /** Instant the player was opened; the phone uses it to tell "started over playing the same
     *  thing" apart from "still playing the same thing" (see `NowPlayingCoordinator`). Only makes
     *  sense while [playerOpen] is true — not cleared on close because there's no need. */
    @Volatile
    var playerOpenedAtMs: Long = 0L

    /**
     * Current live channel's name (Task 15), or null outside live mode.
     *
     * Exists because a live channel is NOT a library episode: `episodeId` holds
     * `"live:<code>"`, and `NowPlayingPublisher.metaFor()` has nowhere to get a title from if it
     * looks that up in `ArkivRepository` (headerInfo/getEpisode return empty, the phone's bar would
     * be left blank when sending a channel to the TV). `PlayerScreen` updates it on every zap, same
     * as [episodeId]; not cleared on exit for the same reason that field isn't cleared.
     */
    @Volatile
    var liveChannelName: String? = null
}

/**
 * The service's [ExoPlayer], the one that plays downloaded files (successor of the old libVLC
 * handle). The screen drives it through its `MediaController` for everything —transport, tracks,
 * speed, volume, first frame, errors— and uses this handle ONLY to bind its video `TextureView`
 * (and to ask whether the loaded media already painted, for `MediaReusePolicy`).
 *
 * Why the surface doesn't go through the controller: every `PlayerScreen` builds its own
 * `MediaController`, two screens coexist during a navigation, and a controller's surface state is
 * its own. The outgoing controller sends `setVideoSurface(null)` when its TextureView is torn down
 * (media3 1.5.1 `MediaControllerImplBase.onSurfaceTextureDestroyed`) and the session applies it to
 * the shared player, blanking the incoming screen. The player's own `setVideoTextureView` /
 * `clearVideoTextureView(view)` arbitrate that correctly: the latter is a no-op unless `view` is the
 * current one.
 */
object PlaybackEngine {
    @Volatile
    var player: ExoPlayer? = null
}

const val ACTION_OPEN_PLAYER = "com.arkiv.player.OPEN_PLAYER"

/**
 * Which episode to open with [ACTION_OPEN_PLAYER]. Optional: without it, whatever is currently
 * playing opens ([NowPlaying]), which is what the player's notification wants. The "download
 * complete" notice uses it, since it points at a specific episode and not whatever was playing.
 */
const val EXTRA_EPISODE_ID = "episodeId"

/**
 * Service that hosts the ExoPlayer for downloaded files ([LocalExoPlayer]) behind a MediaSession.
 * Media3 builds the playback notification from it (artwork and controls on the lock screen and the
 * notification shade), and the session keeps the file playing when the app goes to the background.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = LocalExoPlayer.build(this)
        PlaybackEngine.player = player

        // Tapping the notification opens the app on the current episode.
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(MediaItemResolverCallback)
            .build()
    }

    /**
     * When sending MediaItems from a MediaController to the MediaSession, media3 **drops the
     * `localConfiguration` (the URI)** crossing the controller→session boundary, and without this
     * callback the `setMediaItems` command is silently ignored (the player never gets the items →
     * black screen, no playback). Here each MediaItem is rebuilt with its URI, which the UI
     * preserves in `requestMetadata.mediaUri` (that field DOES survive the IPC). This is media3's
     * recommended pattern.
     */
    private object MediaItemResolverCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapTo(ArrayList(mediaItems.size)) { item ->
                val uri = item.requestMetadata.mediaUri
                val ex = item.requestMetadata.extras
                val b = item.buildUpon()
                if (uri != null) b.setUri(uri)
                // Rebuild the PlayerSourceTag (kind/referer/etc.) that the IPC lost. See
                // PlayerSourceTagIpc: the codec lives there and not here so the round trip is
                // testable without Robolectric.
                PlayerSourceTagIpc.decodeFromBundle(ex)?.let { b.setTag(it) }
                b.build()
            }
            return Futures.immediateFuture(resolved)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // HEADS UP: while casting, the local player is left with playWhenReady=false ON PURPOSE
        // (so it doesn't compete with the receiver for the stream), which is exactly the condition
        // read here as "nothing is playing". It's fine for the service to stop —the magis proxy and
        // the live one live in the graph, not here—; what CANNOT happen is for it to let go of the
        // network, and that's what the releaseNetworkResources() guard takes care of.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            // The user swiped the app away from recents with no active playback: cut network
            // resources (magis proxy + live proxy) before stopping the service.
            releaseNetworkResources()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Stop the magis proxy and the live one BEFORE releasing the player: both live in the
        // graph (background via service/MediaSession), so when the service is destroyed this is
        // where they have to be released to avoid leaking network/battery/disk.
        releaseNetworkResources()
        PlaybackEngine.player = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** Closes the archive proxy and the live one (idempotent and null-safe). */
    private fun releaseNetworkResources() {
        // With a live Chromecast session they are NOT released: the receiver is pulling bytes from
        // this process's LAN server. This genuinely happens on the "open the app already casting
        // and send the episode straight to the TV" path: there was never local playback there, the
        // service was only BOUND, and releasing the MediaController (leaving the screen) destroys
        // the service and lands here.
        if (isCasting()) {
            android.util.Log.i("ArkivCast", "not releasing network resources: a Chromecast session is alive")
            return
        }
        runCatching {
            val graph = (application as com.arkiv.player.ArkivApp).graph
            runCatching { graph.archiveCacheProxy.stop() }
            // Task 14 (live channel) created liveHlsProxy/liveController in the graph but never
            // closed them: the ServerSocket on 127.0.0.1 and its accept() thread stayed alive for
            // the rest of the process after leaving a channel. Same sibling as archiveCacheProxy:
            // closed here, with the SAME casting guard above -since Task 18 that guard REALLY
            // protects an ongoing Chromecast session: the receiver pulls segments from THIS proxy
            // (see PlayerScreen.castRequestFor/LiveHlsProxy.lanUrl), so closing it with the TV
            // still playing would cut the channel dead.
            runCatching { graph.liveHlsProxy.stop() }
            // close() only invalidates the cache of resolved sessions (there's no socket to release
            // here, stop() above already did that) so the next channel opened doesn't reuse a stale
            // gateway session after a long network cut or the process being paused.
            runCatching { graph.liveController.close() }
        }
    }

    /** Is there a live Chromecast session? Without Google Play Services the manager doesn't exist → false. */
    private fun isCasting(): Boolean = runCatching {
        (application as com.arkiv.player.ArkivApp).graph.castSession?.casting?.value == true
    }.getOrDefault(false)
}
