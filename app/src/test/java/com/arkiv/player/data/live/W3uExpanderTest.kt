package com.arkiv.player.data.live

import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Following a W3U list's linked lists, and saving the result as the M3U the En vivo pipeline reads. */
class W3uExpanderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val publicOnly: (String) -> Boolean = { OwnSourceValidator.checkUrl(it) is OwnUrlCheck.Ok }

    private fun station(name: String, url: String = "https://cdn.example.com/${name.lowercase()}.m3u8") = """{"name":"$name","url":"$url"}"""
    private fun w3u(stations: List<String> = emptyList(), links: List<Pair<String, String>> = emptyList()) =
        """{"name":"L","groups":[${links.joinToString(",") { (n, u) -> """{"name":"$n","url":"$u"}""" }}],"stations":[${stations.joinToString(",")}]}"""

    private class Fake(val bodies: Map<String, String>, val slow: Set<String> = emptySet()) : LivePlaylistFetcher {
        val asked = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray {
            asked += url to headers
            if (url in slow) delay(10 * 60_000L)
            val body = bodies[url] ?: throw IOException("404")
            if (body.length > maxBytes) throw PlaylistTooLargeException(maxBytes / (1024 * 1024))
            return body.toByteArray()
        }
    }

    private suspend fun expand(rootText: String, fake: Fake, caps: W3uExpander.Caps = W3uExpander.Caps(), clock: () -> Long = { 0L }) =
        W3uExpander(fake, publicOnly, clock, caps).expand(W3uParser.parse(rootText)!!, "https://root.example.com/l.w3u")

    @Test fun `linked W3U and M3U lists are imported under their group's name`() = runTest {
        val fake = Fake(mapOf(
            "https://a.example.com/dep.w3u" to """{"name":"Dep","groups":[{"name":"Fútbol","stations":[${station("F1")}]}]}""",
            "https://b.example.com/news.m3u" to "#EXTM3U\n#EXTINF:-1 group-title=\"Nac\",N1\n#EXTHTTP:{\"Referer\":\"https://n.example.com/\"}\nhttps://n.example.com/1.m3u8\n#EXTINF:-1,N2\nhttps://n.example.com/2.m3u8\n",
        ))
        val x = expand(w3u(listOf(station("Root")), listOf("Deportes" to "https://a.example.com/dep.w3u", "Noticias" to "https://b.example.com/news.m3u")), fake)
        assertEquals(listOf("Root" to "", "F1" to "Deportes · Fútbol", "N1" to "Noticias · Nac", "N2" to "Noticias"), x.entries.map { it.name to it.group })
        assertEquals(mapOf("Referer" to "https://n.example.com/"), x.entries[2].headers)
        assertEquals(2, x.linksFollowed)
        // Nested lists are fetched with no headers: nothing of the person's own list goes to another host.
        assertTrue(fake.asked.all { it.second.isEmpty() })
    }

    @Test fun `a cycle of lists is fetched once each and stops`() = runTest {
        val fake = Fake(mapOf(
            "https://a.example.com/a.w3u" to w3u(listOf(station("A")), listOf("B" to "https://b.example.com/b.w3u")),
            "https://b.example.com/b.w3u" to w3u(listOf(station("B")), listOf("A" to "https://a.example.com/a.w3u", "Raíz" to "https://root.example.com/l.w3u")),
        ))
        val x = expand(w3u(links = listOf("A" to "https://a.example.com/a.w3u")), fake, W3uExpander.Caps(maxDepth = 5))
        assertEquals(listOf("A", "B"), x.entries.map { it.name })
        assertEquals(listOf("https://a.example.com/a.w3u", "https://b.example.com/b.w3u"), fake.asked.map { it.first })
    }

    @Test fun `links are followed at most two levels deep`() = runTest {
        val fake = Fake(mapOf(
            "https://x.example.com/1.w3u" to w3u(listOf(station("Uno")), listOf("2" to "https://x.example.com/2.w3u")),
            "https://x.example.com/2.w3u" to w3u(listOf(station("Dos")), listOf("3" to "https://x.example.com/3.w3u")),
            "https://x.example.com/3.w3u" to w3u(listOf(station("Tres"))),
        ))
        val x = expand(w3u(links = listOf("1" to "https://x.example.com/1.w3u")), fake)
        assertEquals(listOf("Uno", "Dos"), x.entries.map { it.name })
        assertFalse(fake.asked.any { it.first.endsWith("3.w3u") })
    }

    @Test fun `a link or a station into the home network, found inside a downloaded list, is never used`() = runTest {
        val fake = Fake(mapOf(
            "https://a.example.com/a.w3u" to w3u(listOf(station("Lan", "http://192.168.1.1/x.m3u8"), station("Ok"))),
        ))
        val x = expand(w3u(links = listOf("Router" to "http://192.168.0.1/admin.w3u", "Local" to "http://localhost:8080/l.m3u", "A" to "https://a.example.com/a.w3u")), fake)
        assertFalse(fake.asked.any { "192.168" in it.first || "localhost" in it.first })
        assertEquals(listOf("Ok"), x.entries.map { it.name })
        assertEquals(2, x.linksRefused)
    }

    @Test fun `the number of linked lists is capped`() = runTest {
        val links = (1..30).map { "G$it" to "https://x.example.com/$it.m3u" }
        val fake = Fake(links.associate { (_, u) -> u to "#EXTM3U\n#EXTINF:-1,C\nhttps://cdn.example.com/c.m3u8\n" })
        val x = expand(w3u(links = links), fake, W3uExpander.Caps(maxLinks = 5))
        assertEquals(5, fake.asked.size)
        assertEquals(5, x.linksFollowed)
    }

    @Test fun `linked lists share one byte budget, and each has its own cap`() = runTest {
        val big = "#EXTM3U\n" + "#EXTINF:-1,C\nhttps://cdn.example.com/c.m3u8\n".repeat(100)
        val fake = Fake(mapOf("https://x.example.com/1.m3u" to big, "https://x.example.com/2.m3u" to big, "https://x.example.com/3.m3u" to big))
        val x = expand(
            w3u(links = listOf("1" to "https://x.example.com/1.m3u", "2" to "https://x.example.com/2.m3u", "3" to "https://x.example.com/3.m3u")),
            fake, W3uExpander.Caps(perListBytes = big.length.toLong(), totalBytes = big.length * 2L + 10),
        )
        assertEquals(2, x.linksFollowed)
        assertEquals(1, x.linksFailed)
    }

    @Test fun `a linked list that never answers times out and the rest still load`() = runTest {
        val fake = Fake(
            mapOf("https://slow.example.com/s.m3u" to "#EXTM3U\n", "https://ok.example.com/o.m3u" to "#EXTM3U\n#EXTINF:-1,Ok\nhttps://cdn.example.com/o.m3u8\n"),
            slow = setOf("https://slow.example.com/s.m3u"),
        )
        val x = expand(w3u(links = listOf("S" to "https://slow.example.com/s.m3u", "O" to "https://ok.example.com/o.m3u")), fake)
        assertEquals(listOf("Ok"), x.entries.map { it.name })
        assertEquals(1, x.linksFailed)
    }

    @Test fun `past the total time no more lists are fetched`() = runTest {
        var now = 0L
        val fake = object : LivePlaylistFetcher {
            val asked = mutableListOf<String>()
            override suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray {
                asked += url
                now += 45_000L
                return "#EXTM3U\n#EXTINF:-1,C\nhttps://cdn.example.com/c.m3u8\n".toByteArray()
            }
        }
        val links = (1..5).map { "G$it" to "https://x.example.com/$it.m3u" }
        W3uExpander(fake, publicOnly, { now }).expand(W3uParser.parse(w3u(links = links))!!, "https://root.example.com/l.w3u")
        assertEquals(2, fake.asked.size)
    }

    @Test fun `the channel cap holds across linked lists`() = runTest {
        val m3u = "#EXTM3U\n" + (1..10).joinToString("") { "#EXTINF:-1,C$it\nhttps://cdn.example.com/$it.m3u8\n" }
        val fake = Fake(mapOf("https://x.example.com/1.m3u" to m3u, "https://x.example.com/2.m3u" to m3u))
        val x = expand(w3u(listOf(station("R")), listOf("1" to "https://x.example.com/1.m3u", "2" to "https://x.example.com/2.m3u")), fake, W3uExpander.Caps(maxEntries = 12))
        assertEquals(12, x.entries.size)
    }

    @Test fun `the saved M3U reads back as the same channels and guides`() {
        val entries = listOf(
            M3uEntry(name = "Canal, con \"comillas\"", url = "https://a.example.com/1.m3u8", tvgId = "c1", logo = "https://img.example.com/1.png",
                group = "Noticias \"N\"", headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://a.example.com/")),
            M3uEntry(name = "Simple", url = "http://b.example.com/2.ts"),
        )
        val file = File(tmp.root, "out.m3u")
        file.bufferedWriter().use { M3uWriter.write(entries, listOf("https://epg.example.com/g.xml.gz"), it) }
        val back = M3uParser.parse(file)
        assertEquals(listOf("https://epg.example.com/g.xml.gz"), back.epgUrls)
        assertEquals(2, back.total)
        val a = back.entries[0]
        assertEquals("Canal, con \"comillas\"", a.name)
        assertEquals("Noticias 'N'", a.group)
        assertEquals("c1", a.tvgId)
        assertEquals("https://img.example.com/1.png", a.logo)
        assertEquals(entries[0].headers, a.headers)
        assertEquals(entries[1].copy(), back.entries[1])
    }

    @Test fun `the fetcher turns a downloaded W3U into the M3U saved on disk, and leaves an M3U alone`() = runTest {
        val root = w3u(listOf(station("Uno")), listOf("Más" to "https://x.example.com/mas.m3u"))
        val inner = Fake(mapOf(
            "https://root.example.com/l.w3u" to "﻿" + root,
            "https://x.example.com/mas.m3u" to "#EXTM3U\n#EXTINF:-1,Dos\nhttps://cdn.example.com/dos.m3u8\n",
            "https://root.example.com/l.m3u" to "#EXTM3U\n#EXTINF:-1,Tres\nhttps://cdn.example.com/tres.m3u8\n",
        ))
        val f = W3uPlaylistFetcher(inner, publicOnly)
        val out = File(tmp.root, "l.m3u")
        f.fetchTo("https://root.example.com/l.w3u", mapOf("User-Agent" to "Mio"), 1_000_000, out)
        assertEquals(listOf("Uno", "Dos"), M3uParser.parse(out).entries.map { it.name })
        f.fetchTo("https://root.example.com/l.m3u", emptyMap(), 1_000_000, out)
        assertEquals(listOf("Tres"), M3uParser.parse(out).entries.map { it.name })
    }

    @Test fun `JSON that is not a W3U list is a failed download, the file deleted`() = runTest {
        val f = W3uPlaylistFetcher(Fake(mapOf("https://root.example.com/x.json" to """{"foo":[1]}""")), publicOnly)
        val out = File(tmp.root, "x.m3u")
        val failed = runCatching { f.fetchTo("https://root.example.com/x.json", emptyMap(), 1_000_000, out) }.isFailure
        assertTrue(failed)
        assertFalse(out.exists())
    }

    @Test fun `a W3U over its own size cap is refused before it is parsed`() = runTest {
        val huge = w3u((1..20).map { station("C$it") })
        val f = W3uPlaylistFetcher(Fake(mapOf("https://root.example.com/l.w3u" to huge)), publicOnly, maxW3uBytes = 100)
        val out = File(tmp.root, "l.m3u")
        assertTrue(runCatching { f.fetchTo("https://root.example.com/l.w3u", emptyMap(), 1_000_000, out) }.exceptionOrNull() is PlaylistTooLargeException)
        assertFalse(out.exists())
    }

    @Test fun `deep JSON nesting is a failed download, not a crash`() = runTest {
        val f = W3uPlaylistFetcher(Fake(mapOf("https://root.example.com/l.w3u" to "[".repeat(500_000))), publicOnly)
        val out = File(tmp.root, "l.m3u")
        assertTrue(runCatching { f.fetchTo("https://root.example.com/l.w3u", emptyMap(), 1_000_000, out) }.isFailure)
        assertFalse(out.exists())
    }
}
