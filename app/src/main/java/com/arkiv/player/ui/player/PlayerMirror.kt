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

    /**
     * True while the Chromecast is the player in charge (set by the screen from its active player).
     *
     * The in-screen players (Magis/plugin, live, Caracol) keep running underneath a cast -- the
     * Magis one paused at the minute the cast began -- and each of them writes into this mirror
     * from its own listener and its own polling loop. Measured 2026-10-01 casting Xuper to the
     * KALLEY: the receiver was at 2:56 (the screen's own log said so) while the bar sat at 1:47 for
     * the whole cast, because the paused local player's watchdog loop rewrote 1:47 every tick
     * right after the screen wrote the receiver's position. So while this is on, only writes made
     * with `authoritative = true` -- the screen's, which read the active player -- get through.
     */
    var remoteActive: Boolean = false

    private fun accepts(authoritative: Boolean): Boolean = authoritative || !remoteActive

    /** The three values the player's listener publishes together. */
    fun syncTransport(buffering: Boolean, playing: Boolean, wantsToPlay: Boolean, authoritative: Boolean = false) {
        if (!accepts(authoritative)) return
        this.buffering = buffering
        this.playing = playing
        this.wantsToPlay = wantsToPlay
    }

    fun updateBuffering(value: Boolean, authoritative: Boolean = false) {
        if (accepts(authoritative)) buffering = value
    }

    fun updatePlaying(value: Boolean, authoritative: Boolean = false) {
        if (accepts(authoritative)) playing = value
    }

    fun updateWantsToPlay(value: Boolean, authoritative: Boolean = false) {
        if (accepts(authoritative)) wantsToPlay = value
    }

    /**
     * New clock reading. Duration is only overwritten when known: a transient 0 from the player
     * would wipe the bar's total mid-playback.
     */
    fun readClock(positionMs: Long, durationMs: Long, authoritative: Boolean = false) {
        if (!accepts(authoritative)) return
        this.positionMs = positionMs
        if (durationMs > 0) this.durationMs = durationMs
        // Diagnostics only on a CHANGE of "does the bar have a duration", never per tick.
        val missing = this.durationMs <= 0 && positionMs > 0
        if (missing != durationMissing) {
            durationMissing = missing
            com.arkiv.player.ui.ProgressDiagnostics.barDuration(!missing, positionMs, this.durationMs)
        }
    }

    /** Last state reported by [readClock]'s diagnostics; a plain field, never drawn. */
    private var durationMissing = false

    /** After requesting a seek: advances the position without waiting for the player, so the bar doesn't jump. */
    fun jumpTo(positionMs: Long) {
        this.positionMs = positionMs
    }

    /** On loading a new item: the previous clock describes nothing. */
    fun resetClock(authoritative: Boolean = false) {
        if (!accepts(authoritative)) return
        positionMs = 0L
        durationMs = 0L
        durationMissing = false
    }

    fun readBuffer(fraction: Float) {
        bufferedFraction = fraction
    }
}

@Composable
internal fun rememberPlayerMirror(): PlayerMirror = remember { PlayerMirror() }
