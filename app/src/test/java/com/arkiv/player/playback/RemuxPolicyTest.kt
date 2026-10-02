package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions around remuxing, pinned here because both mistakes are expensive: remuxing what
 * did not need it burns minutes and a gigabyte of the user's phone, and skipping what did sends
 * the TV a container it refuses.
 */
class RemuxPolicyTest {

    @Test
    fun `every audio of a title starting at the same point shares one start key`() {
        val cdn = "https://cdn.example/x_media.ts"
        val a0 = RemuxPolicy.keyFrom(cdn, 2_100_000L, 0)
        val a1 = RemuxPolicy.keyFrom(cdn, 2_100_000L, 1)
        assertNotEquals(a0, a1)
        assertEquals(RemuxPolicy.keyFrom(cdn, 2_100_000L), RemuxPolicy.startKey(a0))
        assertEquals(RemuxPolicy.startKey(a0), RemuxPolicy.startKey(a1))
        assertEquals(2_100_000L, RemuxPolicy.fromInKey(a1))
        // Another start point is another key; from the top there is no start point at all.
        assertNotEquals(RemuxPolicy.startKey(a0), RemuxPolicy.startKey(RemuxPolicy.keyFrom(cdn, 1_800_000L, 0)))
        assertEquals(cdn, RemuxPolicy.startKey(RemuxPolicy.keyFrom(cdn, 0L, 1)))
        assertEquals(0L, RemuxPolicy.fromInKey(RemuxPolicy.keyFrom(cdn, 0L, 1)))
    }

    @Test
    fun `only an MPEG-TS needs remuxing`() {
        assertTrue(RemuxPolicy.needsRemux("video/mp2t"))
    }

    /**
     * An mp4 is already what the receiver wants. Remuxing one would spend the whole cost to
     * produce the same file -- and Magis serves plenty of them (`MagisResolve` picks `_media.mp4`
     * whenever the portal's `videoFormat` is not `ts`).
     */
    @Test
    fun `what the receiver already accepts is left alone`() {
        assertFalse(RemuxPolicy.needsRemux("video/mp4"))
        assertFalse(RemuxPolicy.needsRemux("video/webm"))
    }

    /** Unknown means leave it alone: the segmenter still covers it, and a failed remux is worse. */
    @Test
    fun `an unknown container is not remuxed on a guess`() {
        assertFalse(RemuxPolicy.needsRemux(null))
        assertFalse(RemuxPolicy.needsRemux(""))
        assertFalse(RemuxPolicy.needsRemux("application/octet-stream"))
    }

    @Test
    fun `the same origin always maps to the same file`() {
        val a = RemuxPolicy.fileName("http://cdn/vod/ABC_media.ts")
        val b = RemuxPolicy.fileName("http://cdn/vod/ABC_media.ts")
        assertEquals(a, b)
        assertTrue(a.endsWith(".mp4"))
    }

    @Test
    fun `different origins do not collide`() {
        assertNotEquals(
            RemuxPolicy.fileName("http://cdn/vod/ABC_media.ts"),
            RemuxPolicy.fileName("http://cdn/vod/DEF_media.ts"),
        )
    }

    /**
     * The auth blob rides in the proxy url's query, and a filename is not a place for credentials:
     * it ends up in logs, in `ls`, and in any crash report that lists open files.
     */
    @Test
    fun `the file name never carries the origin verbatim`() {
        val name = RemuxPolicy.fileName("http://127.0.0.1:41234/s?h=Q29udGVudC1BdXRo&u=x")
        assertFalse(name.contains("Q29udGVudC1BdXRo"))
        assertFalse(name.contains("127.0.0.1"))
        assertTrue("expected <hex>.mp4, got $name", Regex("^[0-9a-f]+\\.mp4$").matches(name))
    }

    // --- when it is safe to hand the receiver a remux still being written ---

    /**
     * A remux that has only just begun is not castable: the receiver drains it in seconds and
     * stalls, which looks exactly like the bug this whole effort is fixing.
     */
    @Test
    fun `a remux that just started is not castable yet`() {
        // 1 MB of a 900 MB, two-hour title: a couple of seconds of content.
        assertFalse(RemuxPolicy.canStart(1_000_000, 900_000_000, 7_200_000))
    }

    @Test
    fun `enough finished content is castable`() {
        // 10% of a two-hour title is 12 minutes: far past the floor.
        assertTrue(RemuxPolicy.canStart(90_000_000, 900_000_000, 7_200_000))
    }

    /**
     * Resuming matters: 40 s of remux is plenty from the start and useless when the person is
     * resuming at minute 62, because everything before that point is content they are skipping.
     */
    @Test
    fun `resuming needs the remux to have reached that point`() {
        val total = 900_000_000L
        val durMs = 7_200_000L
        // 10% done = 12 minutes of content.
        assertTrue(RemuxPolicy.canStart(90_000_000, total, durMs, positionMs = 60_000))
        assertFalse(RemuxPolicy.canStart(90_000_000, total, durMs, positionMs = 3_720_000))
    }

    /** Nothing known yet is not an invitation to guess. */
    @Test
    fun `without a size or a duration the answer is no`() {
        assertFalse(RemuxPolicy.canStart(0, 900_000_000, 7_200_000))
        assertFalse(RemuxPolicy.canStart(90_000_000, 0, 7_200_000))
        assertFalse(RemuxPolicy.canStart(90_000_000, 900_000_000, 0))
    }

    // --- keeping the cache from eating the phone ---

    private val GB = 1024L * 1024 * 1024

    @Test
    fun `under the ceiling nothing is dropped`() {
        val f = listOf(Triple("a.mp4", GB, 1L), Triple("b.mp4", GB, 2L))
        assertTrue(RemuxPolicy.toDelete(f, cap = 4 * GB).isEmpty())
    }

    /** Oldest first. Backwards would evict what is playing and keep what nobody has opened. */
    @Test
    fun `the oldest goes first`() {
        // 3 GB over a 4 GB ceiling, in 1.5 GB files: two have to go, and they are the two oldest.
        val f = listOf(
            Triple("new.mp4", 3 * GB / 2, 4000L),
            Triple("old.mp4", 3 * GB / 2, 1000L),
            Triple("middle.mp4", 3 * GB / 2, 2000L),
            Triple("recent.mp4", 3 * GB / 2, 3000L),
        )
        assertEquals(listOf("old.mp4", "middle.mp4"), RemuxPolicy.toDelete(f, cap = 4 * GB))
    }

    /** Only as many as needed: evicting more than the excess throws away work for nothing. */
    @Test
    fun `it stops as soon as it fits`() {
        val f = listOf(
            Triple("old.mp4", 2 * GB, 1000L),
            Triple("middle.mp4", 2 * GB, 2000L),
            Triple("new.mp4", 2 * GB, 3000L),
        )
        assertEquals(listOf("old.mp4"), RemuxPolicy.toDelete(f, cap = 4 * GB))
    }

    /** What is about to be written counts too, or the ceiling is only respected after busting it. */
    @Test
    fun `what is about to be written counts against the ceiling`() {
        val f = listOf(Triple("a.mp4", 3 * GB, 1L))
        assertTrue(RemuxPolicy.toDelete(f, cap = 4 * GB).isEmpty())
        assertEquals(listOf("a.mp4"), RemuxPolicy.toDelete(f, incomingBytes = 2 * GB, cap = 4 * GB))
    }

    /** ERRORES-ALJ: 2743 MB of remuxes at startup under the old 4 GB cap. */
    @Test
    fun `the ceiling is 1_5 GB, or a quarter of the room the cache could have, whichever is smaller`() {
        assertEquals(1536L * 1024 * 1024, RemuxPolicy.BYTE_CAP)
        assertEquals(RemuxPolicy.BYTE_CAP, RemuxPolicy.capFor(usableBytes = 40 * GB, cacheBytes = 0L))
        assertEquals(GB, RemuxPolicy.capFor(usableBytes = 3 * GB, cacheBytes = GB))
        // The cache itself counts as room: filling it does not shrink the ceiling under it.
        assertEquals(RemuxPolicy.capFor(4 * GB, 0L), RemuxPolicy.capFor(3 * GB, GB))
        assertEquals(0L, RemuxPolicy.capFor(-1L, 0L))
    }

    @Test
    fun `the default ceiling evicts what a 4 GB one kept, oldest first`() {
        val f = listOf(Triple("a.mp4", GB, 1L), Triple("b.mp4", GB, 2L))
        assertEquals(listOf("a.mp4"), RemuxPolicy.toDelete(f))
    }

    /** Never the file being written or served, even when that leaves the cache over its ceiling. */
    @Test
    fun `what is written or served is never evicted`() {
        val key = RemuxPolicy.keyFrom("https://cdn.example/x_media.ts", 0L)
        val name = RemuxPolicy.fileName(key)
        val keep = RemuxPolicy.namesOf(listOf(key))
        assertEquals(setOf(name, "$name.part", "$name.prev"), keep)
        val f = listOf(
            Triple("$name.prev", 2 * GB, 1L),
            Triple("other.mp4", GB, 2L),
            Triple("$name.part", GB, 3L),
        )
        assertEquals(listOf("other.mp4"), RemuxPolicy.toDelete(f, cap = GB, keep = keep))
        assertTrue(RemuxPolicy.toDelete(f.filter { it.first in keep }, cap = GB, keep = keep).isEmpty())
    }

    @Test
    fun `an empty cache needs no eviction`() {
        assertTrue(RemuxPolicy.toDelete(emptyList(), incomingBytes = GB).isEmpty())
    }

    @Test
    fun `a remux that dies at the start on its input or on time is tried again, a bounded number of times`() {
        assertTrue(RemuxPolicy.retryExport(0, 1000, onAir = false)) // asset loader: source error
        assertTrue(RemuxPolicy.retryExport(0, 2001, onAir = false)) // I/O
        assertTrue(RemuxPolicy.retryExport(1, 7002, onAir = false)) // muxing timeout
        assertFalse(RemuxPolicy.retryExport(RemuxPolicy.EXPORT_ATTEMPTS - 1, 2001, onAir = false))
    }

    @Test
    fun `a remux the device or the file cannot do is not retried`() {
        assertFalse(RemuxPolicy.retryExport(0, 3001, onAir = false)) // decoder
        assertFalse(RemuxPolicy.retryExport(0, 4001, onAir = false)) // encoder
        assertFalse(RemuxPolicy.retryExport(0, 7001, onAir = false)) // muxer refused
    }

    @Test
    fun `a remux a TV is playing is not started over, however little it wrote`() {
        assertFalse(RemuxPolicy.retryExport(0, 2001, onAir = true))
        assertFalse(RemuxPolicy.retryExport(0, 7002, onAir = true))
    }

    @Test
    fun `the remux waits for the proxy longer than the proxy waits for the CDN`() {
        val profile = OriginPolicy.Profile.MAGIS_REMUX
        var budget = 0L
        repeat(OriginPolicy.attempts(profile)) { a ->
            budget += OriginPolicy.responseMs(a, profile)
            if (a < OriginPolicy.attempts(profile) - 1) budget += OriginPolicy.waitMs(a, profile)
        }
        assertTrue("budget $budget", RemuxPolicy.INPUT_READ_MS > budget)
    }

    @Test
    fun `the transmux log never says BLOCKED for a missing format or a rounding-level pixel aspect`() {
        assertEquals("unknown, no video format available", RemuxPolicy.transmuxOutlook(null))
        assertEquals("possible (square pixel)", RemuxPolicy.transmuxOutlook(1f))
        // 1280x534 HEVC measured 2026-10-01: remuxed as a copy and played fine.
        assertEquals("possible (square pixel)", RemuxPolicy.transmuxOutlook(1.00125f))
        val anamorphic = RemuxPolicy.transmuxOutlook(4f / 3f)
        assertTrue(anamorphic, anamorphic.contains("may make it re-encode"))
        assertFalse(anamorphic.contains("BLOCKED"))
    }
}
