package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginPlaylist
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A declared playlist's disk copy: token-proof key, orphan cleanup, a size budget, one parse at a time, offline reuse. */
class PlaylistSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = Instant.parse("2026-09-27T16:00:00Z").toEpochMilli()
    private val m3u = "#EXTM3U\n#EXTINF:-1,Uno\nhttps://live.example.com/1.m3u8\n"
    private val downloads = mutableListOf<String>()
    private var fail = false
    private val fetcher = LivePlaylistFetcher { url, _, _ ->
        downloads += url
        if (fail) throw IOException("sin red")
        m3u.toByteArray()
    }
    private fun source(url: String, dir: File = tmp.root, gate: Semaphore = Semaphore(1), budget: Long = PlaylistSource.MAX_LIVE_CACHE_BYTES) =
        PlaylistSource(PluginPlaylist(url), fetcher, dir, { now }, {}, parseGate = gate, cacheBudgetBytes = budget)

    @Test fun `the cache key ignores the query, so a rotating token keeps the saved copy`() = runBlocking {
        val a = source("https://lists.example.com/get.php?username=u&password=p&token=aaa")
        val b = source("https://lists.example.com/get.php?username=u&password=p&token=bbb")
        assertEquals(a.key, b.key)
        assertEquals(PlaylistSource.cacheKey("https://lists.example.com/get.php"), a.key)
        assertTrue(a.key != source("https://lists.example.com/other.php?token=aaa").key)
        a.entries(false)
        now += 3600_000L
        assertEquals(1, b.entries(false)!!.total)
        assertEquals(1, downloads.size)
    }

    @Test fun `files of playlists no longer declared are deleted, temp files too`() {
        val dir = File(tmp.root, "live").apply { mkdirs() }
        listOf("aaaaaaaa.m3u", "aaaaaaaa.epg", "bbbbbbbb.m3u", "bbbbbbbb.epg", "bbbbbbbb.m3u.tmp", "cccccccc.epg.tmp")
            .forEach { File(dir, it).writeText("x") }
        PlaylistSource.pruneLiveDir(tmp.root, keepKeys = setOf("aaaaaaaa"), budgetBytes = PlaylistSource.MAX_LIVE_CACHE_BYTES) {}
        assertEquals(setOf("aaaaaaaa.m3u", "aaaaaaaa.epg"), dir.list()!!.toSet())
    }

    @Test fun `the live dir stays under its budget, oldest downloads go first`() {
        val dir = File(tmp.root, "live").apply { mkdirs() }
        listOf("aaaaaaaa.m3u" to 1000L, "bbbbbbbb.m3u" to 3000L, "cccccccc.m3u" to 2000L).forEach { (n, t) ->
            File(dir, n).apply { writeBytes(ByteArray(400)); setLastModified(t) }
        }
        PlaylistSource.pruneLiveDir(tmp.root, keepKeys = setOf("aaaaaaaa", "bbbbbbbb", "cccccccc"), budgetBytes = 1000) {}
        assertEquals(setOf("bbbbbbbb.m3u", "cccccccc.m3u"), dir.list()!!.toSet())
    }

    @Test fun `a download past the budget evicts older copies`() = runBlocking {
        val dir = File(tmp.root, "live").apply { mkdirs() }
        File(dir, "zzzzzzzz.epg").apply { writeBytes(ByteArray(500)); setLastModified(now - 1000) }
        source("https://lists.example.com/a.m3u", budget = 100).entries(false)
        assertFalse(File(dir, "zzzzzzzz.epg").exists())
        assertEquals(1, dir.list()!!.count { it.endsWith(".m3u") })
    }

    @Test fun `all plugins' live caches stay under one ceiling, the least recently refreshed plugin goes first`() {
        val root = File(tmp.root, "plugin-data")
        fun put(plugin: String, name: String, size: Int, at: Long) =
            File(root, "$plugin/live/$name").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size)); setLastModified(at) }
        // Plugin a refreshed last at 1000, c at 2000, b at 3000: a is the least recently used cache.
        put("a", "aaaaaaaa.m3u", 300, 500)
        put("a", "aaaaaaaa.epg", 300, 1000)
        put("b", "bbbbbbbb.m3u", 300, 3000)
        put("c", "cccccccc.m3u", 300, 100)
        put("c", "cccccccc.epg", 300, 2000)
        put("c", "cccccccc.m3u.tmp", 900, 2500)
        val home = File(root, "a/home.json").apply { writeBytes(ByteArray(5000)) }
        PlaylistSource.pruneAllLiveDirs(root, budgetBytes = 700) {}
        assertFalse(File(root, "a/live/aaaaaaaa.m3u").exists())
        assertFalse(File(root, "a/live/aaaaaaaa.epg").exists())
        // c's older file goes next; then the total (600) fits.
        assertFalse(File(root, "c/live/cccccccc.m3u").exists())
        assertTrue(File(root, "c/live/cccccccc.epg").exists())
        assertTrue(File(root, "b/live/bbbbbbbb.m3u").exists())
        // Temp files being written and anything outside live/ are never evicted for size.
        assertTrue(File(root, "c/live/cccccccc.m3u.tmp").exists())
        assertTrue(home.exists())
    }

    @Test fun `the global ceiling never evicts the copy just written`() {
        val root = File(tmp.root, "plugin-data")
        val old = File(root, "a/live/aaaaaaaa.m3u").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(400)); setLastModified(1000) }
        val fresh = File(root, "b/live/bbbbbbbb.m3u").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(400)); setLastModified(500) }
        PlaylistSource.pruneAllLiveDirs(root, budgetBytes = 100, justWritten = fresh) {}
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test fun `a download past the global ceiling evicts another plugin's copies`() = runBlocking {
        val root = File(tmp.root, "plugin-data")
        val other = File(root, "other/live/zzzzzzzz.epg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(500)); setLastModified(now - 1000) }
        val mine = File(root, "mine")
        PlaylistSource(
            PluginPlaylist("https://lists.example.com/a.m3u"), fetcher, mine, { now }, {},
            parseGate = Semaphore(1), allCachesRoot = root, allCachesBudgetBytes = 100,
        ).entries(false)
        assertFalse(other.exists())
        assertEquals(1, File(mine, "live").list()!!.count { it.endsWith(".m3u") })
    }

    @Test fun `parsing waits for the shared gate`() = runBlocking {
        val gate = Semaphore(1)
        val s = source("https://lists.example.com/a.m3u", gate = gate)
        gate.acquire()
        val job = async { s.entries(false) }
        assertNull(withTimeoutOrNull(300) { job.await() })
        gate.release()
        assertNotNull(job.await())
        assertEquals(1, PlaylistSource.PARSE_GATE.availablePermits)
    }

    @Test fun `offline, the parsed list is kept without parsing the stale copy again`() = runBlocking {
        val s = source("https://lists.example.com/a.m3u")
        val first = s.entries(false)!!
        now += 13 * 3600_000L
        fail = true
        assertSame(first, s.entries(false))
        now += PlaylistSource.RETRY_MS + 1
        assertSame(first, s.entries(false))
        assertEquals(3, downloads.size)
        now += 1
        s.entries(false)
        assertEquals(3, downloads.size)
    }

    @Test fun `no cache dir means no playlist`() = runBlocking {
        assertNull(PlaylistSource(PluginPlaylist("https://lists.example.com/a.m3u"), fetcher, null, { now }, {}).entries(false))
    }

    @Test fun `a list deleted between the encoding sniff and the read keeps the parsed copy, never throws`() = runBlocking {
        val s = source("https://lists.example.com/a.m3u")
        val first = s.entries(false)!!
        now += 13 * 3600_000L
        s.beforeRead = { it.delete() }
        assertSame(first, s.entries(false))
        val cold = source("https://lists.example.com/b.m3u")
        cold.beforeRead = { it.delete() }
        assertNull(cold.entries(false))
    }

    @Test fun `a list holds only the channel budget it was given, and is parsed again only when a cut list gets more`() = runBlocking {
        val big = "#EXTM3U\n" + (0 until 100).joinToString("") { "#EXTINF:-1,C$it\nhttps://live.example.com/$it.m3u8\n" }
        val s = PlaylistSource(PluginPlaylist("https://lists.example.com/big.m3u"), { _, _, _ -> big.toByteArray() }, tmp.root, { now }, {}, parseGate = Semaphore(1))
        var parses = 0
        s.beforeRead = { parses++ }
        val ten = s.entries(false, maxEntries = 10)!!
        assertEquals(listOf(10, 100, 1), listOf(ten.entries.size, ten.total, parses))
        assertSame(ten, s.entries(false, maxEntries = 10))
        // Less budget: trimmed from memory, the file not read again.
        val five = s.entries(false, maxEntries = 5)!!
        assertEquals(listOf(5, 100, 1), listOf(five.entries.size, five.total, parses))
        assertSame(five, s.entries(false, maxEntries = 5))
        // More budget for a list that was cut: read again.
        val fifty = s.entries(false, maxEntries = 50)!!
        assertEquals(listOf(50, 100, 2), listOf(fifty.entries.size, fifty.total, parses))
        // A list that was never cut serves any budget as it is.
        val small = source("https://lists.example.com/a.m3u")
        var smallParses = 0
        small.beforeRead = { smallParses++ }
        val all = small.entries(false, maxEntries = 10)!!
        assertSame(all, small.entries(false, maxEntries = 5000))
        assertEquals(1, smallParses)
    }

    @Test fun `a list being parsed is never evicted for size`() = runBlocking {
        val s = source("https://lists.example.com/a.m3u")
        var survived = false
        s.beforeRead = { f ->
            PlaylistSource.pruneLiveDir(tmp.root, keepKeys = null, budgetBytes = 0) {}
            val afterBudget = f.exists()
            PlaylistSource.pruneLiveDir(tmp.root, keepKeys = emptySet(), budgetBytes = 0) {}
            survived = afterBudget && f.exists()
        }
        assertEquals(1, s.entries(false)!!.total)
        assertTrue(survived)
    }
}
