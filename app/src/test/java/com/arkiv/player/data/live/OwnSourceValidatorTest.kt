package com.arkiv.player.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnSourceValidatorTest {
    private fun ok(raw: String) = OwnSourceValidator.checkUrl(raw) as OwnUrlCheck.Ok
    private fun refused(raw: String) = (OwnSourceValidator.checkUrl(raw) as OwnUrlCheck.Refused).message

    @Test fun `an https address is accepted and not marked cleartext`() {
        val r = ok("  https://tv.example.com/live/uno.m3u8  ")
        assertEquals("https://tv.example.com/live/uno.m3u8", r.url)
        assertFalse(r.cleartext)
    }

    @Test fun `an http address is accepted and flagged cleartext`() {
        assertTrue(ok("http://tv.example.com/live/uno.m3u8").cleartext)
    }

    @Test fun `a public IPv4 literal is accepted`() {
        assertEquals("http://8.8.8.8/a.m3u8", ok("http://8.8.8.8/a.m3u8").url)
    }

    @Test fun `the query with a token survives untouched`() {
        val raw = "http://tv.example.com/get.php?username=ana&password=p%40ss&type=m3u_plus&output=ts"
        assertEquals(raw, ok(raw).url)
    }

    @Test fun `host case is canonicalised`() {
        assertEquals("http://example.com/A.m3u8", ok("HTTP://Example.COM/A.m3u8").url)
    }

    @Test fun `blank, too long and non-http schemes are refused`() {
        refused("   ")
        refused("rtmp://example.com/live")
        refused("ftp://example.com/a.m3u8")
        refused("javascript:alert(1)")
        refused("example.com/a.m3u8")
        refused("https://example.com/" + "a".repeat(OwnSourceValidator.MAX_URL))
    }

    @Test fun `the home network, this device and reserved hosts are refused`() {
        for (u in listOf(
            "http://192.168.1.5/a.m3u8", "http://10.0.0.1/a.m3u8", "http://172.16.4.4/a.m3u8",
            "http://127.0.0.1/a.m3u8", "http://localhost:8080/a.m3u8", "http://tv.local/a.m3u8",
            "http://[::1]/a.m3u8", "http://100.64.0.9/a.m3u8", "http://169.254.1.1/a.m3u8",
            "http://0.0.0.0/a.m3u8", "http://224.0.0.1/a.m3u8", "http://2130706433/a.m3u8",
        )) {
            assertTrue(u, OwnSourceValidator.checkUrl(u) is OwnUrlCheck.Refused)
        }
        assertTrue(refused("http://192.168.1.5/a.m3u8").contains("red local"))
    }

    @Test fun `credentials embedded in the address are refused`() {
        assertTrue(OwnSourceValidator.checkUrl("http://ana:clave@example.com/a.m3u8") is OwnUrlCheck.Refused)
    }

    @Test fun `names are 1 to 80 characters`() {
        assertNull(OwnSourceValidator.checkName("Caracol"))
        assertNotNull(OwnSourceValidator.checkName("   "))
        assertNotNull(OwnSourceValidator.checkName("x".repeat(81)))
    }

    @Test fun `header values must be printable ASCII on one line`() {
        assertNull(OwnSourceValidator.checkHeaderValue(""))
        assertNull(OwnSourceValidator.checkHeaderValue("VLC/3.0.20 LibVLC/3.0.20"))
        assertNotNull(OwnSourceValidator.checkHeaderValue("a\r\nX-Injected: 1"))
        assertNotNull(OwnSourceValidator.checkHeaderValue("ñandú"))
        assertNotNull(OwnSourceValidator.checkHeaderValue("x".repeat(201)))
    }

    @Test fun `a logo may be http or https on a public host, or blank`() {
        assertNull(OwnSourceValidator.checkLogo(""))
        assertNull(OwnSourceValidator.checkLogo("https://img.example.com/uno.png"))
        assertNull(OwnSourceValidator.checkLogo("http://img.example.com/uno.png"))
        assertNotNull(OwnSourceValidator.checkLogo("http://192.168.1.5/uno.png"))
        assertNotNull(OwnSourceValidator.checkLogo("https://192.168.1.5/uno.png"))
    }

    @Test fun `the same address with a different spelling is a duplicate`() {
        assertTrue(OwnSourceValidator.isDuplicate("HTTP://Example.com/a.m3u8", listOf("http://example.com/a.m3u8")))
        assertFalse(OwnSourceValidator.isDuplicate("http://example.com/b.m3u8", listOf("http://example.com/a.m3u8")))
        assertFalse(OwnSourceValidator.isDuplicate("nonsense", listOf("nonsense")))
    }
}
