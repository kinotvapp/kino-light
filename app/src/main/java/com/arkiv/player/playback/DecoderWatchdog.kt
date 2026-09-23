package com.arkiv.player.playback

/**
 * Whether local playback should be reloaded ONCE preferring a software video decoder.
 *
 * It replaces the hardware→software rescue libVLC used to do for downloaded files. The failure it
 * covers is a hardware HEVC decoder that accepts the stream and never outputs a picture (measured
 * with the same Magis HEVC content these downloads carry). ExoPlayer's decoder fallback only helps
 * when the codec fails to initialise, not when it initialises and stays silent.
 *
 * With ExoPlayer that failure shows up differently than it did with VLC: the video renderer is not
 * "ready" until its first frame is rendered (media3 1.5.1 `VideoFrameReleaseControl.isReady`), so a
 * silent decoder usually holds the player in BUFFERING with a frozen clock instead of letting audio
 * run ahead. That is why the trigger is "wants to play and no frame yet", not "the clock advances
 * and no frame yet": the latter would miss the likely case.
 *
 * Pure so the edges are pinned by tests; the screen only feeds it and acts.
 */
object DecoderWatchdog {

    /**
     * How long without a first frame before reloading in software. Same bound libVLC used for its
     * own rescue back when it played downloaded files, and shorter than [FirstFrameWait.CAP_MS]
     * so the reload happens while the first-frame spinner still covers the screen.
     */
    const val NO_FRAME_MS = 10_000L

    /**
     * @param waitingMs how long the current load has had a surface to paint on: since the load, or
     *   since the surface came back from the background, whichever is later. Negative = nothing loaded.
     * @param renderedFirstFrame whether this load has rendered any frame.
     * @param videoTracks how many video tracks the loaded media has (0 = audio only, or not known yet).
     * @param wantsToPlay `playWhenReady`: paused, nothing is supposed to render.
     * @param hasSurface whether a video surface is attached (false in the background).
     * @param hasError whether the player is reporting a playback error. A file that fails to open
     *   also sits with `playWhenReady` and no frame, and reloading it in software on top of the
     *   error overlay fixes nothing.
     * @param alreadySoftware whether this load already prefers software, which is also what makes it
     *   fire only once.
     */
    @Suppress("LongParameterList")
    fun shouldReloadInSoftware(
        waitingMs: Long,
        renderedFirstFrame: Boolean,
        videoTracks: Int,
        wantsToPlay: Boolean,
        hasSurface: Boolean,
        hasError: Boolean,
        alreadySoftware: Boolean,
    ): Boolean {
        if (waitingMs < 0L) return false
        if (hasError) return false
        if (alreadySoftware || renderedFirstFrame) return false
        if (videoTracks <= 0 || !wantsToPlay || !hasSurface) return false
        return waitingMs >= NO_FRAME_MS
    }
}
