package com.arkiv.player.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the player screen knows about the PICTURE of the local player: the ExoPlayer that
 * `PlaybackService` hosts for downloaded files, driven through the screen's `MediaController`.
 *
 * The in-screen players (Magis, live, Caracol) own their `TextureView` and report their own first
 * frame; the local one paints on a `TextureView` this screen owns and binds to the service's player,
 * so the screen has to keep this itself. Three consumers read it: the first-frame spinner
 * (`noFirstFrame`), the "lost the picture coming back from the background" spinner
 * (`waitingForVideo`), and the decoder watchdog ([com.arkiv.player.playback.DecoderWatchdog]).
 *
 * Times are `SystemClock.elapsedRealtime()` values passed in, so this stays testable on the JVM.
 */
@Stable
internal class LocalVideoState {

    /** A frame was rendered since the last [onLoad]. */
    var renderedFirstFrame by mutableStateOf(false)
        private set

    /**
     * Every `onRenderedFirstFrame` so far. ExoPlayer notifies it again for each new surface, which
     * is what [paintedSinceAttach] relies on.
     */
    var framesNotified by mutableIntStateOf(0)
        private set

    /** Display aspect of the video (width × pixel ratio / height); 0 = not known yet. */
    var aspect by mutableFloatStateOf(0f)
        private set

    /** The current load asked for a software decoder (the watchdog already spent its reload). */
    var loadPrefersSoftware = false
        private set

    private var loadedAtMs = 0L
    private var surfaceSinceMs = 0L
    private var framesAtAttach = 0

    /** A video surface is bound right now (false in the background). */
    val hasSurface: Boolean get() = surfaceSinceMs > 0L

    /** A frame reached the surface bound by the last [onSurfaceAttached]. */
    val paintedSinceAttach: Boolean get() = framesNotified > framesAtAttach

    /** The screen (re)loaded the local media: a new wait for its first frame starts. */
    fun onLoad(nowMs: Long, prefersSoftware: Boolean) {
        loadedAtMs = nowMs
        renderedFirstFrame = false
        loadPrefersSoftware = prefersSoftware
    }

    fun onFirstFrame() {
        renderedFirstFrame = true
        framesNotified++
    }

    fun onVideoSize(width: Int, height: Int, pixelWidthHeightRatio: Float) {
        if (width <= 0 || height <= 0) return
        aspect = width * pixelWidthHeightRatio / height
    }

    fun onSurfaceAttached(nowMs: Long) {
        surfaceSinceMs = nowMs
        framesAtAttach = framesNotified
    }

    fun onSurfaceDetached() {
        surfaceSinceMs = 0L
    }

    /** Since the last load, or -1 when this screen loaded nothing. */
    fun msSinceLoad(nowMs: Long): Long = if (loadedAtMs > 0L) nowMs - loadedAtMs else -1L

    /**
     * How long the current load has had a surface to paint on: since the load or since the surface
     * came back, whichever is later. -1 without a load or without a surface.
     */
    fun msWithSurface(nowMs: Long): Long =
        if (loadedAtMs > 0L && surfaceSinceMs > 0L) nowMs - maxOf(loadedAtMs, surfaceSinceMs) else -1L
}
