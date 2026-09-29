package com.arkiv.player.ui.player

/** Where `StreamExoPlayer.onPlayerError` sends an error. See [playerErrorRoute]. */
internal enum class PlayerErrorRoute {
    /** A live channel's playlist-level error: the live recovery decides (re-join the edge first). */
    LIVE_IN_PLACE,

    /** The DRM session failed: final, said as `PluginWidevine.ERROR_MESSAGE`, never an audio track's fault. */
    DRM_FINAL,

    /** Presumed a side audio track's fault: drop the one to blame and rebuild from the same position. */
    DROP_AUDIO,

    /** Any other cut of a live channel: its reopen budget decides. */
    LIVE_CUT,

    /** A VOD error: the error dialog. */
    FINAL,

    /**
     * A request was refused only because its host is undeclared, and askable
     * (`UndeclaredPlaybackHostException`): the person is asked, and a "yes" rebuilds the player.
     */
    ASK_HOST,
}

/**
 * The decision behind `StreamExoPlayer.onPlayerError`, pure so it can be pinned down.
 *
 * - [live]: a plugin live channel is on this player; [liveInPlace]: the error is one it can fix
 *   in place (behind the live window, playlist reset or stuck).
 * - [drmError]: an `ERROR_CODE_DRM_*`; [drmSoftwareRefused]: this device would not run Widevine at
 *   the software level, so no session can ever open on it.
 * - [audioTracksActive]: side audio tracks are still merged in.
 * - [askableHost]: the error is the gate's `UndeclaredPlaybackHostException` (see [undeclaredPlaybackHost])
 *   and someone is there to ask; it wins over everything else.
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
    askableHost: Boolean = false,
): PlayerErrorRoute = when {
    // First: an undeclared host is neither an audio track's fault nor a cut a reopen would fix (the
    // rebuilt player would meet the same refusal); only the person's answer changes anything.
    askableHost -> PlayerErrorRoute.ASK_HOST
    live && liveInPlace -> PlayerErrorRoute.LIVE_IN_PLACE
    drmError && (!live || drmSoftwareRefused) -> PlayerErrorRoute.DRM_FINAL
    audioTracksActive -> PlayerErrorRoute.DROP_AUDIO
    live -> PlayerErrorRoute.LIVE_CUT
    else -> PlayerErrorRoute.FINAL
}
