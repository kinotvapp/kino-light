package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An online subtitle downloaded while casting reaches the TV: the offer grows without breaking the
 * TV's current URLs, and a Chromecast whose load lacks the chosen one reloads once with it.
 */
class OnlineSubtitleCastTest {

    private val es = CastSubtitleSource("es", "https://cdn/es.srt")
    private val online = CastSubtitleSource("es", "file:///cache/online_subtitles/abc.srt", "srt")

    private fun request(ep: String = "ep1") = CastRequest("http://x/v", "video/mp4", ep, "T", "", "", 0)

    @Test
    fun `an offer only extends the same title's list`() {
        assertTrue(extendsOffer("ep1", listOf(es), "ep1", listOf(es, online)))
        assertFalse(extendsOffer("ep1", listOf(es), "ep2", listOf(es, online)))
        assertFalse(extendsOffer("ep1", listOf(es), "ep1", listOf(online, es)))
        assertFalse(extendsOffer("ep1", emptyList(), "ep1", listOf(online)))
        assertFalse(extendsOffer("ep1", listOf(es), "ep1", listOf(es)))
    }

    @Test
    fun `the grown offer keeps the token the TV already uses`() {
        val server = CastSubtitleServer(lanIp = { "192.168.1.5" })
        server.offer("ep1", listOf(es)) { null }
        val before = server.baseUrl("ep1", 0)!!
        server.offer("ep1", listOf(es, online)) { null }
        assertEquals(before, server.baseUrl("ep1", 0))
        assertEquals(2, server.sourcesFor("ep1").size)
        // Another title still gets a new token.
        server.offer("ep2", listOf(es)) { null }
        assertTrue(server.baseUrl("ep2", 0) != before)
    }

    @Test
    fun `a chosen subtitle missing from the TV's load asks for one reload, not more`() {
        val subs = CastSubtitles(CastSubtitleServer(lanIp = { "192.168.1.5" }))
        subs.offer("ep1", listOf(es)) { null }
        subs.select("ep1", CastTextSelection.On("kino-sub:0", "es"))
        subs.forLoad(request())
        assertFalse("the one on is in the load", subs.needsReload("ep1"))
        // The person downloads an online subtitle mid-cast and the phone turns it on.
        subs.offer("ep1", listOf(es, online)) { null }
        subs.select("ep1", CastTextSelection.On("kino-sub:1", "es"))
        assertTrue(subs.needsReload("ep1"))
        assertFalse("once", subs.needsReload("ep1"))
        // The reload carries it.
        val load = subs.forLoad(request())!!
        assertEquals(1001L, load.activeId)
        assertFalse(subs.needsReload("ep1"))
        // A DLNA re-send offers it too, as SRT from the phone.
        assertTrue(subs.dlnaSidecar()!!.selected!!.url.endsWith("/1.srt"))
    }

    @Test
    fun `a title cast with no subtitles at all reloads once the person adds one`() {
        val subs = CastSubtitles(CastSubtitleServer(lanIp = { "192.168.1.5" }))
        subs.offer("ep1", emptyList()) { null }
        subs.forLoad(request())
        subs.offer("ep1", listOf(online)) { null }
        subs.select("ep1", CastTextSelection.On("kino-sub:0", "es"))
        assertTrue(subs.needsReload("ep1"))
    }
}
