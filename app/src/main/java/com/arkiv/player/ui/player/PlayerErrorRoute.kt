package com.arkiv.player.ui.player

/** Where `StreamExoPlayer.onPlayerError` sends an error. See [playerErrorRoute]. */
internal enum class PlayerErrorRoute {
    /** A live channel's playlist-level error: the live recovery decides (re-join the edge first). */
    LIVE_IN_PLACE,

    /** The DRM session failed: final, said as `PluginWidevine.ERROR_MESSAGE`, never an audio track's fault. */
    DRM_FINAL,

    /**
     * A VOD player whose own watchdog says it is stuck (playing with no progress, or buffering and not loading), with retries
     * left: re-prepare the source at the same position. Measured: most of these follow an audio sink discontinuity on TV boxes,
     * after which the AudioTrack never recovers until it is rebuilt.
     */
    STUCK_RETRY,

    /** Presumed a side audio track's fault: drop the one to blame and rebuild from the same position. */
    DROP_AUDIO,

    /** Any other cut of a live channel: its reopen budget decides. */
    LIVE_CUT,

    /** A VOD error: the error dialog. */
    FINAL,
}

/**
 * The decision behind `StreamExoPlayer.onPlayerError`, pure so it can be pinned down.
 *
 * - [live]: a plugin live channel is on this player; [liveInPlace]: the error is one it can fix
 *   in place (behind the live window, playlist reset or stuck).
 * - [drmError]: an `ERROR_CODE_DRM_*`; [drmSoftwareRefused]: this device would not run Widevine at
 *   the software level, so no session can ever open on it.
 * - [audioTracksActive]: side audio tracks are still merged in.
 * - [stuck]: the error is the player's own stuck watchdog ([isStuckPlayer]); [stuckRetriesLeft]: how many re-prepares are left.
 *
 * A live channel's DRM error goes through its reopen budget like any cut (a fresh resolve may bring
 * a fresh license) -- except when the device refused the software level: nothing a reopen brings
 * can change that, so it is final at once instead of three reopens ending in "Se cortó la señal".
 */
internal fun playerErrorRoute(
    live: Boolean,
    liveInPlace: Boolean,
    drmError: Boolean,
    drmSoftwareRefused: Boolean,
    audioTracksActive: Boolean,
    stuck: Boolean = false,
    stuckRetriesLeft: Int = 0,
): PlayerErrorRoute = when {
    live && liveInPlace -> PlayerErrorRoute.LIVE_IN_PLACE
    drmError && (!live || drmSoftwareRefused) -> PlayerErrorRoute.DRM_FINAL
    !live && stuck && stuckRetriesLeft > 0 -> PlayerErrorRoute.STUCK_RETRY
    audioTracksActive -> PlayerErrorRoute.DROP_AUDIO
    live -> PlayerErrorRoute.LIVE_CUT
    else -> PlayerErrorRoute.FINAL
}

/**
 * Whether an error is media3's own stuck-player watchdog: `ERROR_CODE_FAILED_RUNTIME_CHECK` (1003) wrapping "Player stuck playing
 * with no progress for N ms" or "Player stuck buffering and not loading for N ms". [messages] are the messages of the error and its
 * causes. Pure so it can be pinned down; [isStuckPlayer] below adapts a real error.
 */
internal fun isStuckPlayer(errorCode: Int, messages: List<String?>): Boolean =
    errorCode == 1003 && messages.any { it?.startsWith("Player stuck") == true }

internal fun isStuckPlayer(error: androidx.media3.common.PlaybackException): Boolean =
    isStuckPlayer(error.errorCode, generateSequence<Throwable>(error) { it.cause }.take(8).map { it.message }.toList())
