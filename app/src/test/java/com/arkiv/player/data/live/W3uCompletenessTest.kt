package com.arkiv.player.data.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real-world spellings of W3U (Wiseplay) lists, and `tvg-shift` on M3U channels. */
class W3uCompletenessTest {
    private val key = "0123456789abcdef0123456789abcdef"
    private val key2 = "fedcba9876543210fedcba9876543210"

    @Test fun `stations and samples and channels are all read, at the root and in groups`() {
        val list = W3uParser.parse("""{
            "name":"Mixta","author":"yo","image":"https://x.example.com/l.png","telegram":"https://t.me/x",
            "samples":[{"name":"Muestra","url":"https://a.example.com/m.m3u8"}],
            "groups":[{"name":"Fútbol","samples":[{"name":"Partido","url":"https://a.example.com/p.m3u8"}],
                       "channels":[{"title":"Por título","link":"https://a.example.com/t.m3u8"}],
                       "groups":[{"name":"Liga","stations":[{"name":"Liga 1","stream":"https://a.example.com/l1.m3u8"}]}]}]}""")!!
        assertEquals(listOf("Muestra", "Partido", "Por título", "Liga 1"), list.entries.map { it.name })
        assertEquals(listOf("", "Fútbol", "Fútbol", "Fútbol · Liga"), list.entries.map { it.group })
    }

    @Test fun `a list with only samples or only sublists is a W3U list`() {
        assertNotNull(W3uParser.parse("""{"name":"x","samples":[{"name":"A","url":"https://a.example.com/a.m3u8"}]}"""))
        val bundle = W3uParser.parse("""{"name":"x","sublists":["https://a.example.com/uno.w3u",{"name":"Dos","url":"https://a.example.com/dos.w3u"},{"url":"javascript:alert(1)"}]}""")!!
        assertEquals(listOf("https://a.example.com/uno.w3u", "https://a.example.com/dos.w3u"), bundle.links.map { it.url })
        assertEquals("Dos", bundle.links[1].group)
        assertEquals(1, bundle.skipped)
        assertNull(W3uParser.parse("""{"name":"x","other":[1]}"""))
    }

    @Test fun `the field aliases of other lists are read`() {
        val e = W3uParser.parse("""{"name":"x","stations":[
            {"name":"Uno","url":"https://a.example.com/1.m3u8","logo":"https://img.example.com/1.png","epg_id":"uno.co","user_agent":"UA/1","referer":"https://ref.example.com/","group":"Noticias"},
            {"name":"Dos","url":"https://a.example.com/2.m3u8|User-Agent=Kodi%2F2&Referer=https://r.example.com/","icon":"https://img.example.com/2.png","tvg-id":"dos.co"}]}""")!!.entries
        assertEquals("https://img.example.com/1.png", e[0].logo)
        assertEquals("uno.co", e[0].tvgId)
        assertEquals(mapOf("User-Agent" to "UA/1", "Referer" to "https://ref.example.com/"), e[0].headers)
        assertEquals("Noticias", e[0].group)
        assertEquals("https://a.example.com/2.m3u8", e[1].url)
        assertEquals(mapOf("User-Agent" to "Kodi/2", "Referer" to "https://r.example.com/"), e[1].headers)
        assertEquals("dos.co", e[1].tvgId)
    }

    @Test fun `the guide may be under epg, epgUrl or xmltv, one or several`() {
        assertEquals(listOf("https://g.example.com/a.xml.gz"), W3uParser.parse("""{"name":"x","epgUrl":"https://g.example.com/a.xml.gz","stations":[]}""")!!.epgUrls)
        assertEquals(listOf("https://g.example.com/a.xml", "https://g.example.com/b.xml"), W3uParser.parse("""{"xmltv":["https://g.example.com/a.xml","https://g.example.com/b.xml"],"stations":[]}""")!!.epgUrls)
    }

    @Test fun `a ClearKey pair is kept in its usual spellings, any other DRM is not`() {
        val e = W3uParser.parse("""{"name":"x","stations":[
            {"name":"A","url":"https://a.example.com/a.mpd","drm":"clearkey","key":"$key:$key2"},
            {"name":"B","url":"https://a.example.com/b.mpd","clearKey":{"kid":"$key","key":"$key2"}},
            {"name":"C","url":"https://a.example.com/c.mpd","drm":"com.widevine.alpha","license_key":"$key:$key2"},
            {"name":"D","url":"https://a.example.com/d.mpd","key":"no es una clave"},
            {"name":"E","url":"https://a.example.com/e.mpd","license_key":{"keys":[{"kid":"ASNFZ4mrze8BI0VniavN7w","k":"_ty6mHZUMhD-3LqYdlQyEA"}]}}]}""")!!.entries
        assertEquals(key to key2, e[0].drmKeyId to e[0].drmKey)
        assertEquals(key to key2, e[1].drmKeyId to e[1].drmKey)
        assertEquals("", e[2].drmKey)
        assertEquals("", e[3].drmKey)
        assertEquals("0123456789abcdef0123456789abcdef", e[4].drmKeyId) // the ClearKey JSON shape, base64url
        assertEquals(32, e[4].drmKey.length)
    }

    @Test fun `nothing executable is ever carried`() {
        val e = W3uParser.parse("""{"name":"x","stations":[
            {"name":"Script","url":"https://a.example.com/s.m3u8","script":"alert(1)","onPlay":"rm -rf /","headers":{"X-Evil":"1","Cookie":"a=b\r\nInjected: 1"}},
            {"name":"Js","url":"javascript:alert(1)"},{"name":"File","url":"file:///etc/passwd"},{"name":"Web","url":"https://a.example.com/page","embed":true},
            {"name":"Host","url":"https://a.example.com/h","isHost":true}]}""")!!
        assertEquals(listOf("Script"), list(e))
        assertEquals(emptyMap<String, String>(), e.entries.single().headers) // a smuggled line break drops the header
        assertEquals(4, e.skipped)
    }

    private fun list(l: W3uList) = l.entries.map { it.name }

    @Test fun `nested lists keep the depth cap and read sublists too`() = runTest {
        val urlAllowed: (String) -> Boolean = { true }
        val bodies = mapOf(
            "https://a.example.com/1.w3u" to """{"name":"1","sublists":["https://a.example.com/2.w3u"],"stations":[{"name":"S1","url":"https://s.example.com/1"}]}""",
            "https://a.example.com/2.w3u" to """{"name":"2","sublists":["https://a.example.com/3.w3u"],"samples":[{"name":"S2","url":"https://s.example.com/2"}]}""",
            "https://a.example.com/3.w3u" to """{"name":"3","stations":[{"name":"S3","url":"https://s.example.com/3"}]}""",
        )
        val fetcher = LivePlaylistFetcher { url, _, _ -> (bodies[url] ?: throw java.io.IOException("404")).toByteArray() }
        val root = W3uParser.parse("""{"name":"root","sublists":["https://a.example.com/1.w3u"]}""")!!
        val out = W3uExpander(fetcher, urlAllowed).expand(root, "https://a.example.com/root.w3u")
        // Depth 1 and 2 are followed; the third level is not (the existing cap of 2).
        assertEquals(listOf("S1", "S2"), out.entries.map { it.name })
    }

    @Test fun `tvg-shift is read in hours, with sign, decimals and a comma`() {
        val m = M3uParser.parse("""#EXTM3U
            #EXTINF:-1 tvg-id="a" tvg-shift="+2",A
            http://x.example.com/a
            #EXTINF:-1 tvg-shift="-5.5",B
            http://x.example.com/b
            #EXTINF:-1 tvg-shift="1,5",C
            http://x.example.com/c
            #EXTINF:-1 tvg-shift="basura",D
            http://x.example.com/d
            #EXTINF:-1 tvg-shift="99",E
            http://x.example.com/e""".trimIndent())
        assertEquals(listOf(120, -330, 90, 0, 0), m.entries.map { it.tvgShiftMin })
        // Saved lists keep it, so a W3U or Xtream list written as M3U round-trips.
        val sb = StringBuilder().also { M3uWriter.write(m.entries, emptyList(), it) }.toString()
        assertEquals(m.entries.map { it.tvgShiftMin }, M3uParser.parse(sb).entries.map { it.tvgShiftMin })
        assertTrue(sb.contains("tvg-shift=\"2\""))
    }
}
