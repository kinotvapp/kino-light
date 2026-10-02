package com.arkiv.player.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a cast failure report says about its routes (Chromecast and DLNA alike), and [CastGaveUp],
 * the one place every exhausted cast passes through.
 */
class CastRouteTrailTest {

    @After fun tearDown() {
        CastGaveUp.lastResort = null
    }

    @Test fun `the report lists every route tried with its result, in order`() {
        val trail = CastRouteTrail()
        trail.tried("proxy", "refused:play:upnp501:http500")
        trail.tried("remux", "stopped_early")
        trail.tried("remux_file", "stuck_loading")
        val extras = CastRouteTrail.reportExtras(trail, "remux_file", "http://127.0.0.1:4100/t/0123456789abcdef0123456789abcdef/stream.ts")
        assertEquals("proxy=refused:play:upnp501:http500 > remux=stopped_early > remux_file=stuck_loading", extras["routes"])
        assertEquals("remux_file", extras["stage"])
        assertEquals("the phone's own server is just \"lan\"", "lan", extras["host"])
    }

    @Test fun `hosts are masked to a host name, never a path, a query or a token`() {
        val trail = CastRouteTrail()
        trail.tried("direct", "idle error at https://cdn.example.com/v/secret.mp4?token=abc")
        val extras = CastRouteTrail.reportExtras(trail, "direct", "https://user:pw@cdn.example.com:8443/v/secret.mp4?token=abc")
        assertEquals("cdn.example.com", extras["host"])
        assertFalse(extras.values.any { it.contains("token=abc") || it.contains("pw@") })
        assertTrue(extras["routes"]!!.startsWith("direct=idle_error_at_https://cdn.example.com"))
        assertEquals("", CastRouteTrail.reportExtras(CastRouteTrail(), "", null)["host"])
    }

    @Test fun `a long hex run in a result is blanked and the trail is bounded`() {
        val trail = CastRouteTrail()
        trail.tried("proxy", "token ${"a".repeat(32)}")
        assertFalse(trail.summary().contains("a".repeat(32)))
        repeat(40) { trail.tried("x", "y") }
        assertEquals(CastRouteTrail.MAX, trail.size)
    }

    @Test fun `an exhausted VOD cast gets the last option once one exists, a live one never`() {
        val vod = CastGaveUp.Exhausted(CastGaveUp.Receiver.DLNA, "magis:123", "Título", live = false, routes = "proxy=stopped_early")
        val live = vod.copy(episodeId = "live:rcn", live = true)
        assertNull("nothing is built yet (0.9.46)", CastGaveUp.exhausted(vod))
        assertSame(vod, CastGaveUp.last)
        val download = object : CastGaveUp.LastResort {
            override val label = "Descargar y preparar para la TV"
            override fun start(exhausted: CastGaveUp.Exhausted) {}
        }
        CastGaveUp.lastResort = download
        assertSame(download, CastGaveUp.exhausted(vod))
        assertSame(download, CastGaveUp.exhausted(vod.copy(receiver = CastGaveUp.Receiver.CHROMECAST)))
        assertNull(CastGaveUp.exhausted(live))
        assertNull("a cast that does not say which title cannot be prepared", CastGaveUp.exhausted(vod.copy(episodeId = "")))
    }
}
