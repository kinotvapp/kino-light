package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The player's mirror: what the screen knows about what's playing —position, duration, whether
 * it's playing, whether it's buffering— plus the download progress painted on top of it.
 *
 * None of this is decided here: these are values that go BEHIND the active player (the local one
 * or Chromecast), written by its listener and by the screen's polling loop, the two that know when
 * to look. This object only keeps them together, because together is how the whole interface
 * reads them.
 *
 * They're grouped on purpose and not loose in the scope: they're the four variables almost every
 * piece of the overlay needs, so every composable that wanted to be extracted was asking for them
 * one by one.
 */
@Stable
internal class PlayerMirror {
    /** Position of the CONTENT (not the receiver's, which has a different origin with a window). */
    var positionMs by mutableLongStateOf(0L)
        private set

    /** Content duration, or 0 while unknown (startup, or a live stream). */
    var durationMs by mutableLongStateOf(0L)
        private set

    var playing by mutableStateOf(false)
        private set

    /** Starts true: when the screen opens nothing is ready yet. */
    var buffering by mutableStateOf(true)
        private set

    /**
     * The INTENT to play (`playWhenReady`), not the same as [playing]: that one also drops on
     * every rebuffer. Tracked separately because it's what distinguishes "the user paused" from
     * "the torrent ran out of data for a second" (see the capture-on-pause effect).
     */
    var wantsToPlay by mutableStateOf(false)
        private set

    /**
     * Fraction [0..1] already downloaded/buffered ahead, for the light-gray stretch of the bar.
     * Comes from `ArchiveCacheProxy.bufferedFraction` (see PlayerScreen's polling loop) -- the same
     * proxy Magis reuses today; 0 while casting, since then it's the receiver that buffers.
     */
    var bufferedFraction by mutableFloatStateOf(0f)
        private set

    /** The three values the player's listener publishes together. */
    fun syncTransport(buffering: Boolean, playing: Boolean, wantsToPlay: Boolean) {
        this.buffering = buffering
        this.playing = playing
        this.wantsToPlay = wantsToPlay
    }

    fun updateBuffering(value: Boolean) {
        buffering = value
    }

    fun updatePlaying(value: Boolean) {
        playing = value
    }

    fun updateWantsToPlay(value: Boolean) {
        wantsToPlay = value
    }

    /**
     * New clock reading. Duration is only overwritten when known: a transient 0 from the player
     * would wipe the bar's total mid-playback.
     */
    fun readClock(positionMs: Long, durationMs: Long) {
        this.positionMs = positionMs
        if (durationMs > 0) this.durationMs = durationMs
    }

    /** After requesting a seek: advances the position without waiting for the player, so the bar doesn't jump. */
    fun jumpTo(positionMs: Long) {
        this.positionMs = positionMs
    }

    /** On loading a new item: the previous clock describes nothing. */
    fun resetClock() {
        positionMs = 0L
        durationMs = 0L
    }

    fun readBuffer(fraction: Float) {
        bufferedFraction = fraction
    }
}

@Composable
internal fun rememberPlayerMirror(): PlayerMirror = remember { PlayerMirror() }
