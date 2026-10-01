package com.arkiv.player.playback

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/** The wait before a growing remux goes to a TV, shared by the Chromecast and DLNA. */
class RemuxCastStartTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = RemuxHlsServer(lanIp = { "127.0.0.1" })

    @After fun tearDown() = server.stop()

    /** The whole fixture (8 s: fragments of 3, 3 and 2 s) as a remux still being written. */
    private fun remuxOnDisk(): File {
        val source = Fmp4Fixture.copyTo(tmp.newFolder())
        return File(tmp.root, "x.mp4.part").also { it.writeBytes(source.readBytes()) }
    }

    @Test
    fun `it starts where the phone is once the remux covers it with the lead`() = runBlocking {
        val part = remuxOnDisk()
        var got: RemuxCastStart.Start? = null
        val took = RemuxCastStart.await(
            server, "key", wantedMs = 0L,
            inProgress = { part to false }, leftover = { null }, stillWanted = { true },
            leadSec = 5.0, maxTicks = 3, tickMs = 0L,
        ) { got = it; true }
        assertTrue(took)
        val start = assertNotNull(got).let { got!! }
        assertEquals(0L, start.fromMs)
        // Six seconds are playable (two whole fragments make one segment while it grows).
        assertEquals(6.0, start.readySec, 0.001)
        assertTrue(start.url!!.endsWith("/master.m3u8"))
        assertTrue(server.isServing("key"))
    }

    @Test
    fun `it keeps waiting while the remux does not cover the position, and stops when no longer wanted`() = runBlocking {
        val part = remuxOnDisk()
        var offered = 0
        var ticks = 0
        val took = RemuxCastStart.await(
            server, "key", wantedMs = 60_000L,
            inProgress = { part to false }, leftover = { null },
            stillWanted = { ++ticks <= 2 },
            leadSec = 5.0, maxTicks = 10, tickMs = 0L,
        ) { offered++; true }
        assertFalse(took)
        assertEquals(0, offered)
    }

    @Test
    fun `nothing on disk yet is nothing served`() = runBlocking {
        val took = RemuxCastStart.await(
            server, "key", wantedMs = 0L,
            inProgress = { null }, leftover = { null }, stillWanted = { true },
            maxTicks = 3, tickMs = 0L,
        ) { true }
        assertFalse(took)
        assertFalse(server.isServing("key"))
    }

    @Test
    fun `a start the caller cannot use yet keeps the wait going`() = runBlocking {
        val part = remuxOnDisk()
        var offers = 0
        val took = RemuxCastStart.await(
            server, "key", wantedMs = 0L,
            inProgress = { part to false }, leftover = { null }, stillWanted = { true },
            leadSec = 5.0, maxTicks = 5, tickMs = 0L,
        ) { ++offers == 3 }
        assertTrue(took)
        assertEquals(3, offers)
    }

    @Test
    fun `a caster that passes a listener hears of every request with its bytes`() = runBlocking {
        val part = remuxOnDisk()
        val started = Collections.synchronizedList(mutableListOf<String>())
        val finished = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val lan = object : LanRequestListener {
            override fun started(remote: String?, requestLine: String, range: String?, userAgent: String?) {
                started += requestLine
            }

            override fun finished(remote: String?, requestLine: String, bytes: Long, ms: Long, failure: String?) {
                finished += requestLine to bytes
            }
        }
        var url: String? = null
        RemuxCastStart.await(
            server, "key", 0L, inProgress = { part to false }, leftover = { null }, stillWanted = { true },
            lan = lan, diagPrefix = "dlna ", leadSec = 5.0, maxTicks = 2, tickMs = 0L,
        ) { url = it.url; true }
        val c = URL(url!!.replace("master.m3u8", "init.mp4")).openConnection() as HttpURLConnection
        val body = c.inputStream.readBytes()
        // The listener runs on the server's thread once the answer is out.
        repeat(50) { if (finished.isEmpty()) Thread.sleep(20) }
        assertEquals(1, started.size)
        assertTrue(started[0].startsWith("GET /r/"))
        assertEquals(body.size.toLong(), finished.single().second)
    }
}
