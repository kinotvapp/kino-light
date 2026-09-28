package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginPlaylist
import com.arkiv.player.data.plugin.PluginRef
import com.arkiv.player.data.plugin.UserHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistGroupsTest {
    private val provider = "plugin:demo"
    private fun list(vararg lines: String) = M3uParser.parse("#EXTM3U\n" + lines.joinToString("\n"))
    private fun group(
        r: M3uResult, pl: PluginPlaylist = PluginPlaylist("https://l.example.com/a.m3u"), hosts: EffectiveHosts = EffectiveHosts(emptyList()),
        allowed: (String) -> Boolean = { true }, maxCategories: Int = 500, maxChannels: Int = 5000,
    ) = groupPlaylist(r, "k1", provider, "demo", pl, hosts, allowed, maxCategories, maxChannels)

    @Test fun `groups become categories with stable ids, and tvg-ids become codes`() {
        val g = group(list(
            "#EXTINF:-1 tvg-id=\"uno.co\" group-title=\"Noticias\",Uno", "https://l.example.com/1.m3u8",
            "#EXTINF:-1 group-title=\"Noticias\",Dos", "https://l.example.com/2.m3u8",
            "#EXTINF:-1,Tres", "https://l.example.com/3.m3u8",
        ))
        assertEquals(listOf("Noticias", "Sin categoría"), g.categories.map { it.name })
        assertTrue(g.categories.all { it.id.startsWith("pl:k1:") })
        val news = g.byCategory.getValue(g.categories[0].id)
        assertEquals("~k1.uno.co", news[0].code)
        assertTrue(news[1].code.matches(Regex("~k1\\.[0-9a-f]{16}")))
        assertEquals(listOf(provider, provider), news.map { it.provider })
        assertEquals(group(list("#EXTINF:-1 group-title=\"Noticias\",Dos", "https://l.example.com/2.m3u8")).byCategory.values.single().single().code, news[1].code)
    }

    @Test fun `a repeated tvg-id falls back to the hash, so both variants stay`() {
        val g = group(list(
            "#EXTINF:-1 tvg-id=\"uno.co\",Uno HD", "https://l.example.com/hd.m3u8",
            "#EXTINF:-1 tvg-id=\"uno.co\",Uno SD", "https://l.example.com/sd.m3u8",
        ))
        assertEquals(2, g.kept)
        assertEquals(2, g.entries.keys.size)
    }

    @Test fun `a later entry repeating a tvg-id leaves the first one's code alone`() {
        val hd = arrayOf("#EXTINF:-1 tvg-id=\"uno.co\",Uno HD", "https://l.example.com/hd.m3u8")
        val before = group(list(*hd))
        val after = group(list(*hd, "#EXTINF:-1 tvg-id=\"uno.co\",Uno SD", "https://l.example.com/sd.m3u8"))
        assertEquals(listOf("~k1.uno.co"), before.entries.keys.toList())
        assertEquals("~k1.uno.co", after.entries.keys.first())
        assertTrue(after.entries.keys.last().matches(Regex("~k1\\.[0-9a-f]{16}")))
    }

    @Test fun `adult and hidden groups vanish, disallowed urls are skipped`() {
        val pl = PluginPlaylist("https://l.example.com/a.m3u", hideGroups = setOf("compras"))
        val g = group(list(
            "#EXTINF:-1 group-title=\"XXX\",A", "https://l.example.com/a.m3u8",
            "#EXTINF:-1 group-title=\"Compras\",B", "https://l.example.com/b.m3u8",
            "#EXTINF:-1 group-title=\"Ok\",C", "https://bad.example.org/c.m3u8",
            "#EXTINF:-1 group-title=\"Ok\",D", "https://l.example.com/d.m3u8",
        ), pl, allowed = { it.startsWith("https://l.example.com/") })
        assertEquals(listOf("D"), g.byCategory.values.flatten().map { it.name })
        assertEquals(2, g.hidden)
        assertEquals(1, g.skipped)
    }

    @Test fun `caps cut channels and fold extra groups into Otros`() {
        val lines = (0 until 30).flatMap { listOf("#EXTINF:-1 group-title=\"G$it\",C$it", "https://l.example.com/$it.m3u8") }
        val g = group(list(*lines.toTypedArray()), maxCategories = 5, maxChannels = 20)
        assertEquals(5, g.categories.size)
        assertEquals("Otros", g.categories.last().name)
        assertEquals(20, g.kept)
        assertEquals(30, g.total)
    }

    @Test fun `resolve playlists give each channel a ref for the plugin's resolve`() {
        val g = group(list("#EXTINF:-1,Uno", "https://l.example.com/1.m3u8"), PluginPlaylist("https://l.example.com/a.m3u", resolve = true))
        val c = g.byCategory.values.single().single()
        assertEquals(PluginRef("demo", c.code, PluginRef.LIVE, "https://l.example.com/1.m3u8"), PluginRef.decode(c.ref!!))
    }

    @Test fun `a logo on the plugin's typed server keeps it, on any other local host it is dropped, elsewhere it follows the plugin image rule`() {
        val hosts = EffectiveHosts(emptyList(), user = listOf(UserHost("http", "192.168.2.6", 8096)))
        val g = group(
            list(
                "#EXTINF:-1 tvg-logo=\"http://192.168.2.6:8096/img/poster/canal-7.png\",TuServidor", "https://l.example.com/1.m3u8",
                "#EXTINF:-1 tvg-logo=\"http://192.168.2.7:8096/img/poster/canal-8.png\",OtraLan", "https://l.example.com/2.m3u8",
                "#EXTINF:-1 tvg-logo=\"https://cdn.example.com/c.png\",Publico", "https://l.example.com/3.m3u8",
                "#EXTINF:-1 tvg-logo=\"http://cdn.example.com/c.png\",PublicoHttp", "https://l.example.com/4.m3u8",
            ),
            hosts = hosts,
        )
        val channels = g.byCategory.values.flatten().associateBy { it.name }
        assertEquals("http://192.168.2.6:8096/img/poster/canal-7.png", channels.getValue("TuServidor").logo)
        assertEquals(null, channels.getValue("OtraLan").logo)
        assertEquals("https://cdn.example.com/c.png", channels.getValue("Publico").logo)
        // Plain http on an undeclared public host: the plugin image rule requires https there too.
        assertEquals(null, channels.getValue("PublicoHttp").logo)
    }

    @Test fun `what the parse filtered is counted, and a parse cut by its time budget is a cut list`() {
        val r = list("#EXTINF:-1,Uno", "https://l.example.com/1.m3u8").copy(hidden = 3, refused = 2, stoppedEarly = true)
        val g = group(r)
        assertEquals(3, g.hidden)
        assertEquals(2, g.skipped)
        assertTrue(g.cut)
        assertEquals("Lista recortada: 1 de 1 canales", trimNotice(listOf(g)))
        assertEquals(null, trimNotice(listOf(group(list("#EXTINF:-1,Uno", "https://l.example.com/1.m3u8")))))
    }
}
