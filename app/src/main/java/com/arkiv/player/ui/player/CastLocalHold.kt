package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.Player

/**
 * One rule for every cast, Chromecast or DLNA, VOD or live: while the TV plays, the phone's own
 * player is silent. Before it, a DLNA cast paused only the service player (a Magis/plugin title kept
 * playing on the phone and drifted from the TV: phone at 1290 s, LG at ~950 s, and the progress and
 * every re-send came from the phone), and a live channel kept playing on the phone under both
 * (2026-10-01: "en vivo sigue reproduciendo mientras el tv también").
 *
 * A VOD player is paused where it is: the cast's end resumes it at the TV's position. A live player
 * is STOPPED, not paused: paused, an HLS live player keeps reloading its playlist and buffering, a
 * second connection to the channel's CDN beside the TV's -- the one a single-connection provider
 * cuts. Stopped it holds nothing; the cast's end primes it again at the live edge ([resumeLive]).
 */
internal object CastLocalHold {

    /**
     * Silences the phone for a cast: [service] (the `controller`) is paused, each in-screen player
     * ([magis], [live]; null when absent) paused or, on a [isLive] channel, stopped.
     */
    fun hold(service: Player?, magis: Player?, live: Player?, isLive: Boolean) {
        service?.let { runCatching { it.pause() } }
        for (p in listOf(magis, live)) {
            if (p == null || p === service) continue
            runCatching {
                p.pause()
                if (isLive) p.stop()
            }
        }
    }

    /**
     * A live channel after its cast: each in-screen player stopped by [hold] primes again at the live
     * edge, and plays when [play] (false: the person pressed the bar's stop and asked for silence;
     * primed anyway, or "play" would find nothing loaded).
     */
    fun resumeLive(magis: Player?, live: Player?, play: Boolean) {
        for (p in listOf(magis, live)) {
            if (p == null) continue
            runCatching {
                p.seekToDefaultPosition()
                p.prepare()
                if (play) p.play()
            }
        }
    }
}

/**
 * Keeps [CastLocalHold] true for players that show up DURING a cast ([on]): a zap or a reopen
 * builds a new `LiveExoPlayer`, which primes with `playWhenReady = true` and would play on the phone
 * under the TV. Keyed by the players only, so it never fires on the cast's own start (that hold is
 * the cast's, which also knows when a cast is refused and must leave the phone playing).
 * A composable of its own: `PlayerContent` is at ART's verifier limit.
 */
@Composable
internal fun CastLocalHoldEffect(on: Boolean, service: Player, magis: Player?, live: Player?, isLive: Boolean) {
    val casting by rememberUpdatedState(on)
    LaunchedEffect(magis, live) {
        if (!casting) return@LaunchedEffect
        android.util.Log.i("ArkivCast", "a player appeared while casting: silenced (live=$isLive)")
        CastLocalHold.hold(service, magis, live, isLive)
    }
}