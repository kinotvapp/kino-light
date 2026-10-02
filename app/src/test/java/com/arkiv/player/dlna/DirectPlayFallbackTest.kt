package com.arkiv.player.dlna

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPlayFallbackTest {
    @Test fun `a Play the TV rejects with Action Failed is worth a remux`() {
        assertTrue(DirectPlayFallback.shouldRemux(stage = "play", upnpCode = 501, http = 500))
    }

    @Test fun `an illegal or unsupported MIME at SetAVTransportURI is worth a remux`() {
        assertTrue(DirectPlayFallback.shouldRemux(stage = "set_uri", upnpCode = 714, http = 500))
        assertTrue(DirectPlayFallback.shouldRemux(stage = "set_uri", upnpCode = 716, http = 500))
    }

    @Test fun `a TV that did not answer, or was busy, is not a container problem`() {
        assertFalse(DirectPlayFallback.shouldRemux(stage = "play", upnpCode = null, http = 0))
        assertFalse(DirectPlayFallback.shouldRemux(stage = "play", upnpCode = 701, http = 500))
        assertFalse(DirectPlayFallback.shouldRemux(stage = "set_uri", upnpCode = 718, http = 500))
    }

    @Test fun `failures that are not a SOAP rejection never trigger it`() {
        assertFalse(DirectPlayFallback.shouldRemux(stage = "no_wifi_ip", upnpCode = null, http = 0))
        assertFalse(DirectPlayFallback.shouldRemux(stage = "transport_error", upnpCode = null, http = 0))
        assertFalse(DirectPlayFallback.shouldRemux(stage = "play", upnpCode = 501, http = 200))
    }

    @Test fun `a fault refusing the remux as HLS sends the whole MP4, a busy or silent TV does not`() {
        assertTrue(DirectPlayFallback.wholeFileAfterHls(upnpCode = 714, http = 500))
        assertTrue(DirectPlayFallback.wholeFileAfterHls(upnpCode = 501, http = 500))
        assertFalse(DirectPlayFallback.wholeFileAfterHls(upnpCode = 701, http = 500))
        assertFalse(DirectPlayFallback.wholeFileAfterHls(upnpCode = null, http = 0))
        assertFalse(DirectPlayFallback.wholeFileAfterHls(upnpCode = 501, http = 200))
    }

    /** ERRORES-AL7: an LG that listed video/mp2t fetched 39 MB of the TS and went STOPPED at 0:00. */
    @Test
    fun `a TS the TV gave up on before playing is retried remuxed`() {
        assertTrue(DirectPlayFallback.remuxAfterEarlyStop(DlnaDiagnosis.STOPPED_EARLY, advanced = false))
        assertTrue(DirectPlayFallback.remuxAfterEarlyStop(DlnaDiagnosis.TRANSPORT_ERROR, advanced = false))
    }

    @Test
    fun `a file that played, or a stall or an unreachable TV, is not retried remuxed`() {
        assertFalse(DirectPlayFallback.remuxAfterEarlyStop(DlnaDiagnosis.STOPPED_EARLY, advanced = true))
        assertFalse(DirectPlayFallback.remuxAfterEarlyStop(DlnaDiagnosis.NEVER_FETCHED, advanced = false))
        assertFalse(DirectPlayFallback.remuxAfterEarlyStop(DlnaDiagnosis.POSITION_STALLED, advanced = false))
        assertFalse(DirectPlayFallback.remuxAfterEarlyStop(DlnaDiagnosis.STUCK_LOADING, advanced = false))
    }
}
