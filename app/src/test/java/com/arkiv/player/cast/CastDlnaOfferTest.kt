package com.arkiv.player.cast

import com.arkiv.player.dlna.DlnaDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Probar por DLNA en <TV>" after the Chromecast failed a title twice: offered only for the SAME TV,
 * found over DLNA at the Cast device's IP (or with its very name), never another one.
 */
class CastDlnaOfferTest {

    private val kalley = DlnaDevice("KALLEY Android TV", "http://192.168.1.40:49152/upnp/control/AVTransport1", "KALLEY", "K-ATV55UHD")
    private val lg = DlnaDevice("[LG] webOS TV OLED55", "http://192.168.1.52:1468/AVTransport/control", "LG Electronics", "OLED55")
    private val samsung = DlnaDevice("Samsung QN55", "http://192.168.1.60:9197/upnp/control/AVTransport1", "Samsung", "QN55Q60")

    @Test fun `the renderer at the Cast device's IP is the one offered`() {
        assertEquals(kalley, CastDlnaOffer.match("192.168.1.40", "Sala", listOf(lg, kalley, samsung)))
        assertEquals("the SDK's InetAddress.toString() spelling too", kalley, CastDlnaOffer.match("/192.168.1.40", null, listOf(lg, kalley)))
    }

    @Test fun `without an IP match, the same name is the same TV`() {
        assertEquals(lg, CastDlnaOffer.match("192.168.1.99", "[LG] webOS TV OLED55", listOf(kalley, lg)))
        assertEquals("case and punctuation aside", lg, CastDlnaOffer.match(null, "lg webos tv oled55", listOf(kalley, lg)))
    }

    @Test fun `another TV on the network is never offered`() {
        assertNull(CastDlnaOffer.match("192.168.1.77", "Cuarto", listOf(kalley, lg, samsung)))
        assertNull(CastDlnaOffer.match(null, null, listOf(kalley)))
        assertNull(CastDlnaOffer.match("192.168.1.40", "Sala", emptyList()))
        assertNull("a two-letter name matches nothing", CastDlnaOffer.match(null, "TV", listOf(DlnaDevice("TV", "http://10.0.0.9/ctl"))))
    }

    @Test fun `it is looked for only after two failures of the title, with its player screen up`() {
        assertFalse(CastDlnaOffer.worthLooking(failures = 1, screenOnTitle = true))
        assertTrue(CastDlnaOffer.worthLooking(failures = 2, screenOnTitle = true))
        assertFalse(CastDlnaOffer.worthLooking(failures = 3, screenOnTitle = false))
    }

    @Test fun `the button names the TV, in Spanish`() {
        assertEquals("Probar por DLNA en KALLEY Android TV", CastDlnaOffer.label(kalley))
    }
}
