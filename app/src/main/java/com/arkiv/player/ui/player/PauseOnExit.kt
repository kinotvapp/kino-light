package com.arkiv.player.ui.player

/**
 * Should whatever's playing pause when the app goes to the background (Home, another app)?
 *
 * The ExoPlayers —Magis, live, and Caracol— compose INSIDE `PlayerScreen`: with Home the screen
 * isn't destroyed, so they kept playing outside. A downloaded file is different: it plays
 * on the ExoPlayer hosted by `PlaybackService`, reached through the screen's `controller`, and on
 * the phone it keeps playing in the background on purpose (with the media notification).
 *
 * - Casting to a Chromecast, no: what's shown is on the TV.
 * - On the TV, yes, everything, the local player included.
 * - On the phone, only the in-screen ExoPlayers; the local (service) player keeps playing.
 */
internal fun shouldPauseOnExit(isTv: Boolean, isExoPlayer: Boolean, casting: Boolean): Boolean = when {
    casting -> false
    isTv -> true
    else -> isExoPlayer
}

/** What to do with whatever's playing when the app goes to the background. See [onBackground]. */
internal enum class OnBackground {
    /** Keeps playing: the local (service) player on the phone, or casting to a Chromecast. */
    KEEP_PLAYING,

    /** Pauses right where it is. Stays paused on return: the person decides. */
    PAUSE,

    /**
     * A live channel on ExoPlayer: pauses and stops (`stop()`), and on return it primes again at
     * the live edge, not where it left off; only resumes playing if it was playing (see
     * [onReturnToLive]).
     *
     * Stopped and not just paused because, paused, the player stays armed and can fail in the
     * background. `LiveExoPlayer`'s error goes to `reopenLiveAfterCut`, which spends one of its
     * reopens and reopens the channel: `openCurrentChannel` publishes a new `liveItem`. Nothing gets
     * armed in the background, because `PlayerScreen` reads `liveItem` with
     * `collectAsStateWithLifecycle`; it picks it up on return, and that new `LiveExoPlayer` primes
     * with `playWhenReady = true`: it would start playing on return even if the person had paused
     * it. Stopped has nothing loaded and nothing to fail on.
     */
    STOP_LIVE,
}

/**
 * [shouldPauseOnExit], plus how: a video pauses and a live channel on ExoPlayer stops.
 * [isExoPlayer] means what is playing is not the `controller` (the service-hosted local player).
 */
internal fun onBackground(isTv: Boolean, isExoPlayer: Boolean, casting: Boolean, isLive: Boolean): OnBackground =
    when {
        !shouldPauseOnExit(isTv, isExoPlayer, casting) -> OnBackground.KEEP_PLAYING
        isLive && isExoPlayer -> OnBackground.STOP_LIVE
        else -> OnBackground.PAUSE
    }

/** What to do on return with the live stream that stopped on going to the background. See [onReturnToLive]. */
internal enum class OnReturnToLive {
    /** Was playing on exit: primes at the live edge and resumes playing. */
    RESUME_LIVE,

    /** Was paused on exit: primes at the live edge and stays paused. */
    STAY_PAUSED,
}

/**
 * [wasPlayingOnExit] is the intent to play (`playWhenReady`) read BEFORE pausing to exit: if the
 * person had paused it, it stays paused on return. Applies the same to Magis live and Caracol live.
 */
internal fun onReturnToLive(wasPlayingOnExit: Boolean): OnReturnToLive =
    if (wasPlayingOnExit) OnReturnToLive.RESUME_LIVE else OnReturnToLive.STAY_PAUSED
