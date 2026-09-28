package com.arkiv.player.data.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginCaller
import com.arkiv.player.data.plugin.PluginManifest
import java.io.File
import java.io.IOException
import java.time.Instant
import com.arkiv.player.data.plugin.PluginRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginLivePlaylistTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fixtures = File("../docs/plugins/fixtures/live")
    private val plugin = InstalledPlugin(
        PluginManifest("demo", "Demo", "1.0.0", 3, "plugin.js", "", "", "", listOf("lists.example.com", "live.example.com"), setOf("home", "resolve", "channels"), null, null),
        InstalledRecord("o/demo", "1.0.0", "x", listOf("lists.example.com", "live.example.com"), 0L),
        null,
    )
    private var now = Instant.parse("2026-09-27T16:00:00Z").toEpochMilli()
    private val calls = mutableListOf<String>()
    private val caller = PluginCaller { _, fn, _, _ ->
        calls += fn
        """[{"id":"news","title":"Noticias propias"},
            {"playlist":{"url":"https://lists.example.com/basic.m3u","format":"m3u","epg":{"url":"https://lists.example.com/guide.xml.gz","format":"xmltv"}}}]"""
    }
    private val downloads = mutableListOf<String>()
    private var failDownloads = false
    private val fetcher = LivePlaylistFetcher { url, _, _ ->
        downloads += url
        if (failDownloads) throw IOException("sin red")
        File(fixtures, url.substringAfterLast('/')).readBytes()
    }
    private fun provider(cache: File = tmp.root) = PluginLiveProvider(plugin, caller, fetcher = fetcher, cacheDir = cache, clock = { now }, log = {})

    @Test fun `playlist groups follow the plugin's own categories`() = runBlocking {
        val cats = provider().categories(false)
        // basic.m3u's "Deportes, en vivo" entry is plain http on a host not approved as insecure: skipped, so its group never appears.
        assertEquals(listOf("Noticias propias", "Noticias", "Infantil"), cats.map { it.name })
        assertEquals(listOf("liveCategories"), calls)
    }

    @Test fun `a playlist channel plays directly, with no plugin call`() = runBlocking {
        val p = provider()
        val cats = p.categories(false)
        val uno = p.channels(cats[1].id).single()
        assertTrue(uno.code.endsWith(".canal1.co"))
        val opening = p.open(uno) as LiveOpening.Plugin
        assertEquals("https://live.example.com/c1/index.m3u8", opening.channel.direct!!.url)
        assertEquals(listOf("liveCategories"), calls)
    }

    @Test fun `the playlist guide comes from its XMLTV, by tvg-id`() = runBlocking {
        val p = provider()
        val cats = p.categories(false)
        val uno = p.channels(cats[1].id).single()
        val guide = p.guide(listOf(uno)).first
        assertEquals(listOf("Noticias del mediodía", "Magazín"), guide.getValue(uno.liveCode).map { it.title })
        assertTrue(p.hasGuide())
    }

    @Test fun `a fresh copy on disk is reused, and a failed download falls back to the stale one`() = runBlocking {
        provider().categories(false)
        assertEquals(1, downloads.count { it.endsWith(".m3u") })
        provider().categories(false)
        assertEquals(1, downloads.count { it.endsWith(".m3u") })
        now += 13 * 3600 * 1000L
        failDownloads = true
        val p = provider()
        assertEquals(3, p.categories(false).size)
        assertEquals(2, downloads.count { it.endsWith(".m3u") })
    }

    @Test fun `a list over the channel cap is cut and says so`() = runBlocking {
        val big = "#EXTM3U\n" + (0 until 5010).joinToString("") { "#EXTINF:-1 group-title=\"G\",C$it\nhttps://live.example.com/$it.m3u8\n" }
        val p = PluginLiveProvider(plugin, caller, fetcher = { url, _, _ -> if (url.endsWith(".m3u")) big.toByteArray() else throw IOException("no") },
            cacheDir = tmp.newFolder(), clock = { now }, log = {})
        val cats = p.categories(false)
        assertEquals(5000, p.channels(cats[1].id).size)
        assertEquals("Lista recortada: 5000 de 5010 canales", p.notice.value)
    }

    private fun custom(m3u: String, epg: ByteArray?, playlistJson: String, cache: File = tmp.newFolder()): Pair<PluginLiveProvider, MutableList<String>> {
        val got = mutableListOf<String>()
        val p = PluginLiveProvider(plugin, PluginCaller { _, _, _, _ -> "[{\"playlist\":$playlistJson}]" },
            fetcher = { url, _, _ ->
                got += url
                if (url.endsWith(".m3u")) m3u.toByteArray() else epg ?: throw IOException("no epg")
            },
            cacheDir = cache, clock = { now }, log = {})
        return p to got
    }

    @Test fun `two screens asking at once download and group the playlist once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fetches = java.util.concurrent.atomic.AtomicInteger()
        val p = PluginLiveProvider(plugin, caller, fetcher = { url, _, _ ->
            if (url.endsWith(".m3u")) { fetches.incrementAndGet(); gate.await() }
            File(fixtures, url.substringAfterLast('/')).readBytes()
        }, cacheDir = tmp.newFolder(), clock = { now }, log = {})
        val a = async { p.categories(false) }
        val b = async { p.categories(false) }
        // Hold the first download until the second screen has had every chance to start its own.
        while (fetches.get() == 0) kotlinx.coroutines.delay(5)
        kotlinx.coroutines.delay(300)
        gate.complete(Unit)
        val (x, y) = awaitAll(a, b)
        assertEquals(x, y)
        assertEquals(1, fetches.get())
    }

    @Test fun `Recargar downloads the playlist again, a plain listing does not`() = runBlocking {
        val p = provider()
        val cats = p.categories(false)
        p.channels(cats[1].id)
        assertEquals(1, downloads.count { it.endsWith(".m3u") })
        assertEquals(1, p.channels(cats[1].id, force = true).size)
        assertEquals(2, downloads.count { it.endsWith(".m3u") })
    }

    @Test fun `a resolve playlist opens through the plugin's resolve of the entry url`() = runBlocking {
        val (p, _) = custom("#EXTM3U\n#EXTINF:-1 tvg-id=\"x.co\",Equis\nhttps://live.example.com/x.m3u8?token=1\n", null,
            """{"url":"https://lists.example.com/t.m3u","format":"m3u","resolve":true}""")
        val c = p.channels(p.categories(false).single().id).single()
        val opening = p.open(c) as LiveOpening.Plugin
        assertNull(opening.channel.direct)
        assertEquals(PluginRef("demo", c.code, PluginRef.LIVE, "https://live.example.com/x.m3u8?token=1"), PluginRef.decode(opening.channel.ref))
    }

    @Test fun `a channel known only by its code opens after a restart`() = runBlocking {
        val code = provider().let { p -> p.channels(p.categories(false)[1].id).single().code }
        val bare = LiveChannel(code, code, 0, null, provider = "plugin:demo")
        val opening = provider().open(bare) as LiveOpening.Plugin
        assertEquals("https://live.example.com/c1/index.m3u8", opening.channel.direct!!.url)
        assertEquals("Canal Uno HD", opening.channel.title)
    }

    @Test fun `rtmp and udp lines and undeclared hosts are counted, the rest plays`() = runBlocking {
        val m3u = "#EXTM3U\n#EXTINF:-1,A\nrtmp://live.example.com/a\n#EXTINF:-1,B\nudp://@239.0.0.1:1234\n" +
            "#EXTINF:-1,C\nhttps://evil.example.org/c.m3u8\n#EXTINF:-1,D\nhttps://live.example.com/d.m3u8\n"
        val (p, _) = custom(m3u, null, """{"url":"https://lists.example.com/m.m3u","format":"m3u"}""")
        assertEquals(listOf("D"), p.channels(p.categories(false).single().id).map { it.name })
        assertNull(p.notice.value)
    }

    @Test fun `a list cut mid-download with no saved copy leaves the plugin's own categories`() = runBlocking {
        val p = PluginLiveProvider(plugin, caller, fetcher = { _, _, _ -> throw IOException("unexpected end of stream") },
            cacheDir = tmp.newFolder(), clock = { now }, log = {})
        assertEquals(listOf("Noticias propias"), p.categories(false).map { it.name })
    }

    @Test fun `a guide cut short is still used, and names match when tvg-id is missing`() = runBlocking {
        val xml = File(fixtures, "guide.xml").readText()
        val cut = xml.substring(0, xml.indexOf("<programme start=\"20260927130000 -0500\" stop=\"20260927133000"))
        val m3u = "#EXTM3U\n#EXTINF:-1 tvg-id=\"canal1.co\",Uno\nhttps://live.example.com/1.m3u8\n" +
            "#EXTINF:-1,Canal Uno\nhttps://live.example.com/2.m3u8\n"
        val (p, _) = custom(m3u, cut.toByteArray(), """{"url":"https://lists.example.com/n.m3u","format":"m3u","epg":{"url":"https://lists.example.com/n.xml","format":"xmltv"}}""")
        val chans = p.channels(p.categories(false).single().id)
        val guide = p.guide(chans).first
        assertEquals(listOf("Noticias del mediodía", "Magazín"), guide.getValue(chans[0].liveCode).map { it.title })
        assertEquals(listOf("Noticias del mediodía", "Magazín"), guide.getValue(chans[1].liveCode).map { it.title })
    }

    @Test fun `a missing guide is answered empty and not downloaded again for a while`() = runBlocking {
        val (p, got) = custom("#EXTM3U\n#EXTINF:-1,Uno\nhttps://live.example.com/1.m3u8\n", null,
            """{"url":"https://lists.example.com/g.m3u","format":"m3u","epg":{"url":"https://lists.example.com/g.xml","format":"xmltv"}}""")
        val chans = p.channels(p.categories(false).single().id)
        assertEquals(emptyList<Any>(), p.guide(chans).first.getValue(chans[0].liveCode))
        p.guide(chans)
        assertEquals(1, got.count { it.endsWith(".xml") })
    }

    @Test fun `adult and undeclared entries at the top never cost valid channels`() = runBlocking {
        val m3u = "#EXTM3U\n" +
            (0 until 100).joinToString("") { "#EXTINF:-1 group-title=\"Adultos\",A$it\nhttps://live.example.com/a$it.m3u8\n" } +
            (0 until 100).joinToString("") { "#EXTINF:-1 group-title=\"G\",E$it\nhttps://evil.example.org/$it.m3u8\n" } +
            (0 until 5000).joinToString("") { "#EXTINF:-1 group-title=\"G\",C$it\nhttps://live.example.com/$it.m3u8\n" }
        val (p, _) = custom(m3u, null, """{"url":"https://lists.example.com/big.m3u","format":"m3u"}""")
        assertEquals(5000, p.channels(p.categories(false).single().id).size)
        assertNull(p.notice.value)
    }

    @Test fun `a forced reload whose download fails keeps the channels already listed`() = runBlocking {
        val p = provider()
        val cats = p.categories(false)
        val before = p.channels(cats[1].id)
        failDownloads = true
        assertEquals(before, p.channels(cats[1].id, force = true))
        assertEquals(listOf("Noticias propias", "Noticias", "Infantil"), p.categories(false).map { it.name })
    }

    @Test fun `a playlist the plugin stops declaring has its files deleted`() = runBlocking {
        var answer = """[{"id":"news","title":"Noticias propias"},{"playlist":{"url":"https://lists.example.com/basic.m3u","format":"m3u"}}]"""
        val cache = tmp.newFolder()
        val p = PluginLiveProvider(plugin, PluginCaller { _, _, _, _ -> answer }, fetcher = fetcher, cacheDir = cache, clock = { now }, log = {})
        p.categories(false)
        assertEquals(1, File(cache, "live").list()!!.size)
        answer = """[{"id":"news","title":"Noticias propias"}]"""
        now += PluginLiveProvider.LIST_TTL_MS + 1
        assertEquals(listOf("Noticias propias"), p.categories(false).map { it.name })
        assertEquals(0, File(cache, "live").list()!!.size)
    }
}
