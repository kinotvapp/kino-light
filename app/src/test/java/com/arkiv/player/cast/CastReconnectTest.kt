package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** What a session (re)connect replays of the last request. */
class CastReconnectTest {

    private val remux = CastRequest(
        uri = "http://192.168.2.11:39919/r/b27cfe40ff5685890346c1c460fe4d7b/master.m3u8",
        mimeType = "application/vnd.apple.mpegurl",
        episodeId = "plugin:xuper:abc::0",
        title = "T",
        subtitle = "",
        artworkUrl = "",
        startPositionMs = 170_767,
        durationMs = 7_667_166,
        hlsFmp4 = true,
    )

    @Test
    fun `a request whose server or token is gone is never replayed`() {
        // Measured 2026-10-01: the old token answered 404 on media.m3u8, the old port refused.
        assertNull(CastReconnect.replay(remux, stillServed = { false }, lastKnownMs = 342_850))
    }

    @Test
    fun `nothing pending replays nothing`() {
        assertNull(CastReconnect.replay(null, stillServed = { true }, lastKnownMs = null))
    }

    @Test
    fun `a request still served resumes where the receiver last was, not where it was first sent`() {
        val replayed = CastReconnect.replay(remux, stillServed = { it == remux.uri }, lastKnownMs = 342_850)
        assertEquals(342_850L, replayed?.startPositionMs)
        assertEquals(remux.uri, replayed?.uri)
    }

    @Test
    fun `without a newer position the request goes as it was`() {
        assertSame(remux, CastReconnect.replay(remux, stillServed = { true }, lastKnownMs = null))
    }

    @Test
    fun `a live stream never gets a position`() {
        val live = remux.copy(startPositionMs = 0, durationMs = 0, asLive = true, hlsFmp4 = false)
        assertSame(live, CastReconnect.replay(live, stillServed = { true }, lastKnownMs = 90_000))
    }

    @Test
    fun `a replay that knows nothing of a load from the top resumes at the saved progress, not 0`() {
        val fromTop = remux.copy(startPositionMs = CastIdleWatch.TOP_MS)
        assertEquals(true, CastReconnect.needsSaved(fromTop, lastKnownMs = null))
        assertEquals(true, CastReconnect.needsSaved(fromTop, lastKnownMs = CastIdleWatch.TOP_MS))
        assertEquals(412_000L, CastReconnect.replay(fromTop, { true }, lastKnownMs = null, savedMs = 412_000)!!.startPositionMs)
        // The receiver's report and a real start still come first, and need no saved progress.
        assertEquals(false, CastReconnect.needsSaved(fromTop, lastKnownMs = 90_000))
        assertEquals(90_000L, CastReconnect.replay(fromTop, { true }, lastKnownMs = 90_000, savedMs = 412_000)!!.startPositionMs)
        assertEquals(false, CastReconnect.needsSaved(remux, lastKnownMs = null))
        assertSame(remux, CastReconnect.replay(remux, { true }, lastKnownMs = null, savedMs = 412_000))
        // Nothing known anywhere: the request as it was.
        assertSame(fromTop, CastReconnect.replay(fromTop, { true }, lastKnownMs = null, savedMs = null))
        assertSame(fromTop, CastReconnect.replay(fromTop, { true }, lastKnownMs = null, savedMs = 0))
    }
}
