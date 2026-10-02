package com.arkiv.player.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The phone stays silent while a TV plays, Chromecast or DLNA ([CastLocalHold]): 2026-10-01, a DLNA
 * cast left a Magis title playing on the phone (it drifted 340 s from the LG), and a live channel
 * played on the phone under both TVs ("en vivo sigue reproduciendo mientras el tv también").
 */
class CastLocalHoldTest {

    /** A [Player] that only records which of its methods were called. */
    private class Recorder {
        val calls = mutableListOf<String>()
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, _ ->
            calls += m.name
            when (m.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        } as Player
    }

    @Test fun `a video cast pauses every local player where it is, none stopped`() {
        val service = Recorder(); val magis = Recorder()
        CastLocalHold.hold(service.player, magis.player, null, isLive = false)
        assertEquals(listOf("pause"), service.calls)
        assertEquals(listOf("pause"), magis.calls)
    }

    @Test fun `a live cast stops the channel's player, no second connection to its CDN`() {
        val service = Recorder(); val live = Recorder(); val plugin = Recorder()
        CastLocalHold.hold(service.player, plugin.player, live.player, isLive = true)
        // The service player holds no channel: only paused.
        assertEquals(listOf("pause"), service.calls)
        assertEquals(listOf("pause", "stop"), live.calls)
        assertEquals(listOf("pause", "stop"), plugin.calls)
    }

    @Test fun `the service player passed twice is only paused, never stopped`() {
        val service = Recorder()
        CastLocalHold.hold(service.player, service.player, null, isLive = true)
        assertEquals(listOf("pause"), service.calls)
    }

    @Test fun `a live channel comes back at its edge, playing unless silence was asked`() {
        val live = Recorder()
        CastLocalHold.resumeLive(null, live.player, play = true)
        assertEquals(listOf("seekToDefaultPosition", "prepare", "play"), live.calls)
        val stopped = Recorder()
        CastLocalHold.resumeLive(stopped.player, null, play = false)
        assertEquals(listOf("seekToDefaultPosition", "prepare"), stopped.calls)
    }
}
