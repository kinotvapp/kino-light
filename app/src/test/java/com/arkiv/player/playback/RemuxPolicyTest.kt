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
        assertTrue(RemuxPolicy.toDelete(f).isEmpty())
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
        assertEquals(listOf("old.mp4", "middle.mp4"), RemuxPolicy.toDelete(f))
    }

    /** Only as many as needed: evicting more than the excess throws away work for nothing. */
    @Test
    fun `it stops as soon as it fits`() {
        val f = listOf(
            Triple("old.mp4", 2 * GB, 1000L),
            Triple("middle.mp4", 2 * GB, 2000L),
            Triple("new.mp4", 2 * GB, 3000L),
        )
        assertEquals(listOf("old.mp4"), RemuxPolicy.toDelete(f))
    }

    /** What is about to be written counts too, or the ceiling is only respected after busting it. */
    @Test
    fun `what is about to be written counts against the ceiling`() {
        val f = listOf(Triple("a.mp4", 3 * GB, 1L))
        assertTrue(RemuxPolicy.toDelete(f).isEmpty())
        assertEquals(listOf("a.mp4"), RemuxPolicy.toDelete(f, incomingBytes = 2 * GB))
    }

    @Test
    fun `an empty cache needs no eviction`() {
        assertTrue(RemuxPolicy.toDelete(emptyList(), incomingBytes = GB).isEmpty())
    }
}
