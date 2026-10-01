package com.arkiv.player.data.live

import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.LiveChannel
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwnLiveProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val list = """
        #EXTM3U
        #EXTINF:-1 tvg-id="c1" tvg-logo="https://img.example.com/1.png" group-title="Noticias",Canal Uno
        http://stream.example.com/uno.m3u8
        #EXTINF:-1 group-title="Deportes",Canal Dos
        https://stream.example.com/dos.m3u8
        #EXTINF:-1 group-title="XXX",Prohibido
        http://stream.example.com/x.m3u8
        #EXTINF:-1 group-title="Noticias",Casero
        http://192.168.1.10/casero.m3u8
    """.trimIndent()

    private fun single(id: String, name: String, url: String, group: String? = null, ua: String? = null) =
        OwnLiveSourceEntity(id, "CHANNEL", name, url, groupName = group, userAgent = ua)

    private fun playlist(id: String, name: String, url: String) = OwnLiveSourceEntity(id, "PLAYLIST", name, url)

    private fun provider(
        sources: List<OwnLiveSourceEntity>,
        body: String = list,
        fetcher: LivePlaylistFetcher = LivePlaylistFetcher { _, _, _ -> body.toByteArray() },
        onCache: (Int) -> Unit = {},
    ) = OwnLiveProvider(
        sources = { sources }, fetcher = fetcher, cacheDir = tmp.newFolder(), allCachesRoot = null,
        syncCache = { onCache(it.size) }, clock = { 1_000L }, log = {},
    )

    private suspend fun channelsOf(p: OwnLiveProvider): List<LiveChannel> =
        p.categories(includeAdults = false).flatMap { p.channels(it.id) }

    @Test fun `single channels are grouped and use their id as the code`() = runTest {
        val p = provider(listOf(
            single("s1", "Uno", "https://a.example.com/1.m3u8"),
            single("s2", "Dos", "https://a.example.com/2.m3u8", group = "Noticias"),
        ))
        val cats = p.categories(includeAdults = false)
        assertEquals(setOf("Canales sueltos", "Noticias"), cats.map { it.name }.toSet())
        val all = cats.flatMap { p.channels(it.id) }
        assertEquals(setOf("s1", "s2"), all.map { it.code }.toSet())
        assertTrue(all.all { it.provider == OwnLive.PROVIDER })
    }

    @Test fun `a playlist becomes the groups of the list, adult and private entries dropped`() = runTest {
        val p = provider(listOf(playlist("p1", "Mi lista", "http://tv.example.com/get.php?u=ana")))
        val cats = p.categories(includeAdults = false)
        assertEquals(setOf("Noticias", "Deportes"), cats.map { it.name }.toSet())
        val channels = cats.flatMap { p.channels(it.id) }
        assertEquals(setOf("Canal Uno", "Canal Dos"), channels.map { it.name }.toSet())   // the XXX group and the LAN entry are gone
        assertTrue(channels.all { it.code.startsWith("~") })
    }

    @Test fun `with two lists the categories say which list they belong to`() = runTest {
        val p = provider(listOf(playlist("p1", "Lista A", "http://a.example.com/l.m3u"), playlist("p2", "Lista B", "http://b.example.com/l.m3u")))
        val names = p.categories(false).map { it.name }
        assertTrue(names.isNotEmpty() && names.all { " · " in it })
        assertTrue(names.any { it.startsWith("Lista A") } && names.any { it.startsWith("Lista B") })
    }

    @Test fun `a channel keeps its code when the list is reordered, so favourites do not move`() = runTest {
        val a = provider(listOf(playlist("p1", "L", "http://tv.example.com/l.m3u")), body = list)
        val lines = list.lines()
        val reordered = (listOf(lines[0]) + lines.drop(1).chunked(2).reversed().flatten()).joinToString("\n")
        // Same list, a new token in the query: the key ignores the query, so it is the same list.
        val b = provider(listOf(playlist("p1", "L", "http://tv.example.com/l.m3u?token=NEW")), body = reordered)
        assertEquals(channelsOf(a).associate { it.name to it.code }, channelsOf(b).associate { it.name to it.code })
    }

    @Test fun `opening a single channel plays its address with the headers of the source`() = runTest {
        val p = provider(listOf(single("s1", "Uno", "http://a.example.com/1.m3u8", ua = "VLC/3")))
        val ch = p.channels(p.categories(false).single().id).single()
        val o = p.open(ch) as LiveOpening.Plugin
        assertEquals("plugin:own:s1::live", o.channel.episodeId)
        assertEquals("own", o.channel.pluginId)
        assertEquals("http://a.example.com/1.m3u8", o.channel.direct!!.url)
        assertEquals("VLC/3", o.channel.direct!!.headers["User-Agent"])
        assertEquals("Uno", o.channel.title)
    }

    @Test fun `a playlist channel opens even on a fresh instance that never listed it, after a restart`() = runTest {
        val src = listOf(playlist("p1", "L", "http://tv.example.com/l.m3u"))
        val listed = channelsOf(provider(src)).first { it.name == "Canal Dos" }
        val fresh = provider(src)
        val o = fresh.open(LiveChannel(listed.code, listed.code, 0, null, provider = OwnLive.PROVIDER)) as LiveOpening.Plugin
        assertEquals("https://stream.example.com/dos.m3u8", o.channel.direct!!.url)
        assertEquals("Canal Dos", o.channel.title)
    }

    @Test fun `opening an unknown channel fails with a Spanish message`() = runTest {
        val p = provider(listOf(single("s1", "Uno", "https://a.example.com/1.m3u8")))
        try {
            p.open(LiveChannel("nope", "nope", 0, null, provider = OwnLive.PROVIDER))
            org.junit.Assert.fail("should have thrown")
        } catch (e: GatewayException) {
            assertTrue(e.message!!.contains("Mis canales"))
        }
    }

    @Test fun `a failed download or an empty answer gives an empty section, never an exception`() = runTest {
        val offline = provider(listOf(playlist("p1", "L", "http://tv.example.com/l.m3u")), fetcher = LivePlaylistFetcher { _, _, _ -> throw IOException("sin red") })
        assertTrue(offline.categories(false).isEmpty())
        val html = provider(listOf(playlist("p1", "L", "http://tv.example.com/l.m3u")), body = "<html>403 Forbidden</html>")
        assertTrue(html.categories(false).isEmpty())
    }

    @Test fun `no sources at all is an empty section`() = runTest {
        val p = provider(emptyList())
        assertTrue(p.categories(false).isEmpty())
        assertNull(p.initialCategory())
        assertFalse(p.hasGuide())
    }

    @Test fun `a change in the sources shows up on the next listing`() = runTest {
        var current = listOf(single("s1", "Uno", "https://a.example.com/1.m3u8"))
        val p = OwnLiveProvider({ current }, LivePlaylistFetcher { _, _, _ -> ByteArray(0) }, tmp.newFolder(), null, clock = { 1L }, log = {})
        assertEquals(1, channelsOf(p).size)
        current = current + single("s2", "Dos", "https://a.example.com/2.m3u8").copy(updatedAt = 2)
        assertEquals(2, channelsOf(p).size)
    }

    @Test fun `the playlist channels are mirrored into the search cache`() = runTest {
        var rows = -1
        val p = provider(listOf(playlist("p1", "L", "http://tv.example.com/l.m3u")), onCache = { rows = it })
        p.categories(false)
        assertEquals(2, rows)
    }

    @Test fun `editing a list to another address lists the new one, not the old one`() = runTest {
        val bodies = mapOf(
            "http://old.example.com/l.m3u" to "#EXTM3U\n#EXTINF:-1 group-title=\"Viejo\",Canal Viejo\nhttp://s.example.com/v.m3u8\n",
            "http://new.example.com/other/l.m3u" to "#EXTM3U\n#EXTINF:-1 group-title=\"Nuevo\",Canal Nuevo\nhttp://s.example.com/n.m3u8\n",
        )
        val fetched = mutableListOf<String>()
        var current = listOf(playlist("p1", "L", "http://old.example.com/l.m3u"))
        val p = OwnLiveProvider(
            { current }, LivePlaylistFetcher { url, _, _ -> fetched += url; bodies.getValue(url).toByteArray() },
            tmp.newFolder(), null, clock = { 1_000L }, log = {},
        )
        assertEquals(setOf("Canal Viejo"), channelsOf(p).map { it.name }.toSet())
        current = listOf(playlist("p1", "L", "http://new.example.com/other/l.m3u").copy(updatedAt = 5))
        assertEquals(setOf("Canal Nuevo"), channelsOf(p).map { it.name }.toSet())
        assertTrue("http://new.example.com/other/l.m3u" in fetched)
    }

    @Test fun `a list is downloaded again once its refresh time has passed, with the sources unchanged`() = runTest {
        var now = 1_000L
        var body = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",Uno\nhttp://s.example.com/1.m3u8\n"
        val src = playlist("p1", "L", "http://tv.example.com/l.m3u").copy(refreshHours = 1)
        val p = OwnLiveProvider({ listOf(src) }, LivePlaylistFetcher { _, _, _ -> body.toByteArray() }, tmp.newFolder(), null, clock = { now }, log = {})
        assertEquals(setOf("Uno"), channelsOf(p).map { it.name }.toSet())
        body = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",Uno\nhttp://s.example.com/1.m3u8\n#EXTINF:-1 group-title=\"G\",Dos\nhttp://s.example.com/2.m3u8\n"
        assertEquals("not yet: the copy is fresh", setOf("Uno"), channelsOf(p).map { it.name }.toSet())
        now += 2 * 60 * 60 * 1000L
        assertEquals(setOf("Uno", "Dos"), channelsOf(p).map { it.name }.toSet())
    }

    @Test fun `a list naming its guide in the header gets it, with no guide typed by the person`() = runTest {
        val body = "#EXTM3U url-tvg=\"https://epg.example.com/g.xml.gz,http://10.0.0.1/lan.xml\"\n" +
            "#EXTINF:-1 tvg-id=\"c1\",Canal Uno\nhttps://stream.example.com/uno.m3u8\n"
        val guide = "<?xml version=\"1.0\"?><tv><channel id=\"c1\"><display-name>Canal Uno</display-name></channel>" +
            "<programme start=\"19700101000000 +0000\" stop=\"19700101010000 +0000\" channel=\"c1\"><title>Noticiero</title></programme></tv>"
        val asked = mutableListOf<String>()
        val p = provider(listOf(playlist("p1", "Mi lista", "https://tv.example.com/l.m3u")), fetcher = LivePlaylistFetcher { url, _, _ ->
            asked += url
            (if (url.startsWith("https://epg.")) guide else body).toByteArray()
        })
        val channels = channelsOf(p)
        assertTrue(p.hasGuide())
        val programmes = p.guide(channels).first.values.single()
        assertEquals(listOf("Noticiero"), programmes.map { it.title })
        assertFalse(asked.any { "10.0.0.1" in it })   // a LAN address found inside a downloaded list is never fetched
    }

    @Test fun `a Wiseplay W3U list plays through the same pipeline, with its station headers`() = runTest {
        val w3u = """{"name":"Mi lista","groups":[{"name":"Noticias","stations":[
            {"name":"Canal W","url":"https://w.example.com/w.m3u8","referer":"https://w.example.com/"},
            {"name":"Casero","url":"http://192.168.1.5/c.m3u8"}]}]}"""
        val inner = LivePlaylistFetcher { _, _, _ -> w3u.toByteArray() }
        val p = provider(listOf(playlist("p1", "Mi lista", "https://tv.example.com/lista.w3u")),
            fetcher = W3uPlaylistFetcher(inner, { OwnSourceValidator.checkUrl(it) is OwnUrlCheck.Ok }))
        val channels = channelsOf(p)
        assertEquals(listOf("Canal W"), channels.map { it.name })
        val opening = p.open(channels.single()) as LiveOpening.Plugin
        assertEquals("https://w.example.com/", opening.channel.direct!!.headers["Referer"])
    }

    @Test fun `what reaches the log never carries an address, so a list's credentials stay out of it`() {
        val line = "playlist http://tv.example.com/get.php?username=ana&password=secreto&type=m3u not downloaded (Unable to resolve host \"tv.example.com\"); no saved copy"
        val redacted = redactUrls(line)
        assertFalse(redacted.contains("secreto"))
        assertFalse(redacted.contains("get.php"))
        assertTrue(redacted.contains("not downloaded"))
    }
}
