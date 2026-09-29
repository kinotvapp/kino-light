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
}
