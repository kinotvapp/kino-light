package com.arkiv.player.dlna

import com.arkiv.player.cast.CastStrategy
import com.arkiv.player.cast.CastStrategy.Format
import com.arkiv.player.cast.CastStrategy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DLNA fallback chain: which stages a renderer's sink list allows (VOD: direct → proxy → HLS
 * remux → whole MP4; live: playlist vs continuous TS), what moves a cast from one stage to the next
 * (a SOAP refusal, an early stop, a load that never plays), and that each stage is tried once.
 */
class DlnaRouteChainTest {

    private val lgWebOs = listOf("video/mp4", "video/mp2t", "application/vnd.apple.mpegurl", "video/x-matroska")
    private val samsung = listOf("video/mp4", "video/mpeg", "video/mp2t", "application/x-mpegurl")
    private val philipsNmr = listOf("audio/mpeg", "image/jpeg", "video/mpeg", "video/vnd.dlna.mpeg-tts", "audio/x-ms-wma")
    private val tsNoHls = listOf("video/mp4", "video/mp2t")

    private fun stages(format: Format, sink: List<String>, direct: Boolean = false) =
        DlnaRouteChain.stagesFor(format, DlnaRenderer.receiverOf(sink), direct)

    // ------------------------------------------------------------------ VOD stages by sink list

    @Test fun `a TS to a TV that lists TS and HLS goes as it is, then the growing remux, then the whole MP4`() {
        assertEquals(listOf(Route.PROXY, Route.REMUX, Route.REMUX_FILE), stages(Format.MPEG_TS, lgWebOs))
        assertEquals(listOf(Route.PROXY, Route.REMUX, Route.REMUX_FILE), stages(Format.MPEG_TS, samsung))
    }

    @Test fun `with the TV's own URL allowed it is the first stage, before the proxy`() {
        assertEquals(listOf(Route.DIRECT, Route.PROXY, Route.REMUX, Route.REMUX_FILE), stages(Format.MPEG_TS, lgWebOs, direct = true))
        assertEquals(listOf(Route.DIRECT, Route.PROXY), stages(Format.MP4, emptyList(), direct = true))
    }

    @Test fun `no HLS in the list skips the growing remux, no TS in it skips the file as it is`() {
        assertEquals(listOf(Route.PROXY, Route.REMUX_FILE), stages(Format.MPEG_TS, tsNoHls))
        assertEquals(listOf(Route.REMUX, Route.REMUX_FILE), stages(Format.MPEG_TS, listOf("video/mp4", "application/vnd.apple.mpegurl")))
        assertEquals(listOf(Route.REMUX_FILE), stages(Format.MPEG_TS, listOf("video/mp4")))
        assertEquals("a renderer that lists nothing gets the whole MP4, as always", listOf(Route.REMUX_FILE), stages(Format.MPEG_TS, emptyList()))
    }

    @Test fun `a file every TV takes, or one nothing decides, goes through the proxy as it is, alone`() {
        assertEquals(listOf(Route.PROXY), stages(Format.MP4, lgWebOs))
        assertEquals(listOf(Route.PROXY), stages(Format.MATROSKA, emptyList()))
        assertEquals(listOf(Route.PROXY), stages(Format.UNKNOWN, emptyList()))
    }

    @Test fun `each stage is offered once, in order, whatever order they fail in`() {
        val chain = DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs, direct = true))
        chain.start(Route.DIRECT)
        assertEquals(Route.PROXY, chain.next(Route.DIRECT))
        chain.start(Route.PROXY)
        assertEquals(Route.REMUX, chain.next(Route.PROXY))
        chain.start(Route.REMUX_FILE) // the remux was already whole on disk: the file went at once
        assertNull("the whole MP4 was tried: nothing after it, and the HLS stage is behind", chain.next(Route.REMUX_FILE))
        assertNull("after the growing remux only the whole MP4, already tried", chain.next(Route.REMUX))
        assertEquals("direct > proxy > remux > remux_file", chain.plan())
    }

    // ------------------------------------------------------------------ advancing, per failure type

    /** Where a cast on [from] goes after [failure] (`refused:<step>:<upnp>:<http>` or a monitor stage), each stage once. */
    private fun after(chain: DlnaRouteChain, from: Route, failure: String, advanced: Boolean = false): Route? {
        chain.start(from)
        val next = chain.next(from) ?: return null
        val moves = if (failure.startsWith("refused:")) {
            val (_, step, upnp, http) = failure.split(':')
            DirectPlayFallback.advancesOnRejection(from, step, upnp.toIntOrNull(), http.toInt())
        } else {
            DirectPlayFallback.advancesOnMonitor(from, failure, advanced)
        }
        return next.takeIf { moves }
    }

    @Test fun `a SOAP refusal a real MP4 fixes moves a TS as it is on to the remux`() {
        val chain = DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs))
        assertEquals(Route.REMUX, after(chain, Route.PROXY, "refused:play:501:500"))
        assertEquals(Route.REMUX_FILE, after(chain, Route.REMUX, "refused:set_uri:716:500"))
        assertNull("the last stage has nothing after it", after(chain, Route.REMUX_FILE, "refused:set_uri:714:500"))
    }

    @Test fun `a busy renderer or one that never answered is not a refusal of the route`() {
        assertNull(after(DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs)), Route.REMUX, "refused:set_uri:701:500"))
        assertNull(after(DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs, direct = true)), Route.DIRECT, "refused:set_uri:-:0"))
        assertNull(after(DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs)), Route.PROXY, "refused:play:402:500"))
    }

    @Test fun `the TV's own URL refused with any HTTP error goes through the proxy`() {
        assertEquals(Route.PROXY, after(DlnaRouteChain(stages(Format.MP4, emptyList(), direct = true)), Route.DIRECT, "refused:set_uri:716:500"))
        assertEquals(Route.PROXY, after(DlnaRouteChain(stages(Format.MP4, emptyList(), direct = true)), Route.DIRECT, "refused:set_uri:-:404"))
    }

    @Test fun `an early stop, a transport error or a load that never plays moves on, a stall after playing does not`() {
        assertEquals(Route.REMUX, after(DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs)), Route.PROXY, DlnaDiagnosis.STOPPED_EARLY))
        assertEquals(Route.REMUX, after(DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs)), Route.PROXY, DlnaDiagnosis.TRANSPORT_ERROR))
        assertEquals(Route.REMUX_FILE, after(DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs)), Route.REMUX, DlnaDiagnosis.STUCK_LOADING))
        assertEquals(Route.REMUX_FILE, after(DlnaRouteChain(stages(Format.MPEG_TS, tsNoHls)), Route.PROXY, DlnaDiagnosis.NEVER_PLAYED))
        assertNull(after(DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs)), Route.PROXY, DlnaDiagnosis.POSITION_STALLED))
        assertNull(after(DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs)), Route.PROXY, DlnaDiagnosis.STOPPED_EARLY, advanced = true))
    }

    @Test fun `no request to the phone moves on only from the TV's own URL, which never asks the phone`() {
        assertEquals(Route.PROXY, after(DlnaRouteChain(stages(Format.MP4, emptyList(), direct = true)), Route.DIRECT, DlnaDiagnosis.NEVER_FETCHED))
        assertNull(after(DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs)), Route.PROXY, DlnaDiagnosis.NEVER_FETCHED))
    }

    @Test fun `a whole chain failing stage after stage tries each once and ends`() {
        val chain = DlnaRouteChain(stages(Format.MPEG_TS, lgWebOs, direct = true))
        val tried = mutableListOf<Route>()
        var route: Route? = chain.stages.first()
        val failures = listOf("refused:set_uri:716:500", DlnaDiagnosis.STOPPED_EARLY, DlnaDiagnosis.STUCK_LOADING, DlnaDiagnosis.STOPPED_EARLY)
        var i = 0
        while (route != null) {
            tried += route
            route = after(chain, route, failures[i++])
        }
        assertEquals(listOf(Route.DIRECT, Route.PROXY, Route.REMUX, Route.REMUX_FILE), tried)
    }

    // ------------------------------------------------------------------ live: playlist or continuous TS

    @Test fun `a live channel goes as its playlist to every TV that lists HLS, the LG and the Samsung included`() {
        assertEquals(DlnaRenderer.HlsRoute.Playlist, DlnaRenderer.liveRoute(lgWebOs))
        assertEquals(DlnaRenderer.HlsRoute.Playlist, DlnaRenderer.liveRoute(samsung))
        assertEquals("one that lists nothing too, as before", DlnaRenderer.HlsRoute.Playlist, DlnaRenderer.liveRoute(emptyList()))
    }

    @Test fun `a TV with no HLS but a TS type gets one continuous TS body of the type it lists`() {
        assertEquals(DlnaRenderer.HlsRoute.ContinuousTs("video/mpeg"), DlnaRenderer.liveRoute(philipsNmr))
        assertEquals(DlnaRenderer.HlsRoute.ContinuousTs("video/mp2t"), DlnaRenderer.liveRoute(tsNoHls))
    }

    @Test fun `a TV with neither gets nothing, and the message says it cannot play live channels`() {
        assertEquals(DlnaRenderer.HlsRoute.None, DlnaRenderer.liveRoute(listOf("video/mp4", "audio/mpeg")))
        assertEquals("Este TV no puede reproducir canales en vivo por DLNA", DlnaRenderer.noHlsMessage(live = true))
    }

    @Test fun `the shared chain decision matches the Chromecast's for its receiver`() {
        assertEquals(listOf(Route.REMUX, Route.REMUX_FILE), CastStrategy.chain(Format.MPEG_TS, needsHeaders = true, directAllowed = false, remuxAvailable = true))
        assertEquals(listOf(Route.DIRECT, Route.PROXY), CastStrategy.chain(Format.MP4, needsHeaders = false, directAllowed = true, remuxAvailable = false))
        assertTrue(CastStrategy.chain(Format.DASH, needsHeaders = false, directAllowed = true, remuxAvailable = true).isEmpty())
        assertFalse(Route.TS_PLAYLIST in CastStrategy.chain(Format.MPEG_TS, needsHeaders = true, directAllowed = false, remuxAvailable = true))
    }
}
