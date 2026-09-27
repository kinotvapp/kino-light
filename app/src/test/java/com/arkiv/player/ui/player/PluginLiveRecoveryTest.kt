package com.arkiv.player.ui.player

import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.plugin.PluginStreamExpiry
import com.arkiv.player.playback.InPlaceRecoveryBudget
import com.arkiv.player.playback.LiveErrorKind
import com.arkiv.player.playback.LiveReopenPolicy
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a plugin's live channel does when its player fails: the same shape as Magis live
 * (`LiveExoPlayer`'s in-place recovery, `reopenLiveAfterCut`'s bounded reopens), never the VOD
 * error dialog on the first hiccup. See [pluginLiveRecovery] and [PluginLiveReopens].
 */
class PluginLiveRecoveryTest {

    // --- pluginLiveRecovery: the pure decision ---

    @Test fun `falling behind the live window rejoins the edge, whatever the reopen count`() {
        for (reopens in 0..5) {
            assertEquals(PluginLiveRecovery.REJOIN_EDGE, pluginLiveRecovery(LiveErrorKind.BEHIND_LIVE_WINDOW, reopens))
        }
        assertEquals(PluginLiveRecovery.REJOIN_EDGE, pluginLiveRecovery(LiveErrorKind.PLAYLIST_RESET, 0))
        assertEquals(PluginLiveRecovery.REJOIN_EDGE, pluginLiveRecovery(LiveErrorKind.PLAYLIST_STUCK, 0))
    }

    @Test fun `a generic cut resolves the channel again, up to Magis live's reopen budget`() {
        for (reopens in 0 until LiveReopenPolicy.MAX_REOPENS) {
            assertEquals("reopen #$reopens", PluginLiveRecovery.RE_RESOLVE, pluginLiveRecovery(LiveErrorKind.OTHER, reopens))
        }
        assertEquals(PluginLiveRecovery.GIVE_UP, pluginLiveRecovery(LiveErrorKind.OTHER, LiveReopenPolicy.MAX_REOPENS))
        assertEquals(PluginLiveRecovery.GIVE_UP, pluginLiveRecovery(LiveErrorKind.OTHER, LiveReopenPolicy.MAX_REOPENS + 1))
    }

    @Test fun `once the in-place budget is spent, falling behind counts as a cut`() {
        assertEquals(PluginLiveRecovery.RE_RESOLVE, pluginLiveRecovery(LiveErrorKind.BEHIND_LIVE_WINDOW, 0, inPlaceLeft = false))
        assertEquals(PluginLiveRecovery.GIVE_UP, pluginLiveRecovery(LiveErrorKind.BEHIND_LIVE_WINDOW, LiveReopenPolicy.MAX_REOPENS, inPlaceLeft = false))
    }

    // --- PluginLiveReopens: the per-channel state the ViewModel keeps ---

    @Test fun `three reopens with doubling waits, then the error`() {
        val r = PluginLiveReopens(nowMs = { 0L })
        val waits = mutableListOf<Long>()
        repeat(LiveReopenPolicy.MAX_REOPENS) {
            assertEquals(PluginLiveRecovery.RE_RESOLVE, r.decide(LiveErrorKind.OTHER))
            waits += r.reopen()
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L), waits)
        assertEquals(LiveReopenPolicy.MAX_REOPENS, r.count)
        assertEquals(PluginLiveRecovery.GIVE_UP, r.decide(LiveErrorKind.OTHER))
    }

    @Test fun `five seconds of advancing picture after a reopen replenishes the budget`() {
        val r = PluginLiveReopens(nowMs = { 0L })
        repeat(LiveReopenPolicy.MAX_REOPENS) { r.decide(LiveErrorKind.OTHER); r.reopen() }
        // A live window's clock starts wherever the edge is (here 25 s in): only the ADVANCE counts.
        r.playing(25_000)
        r.playing(25_000 + LiveReopenPolicy.MIN_HEALTHY_MS - 1)
        assertEquals("not healthy yet", LiveReopenPolicy.MAX_REOPENS, r.count)
        r.playing(25_000 + LiveReopenPolicy.MIN_HEALTHY_MS)
        assertEquals(0, r.count)
        assertEquals(PluginLiveRecovery.RE_RESOLVE, r.decide(LiveErrorKind.OTHER))
    }

    @Test fun `a reopen that never advances does not replenish, so a dead channel ends in the error`() {
        val r = PluginLiveReopens(nowMs = { 0L })
        repeat(LiveReopenPolicy.MAX_REOPENS) {
            r.decide(LiveErrorKind.OTHER)
            r.reopen()
            r.playing(0)
            r.playing(0)
        }
        assertEquals(PluginLiveRecovery.GIVE_UP, r.decide(LiveErrorKind.OTHER))
    }

    @Test fun `a fourth behind-live-window within a minute falls to the reopen, and a reset forgets everything`() {
        var now = 0L
        val r = PluginLiveReopens(nowMs = { now })
        repeat(InPlaceRecoveryBudget.MAX) { assertEquals(PluginLiveRecovery.REJOIN_EDGE, r.decide(LiveErrorKind.BEHIND_LIVE_WINDOW)); now += 1_000 }
        assertEquals(PluginLiveRecovery.RE_RESOLVE, r.decide(LiveErrorKind.BEHIND_LIVE_WINDOW))
        r.reopen()
        r.reset()
        assertEquals(0, r.count)
    }

    // --- the boundary: a VOD plugin stream is not a live channel and keeps the expiry rule ---

    @Test fun `a plugin movie or chapter never enters the live recovery and keeps the expiresInSeconds rule`() {
        assertFalse(PlayerSource.isLiveChannel("plugin:demo:m1::0"))
        assertFalse(PlayerSource.isLiveChannel("plugin:demo:s1::t2e3"))
        assertTrue(PlayerSource.isLiveChannel(PluginIds.liveEpisodeId("demo", "c1")))
        // The VOD rule, unchanged: no tracked expiry means a cut fails outright, never a re-resolve.
        assertFalse(shouldRetryPluginStream(SourceKind.PLUGIN, null, nowMs = 60_000))
        assertTrue(shouldRetryPluginStream(SourceKind.PLUGIN, PluginStreamExpiry(resolvedAtMs = 0, expiresInSeconds = 60), nowMs = 60_000))
    }
}
