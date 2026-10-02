package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each TV load carries for subtitles, and that the phone's choice outlives the screen: a
 * re-cast or a reconnect of the same title gets the subtitle that was on, like the audio.
 */
class CastSubtitlesTest {

    private val sources = listOf(CastSubtitleSource("es", "https://cdn/es.srt"), CastSubtitleSource("en", "https://cdn/en.srt"))

    private fun subs(override: String? = null) =
        CastSubtitles(CastSubtitleServer(lanIp = { "192.168.1.5" }), deliveryOverride = { override })

    private fun request(ep: String = "ep1", hlsFmp4: Boolean = false, offsetMs: Long = 0, asLive: Boolean = false) =
        CastRequest("http://x/v", "video/mp4", ep, "T", "", "", 0, offsetMs = offsetMs, hlsFmp4 = hlsFmp4, asLive = asLive)

    @Test
    fun `a progressive load gets every subtitle as a sidecar WebVTT with the phone's choice on`() {
        val s = subs()
        s.offer("ep1", sources) { null }
        s.select("ep1", CastTextSelection.On("kino-sub:1", "en"))
        val load = s.forLoad(request())!!
        assertEquals(CastSubtitleDelivery.SIDECAR, load.delivery)
        assertEquals(2, load.tracks.size)
        assertTrue(load.tracks[0].url.matches(Regex("http://192\\.168\\.1\\.5:\\d+/s/[0-9a-f]{32}/0/0\\.vtt")))
        assertEquals(1001L, load.activeId)
        assertEquals("en", s.wanted("ep1")?.language)
        assertTrue(s.manifestRenditions().isEmpty())
    }

    @Test
    fun `the remux HLS carries them in its manifest, on the load's timeline`() {
        val s = subs()
        s.offer("ep1", sources) { null }
        val load = s.forLoad(request(hlsFmp4 = true, offsetMs = 90_000))!!
        assertEquals(CastSubtitleDelivery.MANIFEST, load.delivery)
        assertNull(load.activeId)
        val renditions = s.manifestRenditions()
        assertEquals(listOf("Español", "Inglés"), renditions.map { it.name })
        assertTrue(renditions[1].segmentBase.endsWith("/90000/1/"))
        assertTrue(s.inManifest("ep1"))
        // Overridden for a device test.
        assertEquals(CastSubtitleDelivery.SIDECAR, subs("sidecar").also { it.offer("ep1", sources) { null } }.forLoad(request(hlsFmp4 = true))!!.delivery)
        assertNull(subs("off").also { it.offer("ep1", sources) { null } }.forLoad(request(hlsFmp4 = true)))
    }

    @Test
    fun `nothing for a title without subtitles, another title, or a live stream`() {
        val s = subs()
        s.offer("ep1", sources) { null }
        assertNull(s.forLoad(request(ep = "ep2")))
        assertNull(s.forLoad(request(asLive = true)))
        s.offer("live", emptyList()) { null }
        assertNull(s.forLoad(request(ep = "live")))
        assertNull(s.dlnaSidecar())
    }

    @Test
    fun `a choice reported before the offer lands is applied when it does, and survives re-offers`() {
        val s = subs()
        var changes = 0
        s.onChoiceChanged = { changes++ }
        s.select("ep1", CastTextSelection.On("kino-sub:1", "en"))
        s.offer("ep1", sources) { null }
        assertEquals(1, changes)
        assertEquals(1001L, s.forLoad(request())!!.activeId)
        // The screen comes back (or the cast reconnects): same title, same choice.
        s.offer("ep1", sources) { null }
        assertEquals(1001L, s.forLoad(request())!!.activeId)
        // Unknown (a fresh player not reporting yet) changes nothing; Off turns it off.
        s.select("ep1", CastTextSelection.Unknown)
        assertEquals(1001L, s.forLoad(request())!!.activeId)
        s.select("ep1", CastTextSelection.Off)
        assertEquals(2, changes)
        assertNull(s.forLoad(request())!!.activeId)
        assertNull(s.wanted("ep1"))
    }

    @Test
    fun `DLNA gets SRT URLs on the title's own timeline, the phone's choice first`() {
        val s = subs()
        s.offer("ep1", sources) { null }
        assertNull(s.dlnaSidecar()!!.selected)
        s.select("ep1", CastTextSelection.On("kino-sub:1", "en"))
        val sidecar = s.dlnaSidecar()!!
        assertNotNull(sidecar.selected)
        assertTrue(sidecar.selected!!.url.endsWith("/0/1.srt"))
        assertEquals(listOf("es"), sidecar.others.map { it.language })
        // A remux started 35 min in: the cues move back by as much, like the Chromecast's.
        assertTrue(s.dlnaSidecar(2_100_000L)!!.selected!!.url.endsWith("/2100000/1.srt"))
    }
}
