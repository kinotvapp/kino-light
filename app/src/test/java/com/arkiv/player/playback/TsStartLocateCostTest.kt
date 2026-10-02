package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What locating a start point costs against a slow CDN, measured on [SyntheticTs] (a 912 MB,
 * 1h45m HEVC+AAC title of very uneven bitrate, the size of the measured Xuper one) through a fake
 * ranged reader that counts every read and which ones were in flight together. A round of reads
 * costs [RTT_MS] (the CDN's measured 1-3 s to the first byte) plus its longest body at
 * [BYTES_PER_MS]: that is the simulated time. The 0.9.45 locator ([LegacyTsStartLocator]) is the
 * yardstick: the same keyframes, in fewer reads and far fewer round trips.
 */
class TsStartLocateCostTest {

    @get:Rule val tmp = TemporaryFolder()

    private val ts = SyntheticTs()

    /** Grid points through the title, plus the measured cast (36:11 → grid 35:00) and a few off-grid. */
    private val points = (1..20).map { it * 5 * 60_000L } + listOf(2_100_000L, 2_177_000L, 1_234_000L, 3_333_000L)

    /**
     * A CDN behind [ts]: each read waits a little for real, so reads issued together overlap and are
     * told apart as one round from reads issued one after another.
     */
    private class Cdn(private val ts: SyntheticTs) {
        private val lock = Object()
        private var inFlight = 0
        /** The rounds of reads: each the sizes of the reads that were in flight together. */
        val rounds = ArrayList<MutableList<Int>>()
        var requests = 0
        var bytes = 0L

        val read: (Long, Int) -> ByteArray? = { offset, size ->
            synchronized(lock) {
                requests++
                bytes += size
                if (inFlight == 0) rounds += ArrayList<Int>()
                rounds.last() += size
                inFlight++
            }
            try {
                Thread.sleep(OVERLAP_MS)
                ts.read(offset, size)
            } finally {
                synchronized(lock) { inFlight-- }
            }
        }

        /** The time this would have taken on the slow CDN, in ms. */
        fun simulatedMs(): Long = rounds.sumOf { RTT_MS + it.max() / BYTES_PER_MS }
    }

    private class Run(val start: TsStart?, val cdn: Cdn)

    private fun legacy(ms: Long) = Cdn(ts).let { Run(LegacyTsStartLocator(ts.size, it.read).locate(ms), it) }

    private fun current(ms: Long, durationMs: Long = 0L, known: TsStartIndex? = null, on: SyntheticTs = ts): Pair<Run, TsStartLocator> {
        val cdn = Cdn(on)
        val locator = TsStartLocator(on.size, cdn.read, known = known, durationHintMs = durationMs)
        return Run(locator.locate(ms), cdn) to locator
    }

    @Test
    fun `fewer reads and round trips than 0_9_45, landing on the same keyframes`() {
        var legacyRequests = 0
        var legacyMs = 0L
        val totals = LongArray(2)
        val requests = IntArray(2)
        for (ms in points) {
            val old = legacy(ms)
            val expected = ts.keyframeAtOrAfter(ms)!!
            assertEquals("0.9.45 at $ms", expected.first, old.start!!.byteOffset)
            legacyRequests += old.cdn.requests
            legacyMs += old.cdn.simulatedMs()
            // Without the title's duration, then with it (the first probe goes out with the head and the tail).
            listOf(0L, ts.durationMs).forEachIndexed { k, duration ->
                val (now, locator) = current(ms, duration)
                assertEquals("at $ms (duration $duration)", expected.first, now.start!!.byteOffset)
                assertEquals(expected.second, now.start.startMs)
                assertEquals(locator.stats.requests, now.cdn.requests)
                totals[k] += now.cdn.simulatedMs()
                requests[k] += now.cdn.requests
                println(
                    "$ms: 0.9.45 ${old.cdn.requests} reads/${old.cdn.rounds.size} rounds ${old.cdn.simulatedMs()}ms" +
                        " → ${now.cdn.requests} reads/${now.cdn.rounds.size} rounds ${now.cdn.simulatedMs()}ms (duration $duration; ${locator.stats})",
                )
            }
        }
        val n = points.size
        println(
            "per locate: 0.9.45 ${legacyRequests.toDouble() / n} reads ${legacyMs / n}ms (+1 size read on the device);" +
                " now ${requests[0].toDouble() / n} reads ${totals[0] / n}ms, with the duration ${requests[1].toDouble() / n} reads ${totals[1] / n}ms",
        )
        assertTrue("reads: $legacyRequests → ${requests.toList()}", requests.all { it < legacyRequests })
        assertTrue("${legacyMs / n} → ${totals[0] / n} ms", totals[0] < legacyMs * 80 / 100)
        assertTrue("${legacyMs / n} → ${totals[1] / n} ms", totals[1] < legacyMs * 60 / 100)
    }

    @Test
    fun `a title of even bitrate lands in about three round trips`() {
        val even = SyntheticTs(seed = 11L, sceneLevels = 0.7..1.4)
        var rounds = 0
        var simulated = 0L
        for (ms in points) {
            val (now, _) = current(ms, even.durationMs, on = even)
            assertEquals("at $ms", even.keyframeAtOrAfter(ms)!!.first, now.start!!.byteOffset)
            rounds += now.cdn.rounds.size
            simulated += now.cdn.simulatedMs()
        }
        println("even bitrate: ${rounds.toDouble() / points.size} rounds, ${simulated / points.size}ms per locate")
        assertTrue("$rounds rounds", rounds <= points.size * 3.2)
    }

    @Test
    fun `the head and the tail go out together`() {
        val (run, _) = current(2_100_000L)
        assertEquals(2, run.cdn.rounds.first().size)
        val (hinted, _) = current(2_100_000L, ts.durationMs)
        // The first probes with them when the duration is known.
        assertEquals(2 + TsStartLocator.EARLY_PROBES, hinted.cdn.rounds.first().size)
    }

    @Test
    fun `the title's index answers a point found before with no read, and another with fewer`() {
        val (first, locator) = current(2_100_000L)
        val index = locator.index!!
        // Again (a re-cast, another audio): not a single read, the same keyframe.
        val (again, cached) = current(2_100_000L, known = index)
        assertEquals(0, again.cdn.requests)
        assertTrue(cached.stats.cached)
        assertEquals(first.start!!.byteOffset, again.start!!.byteOffset)
        assertEquals(first.start.startMs, again.start.startMs)
        assertEquals(first.start.tables.toList(), again.start.tables.toList())
        // Another point of the same title: no head, no tail, and the probes start from what was dated.
        for (ms in listOf(2_400_000L, 1_800_000L, 2_700_000L)) {
            val (cold, _) = current(ms)
            val (warm, _) = current(ms, known = index)
            assertEquals(cold.start!!.byteOffset, warm.start!!.byteOffset)
            assertEquals(legacy(ms).start!!.byteOffset, warm.start.byteOffset)
            assertTrue("$ms: ${cold.cdn.requests} → ${warm.cdn.requests}", warm.cdn.requests < cold.cdn.requests)
        }
    }

    @Test
    fun `an index of another file is not used`() {
        val (_, locator) = current(2_100_000L)
        val other = SyntheticTs(seed = 3L)
        val (run, _) = current(2_100_000L, known = locator.index, on = other)
        assertEquals(other.keyframeAtOrAfter(2_100_000L)!!.first, run.start!!.byteOffset)
        assertTrue(run.cdn.requests > 0)
    }

    @Test
    fun `the index is kept on disk and read back by the next process`() {
        val (_, locator) = current(2_100_000L)
        val index = current(2_400_000L, known = locator.index).second.index!!
        val folder = File(tmp.root, "remux")
        TsStartIndexStore(folder).put("cdn://title", index)
        val file = TsStartIndexStore(folder).fileFor("cdn://title")
        assertTrue(file.isFile)
        assertTrue("${file.length()}B", file.length() < 16 * 1024)
        // A new process: nothing in memory, the file answers.
        val read = TsStartIndexStore(folder).get("cdn://title")!!
        assertEquals(index.total, read.total)
        assertEquals(index.samples, read.samples)
        assertEquals(index.end, read.end)
        assertEquals(index.starts, read.starts)
        assertEquals(index.head.tables.toList(), read.head.tables.toList())
        for (ms in listOf(2_100_000L, 2_400_000L)) {
            val (run, _) = current(ms, known = read)
            assertEquals(0, run.cdn.requests)
            assertEquals(ts.keyframeAtOrAfter(ms)!!.first, run.start!!.byteOffset)
        }
        // Its name does not carry the key; another key is another file.
        assertTrue("title" !in file.name)
        assertNull(TsStartIndexStore(folder).get("cdn://other"))
        // Stale (a month on) or garbled: not trusted.
        val later = System.currentTimeMillis() + TsStartIndexStore.MAX_AGE_MS + 60_000L
        assertNull(TsStartIndexStore(folder, now = { later }).get("cdn://title"))
        file.writeBytes(ByteArray(40) { 7 })
        assertNull(TsStartIndexStore(folder).get("cdn://title"))
    }

    @Test
    fun `a failed read finds nothing but keeps what was learned`() {
        var reads = 0
        val failing = TsStartLocator(ts.size, { offset, size -> if (++reads > 2) null else ts.read(offset, size) })
        assertNull(failing.locate(2_100_000L))
        // The head and the tail were read: the next try does not read them again.
        assertNotNull(failing.index)
    }

    private companion object {
        /** Real wait per read, so that reads issued together are seen in flight together. */
        const val OVERLAP_MS = 25L
        /** The CDN's round trip to the first byte, behind the phone's proxy. */
        const val RTT_MS = 2_000L
        /** And then 2 MB/s. */
        const val BYTES_PER_MS = 2_000
    }
}
