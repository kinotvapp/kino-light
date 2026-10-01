package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What reaches the receiver for the remux served as HLS, and the one-entry-per-TV chooser. */
class CastRemuxHlsTest {

    private fun remuxRequest(start: Long, hlsFmp4: Boolean = true) = CastRequestBuilder.build(
        episodeId = "plugin:xuper:X::0",
        title = "Deadpool & Wolverine",
        subtitle = "",
        artworkUrl = "",
        mediaUrl = "http://127.0.0.1:41017/t/tok/s",
        castUrl = "http://cdn.example/vod/X_media.ts",
        lanUrl = "http://192.168.2.11:40000/r/abc/master.m3u8",
        startPositionMs = start,
        mimeOverride = "application/vnd.apple.mpegurl",
        requiresLanUrl = true,
        durationMs = 7_667_166L,
        hlsFmp4 = hlsFmp4,
    )!!

    @Test
    fun `the remux goes out as an fMP4 HLS playlist, from where the phone was`() {
        val r = remuxRequest(start = 269_014L)
        assertEquals("http://192.168.2.11:40000/r/abc/master.m3u8", r.uri)
        assertEquals("application/vnd.apple.mpegurl", r.mimeType)
        // The position the old reload threw away (`re-casting as mp4 from 269014ms` → `from=0ms`).
        assertEquals(269_014L, r.startPositionMs)
        assertTrue(r.hlsFmp4)
        assertFalse(r.asLive)
        assertEquals(7_667_166L, r.durationMs)
    }

    @Test
    fun `a live channel is never marked as fMP4`() {
        val r = CastRequestBuilder.build(
            episodeId = "live:1", title = "", subtitle = "", artworkUrl = "", mediaUrl = "http://127.0.0.1/x",
            castUrl = null, lanUrl = "http://192.168.2.11/live.m3u8", startPositionMs = 0, isLive = true, hlsFmp4 = true,
        )!!
        assertFalse(r.hlsFmp4)
    }

    @Test
    fun `the remux is announced as a buffered title of known length with fMP4 segments`() {
        val shape = CastStreamShape.of(durationMs = 7_667_166L, isLive = false, hlsFmp4 = true)!!
        assertFalse(shape.live)
        assertEquals(7_667_166L, shape.streamDurationMs)
        assertTrue(shape.fmp4Segments)
    }

    @Test
    fun `fMP4 alone is enough to need a MediaInfo of our own`() {
        val shape = CastStreamShape.of(durationMs = 0L, isLive = false, hlsFmp4 = true)!!
        assertEquals(-1L, shape.streamDurationMs)
        assertTrue(shape.fmp4Segments)
    }

    @Test
    fun `everything else is announced as before`() {
        assertNull(CastStreamShape.of(durationMs = 0L, isLive = false, hlsFmp4 = false))
        assertEquals(CastStreamShape(live = true, streamDurationMs = -1L, fmp4Segments = false), CastStreamShape.of(5_000L, isLive = true, hlsFmp4 = false))
        assertEquals(CastStreamShape(live = false, streamDurationMs = 5_000L, fmp4Segments = false), CastStreamShape.of(5_000L, isLive = false, hlsFmp4 = false))
    }

    @Test
    fun `the same TV listed by both Play services providers shows once, as the Cast provider's route`() {
        val mr1 = CastRouteDedup.Route("com.google.android.gms/.cast.media.CastMediaRouteProviderService_Persistent:46d2", "liliycami", "R3")
        val mr2 = CastRouteDedup.Route("com.google.android.gms/.cast.media.CastMediaRoute2ProviderService_Persistent:46d2", "liliycami", "R3")
        val other = CastRouteDedup.Route("com.google.android.gms/.cast.media.CastMediaRouteProviderService_Persistent:99", "Sala", "Chromecast")
        assertEquals(setOf(mr1.id, other.id), CastRouteDedup.keep(listOf(mr2, mr1, other)))
    }

    @Test
    fun `two different TVs that only share a name both stay`() {
        val a = CastRouteDedup.Route("p:1", "TV", "Chromecast")
        val b = CastRouteDedup.Route("p:2", "TV", "KALLEY R3")
        assertEquals(setOf("p:1", "p:2"), CastRouteDedup.keep(listOf(a, b)))
    }

    @Test
    fun `the same TV listed again under its model name shows once`() {
        val named = CastRouteDedup.Route("com.google.android.gms/.cast.media.CastMediaRouteProviderService_Persistent:46d2352c", "liliycami", "R3")
        val model = CastRouteDedup.Route("com.google.android.gms/.cast.media.CastMediaRoute2ProviderService_Persistent:46d2352c", "R3", null)
        assertEquals(setOf(named.id), CastRouteDedup.keep(listOf(model, named)))
    }

    @Test
    fun `routes that name the same Cast device or address are one TV, whatever they are called`() {
        val a = CastRouteDedup.Route("x:1", "liliycami", "R3", deviceKeys = setOf("46d2352c-6846-5b9d-c454-39ef4ee675ad", "192.168.2.12"))
        val b = CastRouteDedup.Route("y:2", "R3", null, deviceKeys = setOf("192.168.2.12"))
        val other = CastRouteDedup.Route("z:3", "Sala", "Chromecast", deviceKeys = setOf("192.168.2.40"))
        assertEquals(setOf("x:1", "z:3"), CastRouteDedup.keep(listOf(a, b, other)))
    }
}
