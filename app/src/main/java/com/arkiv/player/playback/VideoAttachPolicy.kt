package com.arkiv.player.playback

/**
 * Re-binds the local player's video when the app comes back from the background.
 *
 * The local player (downloaded files) lives in `PlaybackService`, so it keeps playing without the
 * UI. On ON_STOP the screen unbinds its TextureView from that ExoPlayer (the audio goes on); on
 * ON_START it binds it again, and ExoPlayer rebuilds its video output on it. It never stops the
 * player: the audio must keep going in the background.
 *
 * It only re-binds after a previous ON_STOP: entering the screen already bound the view when it was
 * built, and the Lifecycle dispatches an ON_START on registering the observer — without this guard
 * that ON_START would unbind and rebind the output that was just created.
 */
class VideoAttachPolicy(
    private val attach: () -> Unit,
    private val detach: () -> Unit,
) {
    private var detached = false

    /** The app went to the background: release the video (audio keeps going). */
    fun onStop() {
        if (detached) return
        detached = true
        detach()
    }

    /** The app came back to the foreground: rebuild the video output if it had been released. */
    fun onStart() {
        if (!detached) return
        detached = false
        attach()
    }
}
