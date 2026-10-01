package com.arkiv.player.data.live

import com.arkiv.player.data.db.OwnLiveSourceEntity
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PastedListFetcherTest {
    @get:Rule val tmp = TemporaryFolder()

    private val m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"Noticias\",Uno\nhttps://tv.example.com/uno.m3u8\n#EXTINF:-1,Casa\nhttp://192.168.1.5/casa.m3u8"
    private val nested = "#EXTM3U\n#EXTINF:-1,Enlazado\nhttps://tv.example.com/enlazado.m3u8"
    private val w3u = """{"name":"L","stations":[{"name":"Uno","url":"https://tv.example.com/uno.m3u8"}],
        "groups":[{"name":"Más","url":"https://lists.example.com/mas.m3u"},{"name":"Casa","url":"http://10.0.0.2/x.m3u"}]}"""

    /** What the network side was asked for, with the headers it got. */
    private val asked = ArrayList<Pair<String, Map<String, String>>>()
    private val network = LivePlaylistFetcher { url, headers, _ ->
        asked += url to headers
        if (url == "https://lists.example.com/mas.m3u") nested.toByteArray() else throw IOException("no route to $url")
    }

    private fun fetcher(contents: Map<String, String>) =
        PastedListFetcher(network) { id -> contents[id] }

    @Test fun `a pasted url is answered with the stored text and never reaches the network`() = runTest {
        val f = fetcher(mapOf("p1" to m3u))
        assertEquals(m3u, String(f.fetch("kino-list:p1", mapOf("User-Agent" to "x"), 10_000_000)))
        val into = tmp.newFile()
        f.fetchTo("kino-list:p1", emptyMap(), 10_000_000, into)
        assertEquals(m3u, into.readText())
        assertTrue(asked.isEmpty())
    }

    @Test fun `a text not here yet fails like a failed download, and an address goes to the network`() = runTest {
        val f = fetcher(emptyMap())
        val into = tmp.newFile()
        try { f.fetchTo("kino-list:p1", emptyMap(), 10_000_000, into); fail() } catch (e: IOException) { assertTrue(!into.exists()) }
        try { f.fetch("kino-list:p1", emptyMap(), 2); fail() } catch (e: IOException) { }
        f.fetch("https://lists.example.com/mas.m3u", emptyMap(), 10)
        assertEquals(listOf("https://lists.example.com/mas.m3u"), asked.map { it.first })
    }

    @Test fun `pasted W3U is expanded like a downloaded one, nested lists only on public hosts and with no headers`() = runTest {
        val f = W3uPlaylistFetcher(fetcher(mapOf("p1" to w3u)), urlAllowed = { OwnSourceValidator.checkUrl(it) is OwnUrlCheck.Ok })
        val into = tmp.newFile()
        f.fetchTo("kino-list:p1", mapOf("User-Agent" to "VLC/3"), 10_000_000, into)
        val names = M3uParser.parse(into.readText()).entries.map { it.name }
        assertEquals(listOf("Uno", "Enlazado"), names)
        assertEquals(listOf("https://lists.example.com/mas.m3u" to emptyMap<String, String>()), asked)   // 10.0.0.2 never asked
    }

    @Test fun `the own provider lists a pasted list, and new text shows at once with the same channel codes`() = runTest {
        var row = OwnLiveSourceEntity("p1", "PLAYLIST", "Pegada", "kino-list:p1", updatedAt = 10, contentDigest = OwnPastedList.digest(m3u))
        var text = m3u
        val p = OwnLiveProvider(
            sources = { listOf(row) }, fetcher = PastedListFetcher(network) { text.takeIf { _ -> it == "p1" } },
            cacheDir = tmp.newFolder(), allCachesRoot = null, clock = { 1_000L }, log = {},
        )
        val first = p.categories(false).flatMap { p.channels(it.id) }
        assertEquals(listOf("Uno"), first.map { it.name })   // the LAN entry is dropped
        text = "$m3u\n#EXTINF:-1 group-title=\"Noticias\",Dos\nhttps://tv.example.com/dos.m3u8"
        row = row.copy(updatedAt = 20, contentDigest = OwnPastedList.digest(text))
        val second = p.categories(false).flatMap { p.channels(it.id) }
        assertEquals(setOf("Uno", "Dos"), second.map { it.name }.toSet())
        assertEquals(first.single().code, second.first { it.name == "Uno" }.code)
    }
}
