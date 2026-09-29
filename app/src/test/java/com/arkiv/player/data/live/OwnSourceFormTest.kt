package com.arkiv.player.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnSourceFormTest {
    private fun channel(url: String = "https://tv.example.com/uno.m3u8") = OwnSourceForm(OwnKind.CHANNEL, "Uno", url)
    private fun valid(f: OwnSourceForm, others: List<String> = emptyList()) = f.validate("id1", others) as OwnFormResult.Valid
    private fun invalid(f: OwnSourceForm, others: List<String> = emptyList()) = (f.validate("id1", others) as OwnFormResult.Invalid).errors

    @Test fun `a minimal channel is valid and blanks become null`() {
        val v = valid(channel())
        assertEquals("CHANNEL", v.source.kind)
        assertEquals("id1", v.source.id)
        assertNull(v.source.groupName); assertNull(v.source.logo); assertNull(v.source.userAgent)
        assertFalse(v.cleartext)
    }

    @Test fun `http is valid and flagged`() {
        assertTrue(valid(channel("http://tv.example.com/uno.m3u8")).cleartext)
    }

    @Test fun `a playlist keeps epg, headers and refresh, a channel drops what does not apply`() {
        val p = valid(OwnSourceForm(OwnKind.PLAYLIST, "Lista", "http://tv.example.com/l.m3u", epgUrl = "http://tv.example.com/e.xml",
            userAgent = "VLC/3", referer = "http://tv.example.com/", refreshHours = 6, groupName = "ignored", logo = "https://i.example.com/x.png")).source
        assertEquals("PLAYLIST", p.kind); assertEquals("http://tv.example.com/e.xml", p.epgUrl)
        assertEquals(6, p.refreshHours); assertNull(p.groupName); assertNull(p.logo)
        val c = valid(channel().copy(epgUrl = "http://tv.example.com/e.xml", refreshHours = 6, groupName = "Noticias")).source
        assertNull(c.epgUrl); assertEquals(0, c.refreshHours); assertEquals("Noticias", c.groupName)
    }

    @Test fun `every bad field is reported at once, keyed by field`() {
        val e = invalid(OwnSourceForm(OwnKind.CHANNEL, " ", "http://192.168.1.5/a.m3u8", logo = "http://x.example.com/l.png",
            userAgent = "a\nb", referer = "ñ"))
        assertEquals(setOf(OwnField.NAME, OwnField.URL, OwnField.LOGO, OwnField.USER_AGENT, OwnField.REFERER), e.keys)
    }

    @Test fun `a bad epg address is refused for a playlist and an out of range refresh is clamped`() {
        val e = invalid(OwnSourceForm(OwnKind.PLAYLIST, "L", "https://tv.example.com/l.m3u", epgUrl = "http://10.0.0.1/e.xml"))
        assertTrue(OwnField.EPG in e)
        val v = valid(OwnSourceForm(OwnKind.PLAYLIST, "L", "https://tv.example.com/l.m3u", refreshHours = 500))
        assertEquals(168, v.source.refreshHours)
    }

    @Test fun `a duplicate address among the other sources is refused`() {
        val e = invalid(channel("HTTPS://TV.example.com/uno.m3u8"), others = listOf("https://tv.example.com/uno.m3u8"))
        assertTrue(OwnField.URL in e)
    }

    @Test fun `an entity turns back into an editable form`() {
        val v = valid(OwnSourceForm(OwnKind.PLAYLIST, "Lista", "http://tv.example.com/l.m3u", userAgent = "VLC/3", refreshHours = 12))
        val back = v.source.toForm()
        assertEquals(OwnKind.PLAYLIST, back.kind); assertEquals("VLC/3", back.userAgent); assertEquals(12, back.refreshHours)
        assertEquals("", back.referer)
    }
}
