package com.arkiv.player.cast

import com.arkiv.player.cast.CastIdleWatch.Decision
import com.arkiv.player.cast.CastIdleWatch.Idle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the phone does when the receiver drops its media on its own. */
class CastIdleWatchTest {

    private var now = 1_000_000L
    private val watch = CastIdleWatch(clock = { now })
    private val ep = "plugin:xuper:abc::0"
    private val twoHours = 7_667_166L

    private fun playingAt(ms: Long) = watch.onPosition(ep, ms, playing = true)

    @Test
    fun `a receiver starved by the network is retried once from where it was`() {
        watch.onNewMedia(ep)
        watch.onOwnLoad()
        now += 60_000
        playingAt(181_358)
        assertEquals(Decision.Retry(181_358), watch.onIdle(Idle.ERROR, ep, twoHours))
    }

    @Test
    fun `a second drop right after the retry asks the person instead of looping`() {
        watch.onNewMedia(ep)
        playingAt(181_358)
        assertEquals(Decision.Retry(181_358), watch.onIdle(Idle.INTERRUPTED, ep, twoHours))
        watch.onOwnLoad()
        now += 30_000
        playingAt(185_000)
        assertEquals(Decision.Ask(185_000), watch.onIdle(Idle.ERROR, ep, twoHours))
        // Asked: more drops change nothing until the person answers.
        assertEquals(Decision.Ignore, watch.onIdle(Idle.ERROR, ep, twoHours))
    }

    @Test
    fun `the person's retry gets one load, and failing again asks again, never retries alone`() {
        watch.onNewMedia(ep)
        playingAt(100_000)
        watch.onIdle(Idle.ERROR, ep, twoHours)
        watch.onIdle(Idle.ERROR, ep, twoHours)
        watch.onUserRetry()
        assertEquals(Decision.Ask(100_000), watch.onIdle(Idle.ERROR, ep, twoHours))
    }

    @Test
    fun `steady playback after a retry gives the automatic retry back`() {
        watch.onNewMedia(ep)
        playingAt(100_000)
        watch.onIdle(Idle.ERROR, ep, twoHours)
        playingAt(100_000 + CastIdleWatch.STEADY_MS)
        assertEquals(Decision.Retry(100_000 + CastIdleWatch.STEADY_MS), watch.onIdle(Idle.ERROR, ep, twoHours))
    }

    @Test
    fun `our own stop, a cancel from the TV and an idle with no reason are not failures`() {
        watch.onNewMedia(ep)
        playingAt(100_000)
        assertEquals(Decision.Ignore, watch.onIdle(Idle.CANCELED, ep, twoHours))
        assertEquals(Decision.Ignore, watch.onIdle(Idle.NONE, ep, twoHours))
        watch.onIntentionalStop()
        assertEquals(Decision.Ignore, watch.onIdle(Idle.ERROR, ep, twoHours))
    }

    @Test
    fun `nothing asked of the receiver means nothing to recover`() {
        assertEquals(Decision.Ignore, watch.onIdle(Idle.ERROR, null, twoHours))
    }

    @Test
    fun `the interruption our own load causes is not a failure, a later one is`() {
        watch.onNewMedia(ep)
        playingAt(50_000)
        watch.onOwnLoad()
        now += 2_000
        assertEquals(Decision.Ignore, watch.onIdle(Idle.INTERRUPTED, ep, twoHours))
        now += CastIdleWatch.LOAD_GRACE_MS
        assertEquals(Decision.Retry(50_000), watch.onIdle(Idle.INTERRUPTED, ep, twoHours))
    }

    @Test
    fun `finishing at the end is the end, finishing early is a failure`() {
        watch.onNewMedia(ep)
        playingAt(twoHours - 20_000)
        assertEquals(Decision.Ignore, watch.onIdle(Idle.FINISHED, ep, twoHours))
        watch.onNewMedia("other")
        watch.onPosition("other", 600_000, playing = true)
        assertEquals(Decision.Retry(600_000), watch.onIdle(Idle.FINISHED, "other", twoHours))
    }

    @Test
    fun `a failure before any frame retries from the request's own start`() {
        watch.onNewMedia(ep)
        assertEquals(Decision.Retry(null), watch.onIdle(Idle.ERROR, ep, twoHours))
    }

    @Test
    fun `a new title starts with a full budget and forgets the old position`() {
        watch.onNewMedia(ep)
        playingAt(100_000)
        watch.onIdle(Idle.ERROR, ep, twoHours)
        watch.onIdle(Idle.ERROR, ep, twoHours)
        watch.onNewMedia("next")
        assertNull(watch.lastKnownMs("next"))
        assertEquals(Decision.Retry(null), watch.onIdle(Idle.ERROR, "next", twoHours))
    }

    @Test
    fun `a new load of the same title forgets the previous session's position for its own start`() {
        watch.onNewMedia(ep, 499_521L)
        playingAt(511_422L)
        // The person went on watching on the phone and cast again from 642833 ms.
        watch.onNewMedia(ep, 642_833L)
        assertEquals(642_833L, watch.lastKnownMs(ep))
        assertEquals(Decision.Retry(642_833L), watch.onIdle(Idle.ERROR, ep, twoHours))
    }

    @Test
    fun `a load from the very start knows no position yet`() {
        watch.onNewMedia(ep, 100_000L)
        watch.onNewMedia(ep, 0L)
        assertNull(watch.lastKnownMs(ep))
    }

    @Test
    fun `positions of another title are not taken as this one's`() {
        watch.onNewMedia(ep)
        watch.onPosition("someone-else", 999_000, playing = true)
        assertNull(watch.lastKnownMs(ep))
        playingAt(5_000)
        assertEquals(5_000L, watch.lastKnownMs(ep))
        assertNull(watch.lastKnownMs("someone-else"))
    }

    @Test
    fun `a retry that never plays asks, once`() {
        watch.onNewMedia(ep)
        playingAt(100_000)
        watch.onIdle(Idle.ERROR, ep, twoHours)
        assertEquals(Decision.Ask(100_000), watch.onRetryStalled())
        assertEquals(Decision.Ignore, watch.onRetryStalled())
        assertEquals(Decision.Ignore, watch.onIdle(Idle.ERROR, ep, twoHours))
    }

    @Test
    fun `no retry in course, nothing stalled`() {
        watch.onNewMedia(ep)
        assertEquals(Decision.Ignore, watch.onRetryStalled())
    }

    @Test
    fun `cast idle reasons map from the SDK's codes`() {
        assertEquals(Idle.NONE, Idle.fromCast(0))
        assertEquals(Idle.FINISHED, Idle.fromCast(1))
        assertEquals(Idle.CANCELED, Idle.fromCast(2))
        assertEquals(Idle.INTERRUPTED, Idle.fromCast(3))
        assertEquals(Idle.ERROR, Idle.fromCast(4))
        assertEquals(Idle.NONE, Idle.fromCast(42))
    }

    @Test
    fun `a load that never played asks once, from where the load started`() {
        watch.onNewMedia(ep, startMs = 170_093)
        watch.onOwnLoad()
        assertEquals(Decision.Ask(170_093), watch.onLoadStalled())
        assertEquals("asked already: no second dialog", Decision.Ignore, watch.onLoadStalled())
        // A new title gets its own watch.
        watch.onNewMedia(ep, startMs = 0)
        assertEquals(Decision.Ask(null), watch.onLoadStalled())
    }
}
