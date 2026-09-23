package com.arkiv.player.thumbnails

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The JPEG lives on disk and not in the database: stuffing ~97 blobs of 100 KB into SQLite bloats
 * it by 10 MB and makes it slow to read, when all that actually needs saving is where the image is.
 */
class FrameStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store() = FrameStore(temp.newFolder("frames"))

    @Test
    fun `save leaves the file with the content`() {
        val a = store()
        val f = a.save("web:series:tt01/1x03", byteArrayOf(1, 2, 3))
        assertTrue(f.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
    }

    /**
     * episodeIds carry `:` and `/`, which aren't valid in a filename. That's why the name is
     * derived by hash instead of using the raw id.
     */
    @Test
    fun `the filename doesn't carry over the episodeId's characters`() {
        val a = store()
        val f = a.fileFor("web:series:tt01/1x03")
        assertFalse(f.name.contains(":"))
        assertFalse(f.name.contains("/"))
        assertTrue(f.name.endsWith(".jpg"))
    }

    /** The same chapter always maps to the same file: that's what makes it overwrite instead of accumulate. */
    @Test
    fun `saving the same chapter twice overwrites`() {
        val a = store()
        val first = a.save("ep-1", byteArrayOf(1))
        val second = a.save("ep-1", byteArrayOf(2, 2))
        assertEquals(first.absolutePath, second.absolutePath)
        assertArrayEquals(byteArrayOf(2, 2), second.readBytes())
        assertEquals(1, temp.root.walkTopDown().filter { it.extension == "jpg" }.count())
    }

    @Test
    fun `different chapters go to different files`() {
        val a = store()
        assertFalse(a.fileFor("ep-1").absolutePath == a.fileFor("ep-2").absolutePath)
    }

    @Test
    fun `pathIfExists returns null when nothing was saved`() {
        assertNull(store().pathIfExists("ep-1"))
    }

    @Test
    fun `pathIfExists returns the path after saving`() {
        val a = store()
        val f = a.save("ep-1", byteArrayOf(9))
        assertEquals(f.absolutePath, a.pathIfExists("ep-1"))
    }

    @Test
    fun `delete removes the file`() {
        val a = store()
        a.save("ep-1", byteArrayOf(9))
        a.delete("ep-1")
        assertNull(a.pathIfExists("ep-1"))
    }

    /** Deleting something that isn't there can't blow up: happens every time a frameless chapter gets marked watched. */
    @Test
    fun `deleting what doesn't exist does not fail`() {
        store().delete("ep-nonexistent")
    }

    /**
     * The write is temp + rename. What can't happen is the temp file staying in the directory:
     * copies of the same frame that nobody ever deletes would pile up.
     */
    @Test
    fun `save does not leave temp files behind`() {
        val a = store()
        a.save("ep-1", byteArrayOf(1, 2, 3))
        a.save("ep-1", byteArrayOf(4, 5, 6))
        val files = temp.root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, files.size)
        assertTrue(files.single().name.endsWith(".jpg"))
    }

    /**
     * The destination is never touched until the whole JPEG is ready: while it's being written,
     * what's at the chapter's path is the PREVIOUS complete frame, not a half-written one. If
     * `save` wrote directly, a cut mid-write would leave a truncated file that still exists -> the
     * card would end up empty (Coil can't decode it) instead of falling back to the TMDB still.
     */
    @Test
    fun `while writing, the destination still has the previous frame`() {
        val a = store()
        val destination = a.save("ep-1", byteArrayOf(1, 1, 1))
        // A half-written temp file in the directory doesn't change what's at the destination.
        java.io.File(destination.parentFile, "${destination.name}.123.tmp").writeBytes(byteArrayOf(9))
        assertArrayEquals(byteArrayOf(1, 1, 1), java.io.File(a.pathIfExists("ep-1")!!).readBytes())
    }

    /**
     * A temp file left over from an interrupted write doesn't live forever: the next capture of
     * the same chapter takes it. Nobody else would claim it -- per-chapter deletion only looks at
     * the destination's `.jpg`.
     */
    @Test
    fun `save sweeps up temp files left hanging around`() {
        val a = store()
        val destination = a.save("ep-1", byteArrayOf(1))
        java.io.File(destination.parentFile, "${destination.name}.123.tmp").writeBytes(byteArrayOf(9))
        a.save("ep-1", byteArrayOf(2))
        assertEquals(1, temp.root.walkTopDown().filter { it.isFile }.count())
        assertArrayEquals(byteArrayOf(2), destination.readBytes())
    }

    /** Same when deleting a watched chapter's frame: it can't leave the temp file behind. */
    @Test
    fun `delete also takes the chapter's temp files`() {
        val a = store()
        val destination = a.save("ep-1", byteArrayOf(1))
        java.io.File(destination.parentFile, "${destination.name}.123.tmp").writeBytes(byteArrayOf(9))
        a.delete("ep-1")
        assertEquals(0, temp.root.walkTopDown().filter { it.isFile }.count())
    }

    /** The logout wipe: EVERYTHING goes, including temp files from an interrupted write. */
    @Test
    fun `deleteAll empties the directory`() {
        val a = store()
        a.save("ep-1", byteArrayOf(1))
        val destination = a.save("ep-2", byteArrayOf(2))
        java.io.File(destination.parentFile, "${destination.name}.123.tmp").writeBytes(byteArrayOf(9))
        a.deleteAll()
        assertNull(a.pathIfExists("ep-1"))
        assertNull(a.pathIfExists("ep-2"))
        assertEquals(0, temp.root.walkTopDown().filter { it.isFile }.count())
    }

    /** Emptying a directory that wasn't even created yet can't blow up. */
    @Test
    fun `deleteAll without a directory does not fail`() {
        FrameStore(java.io.File(temp.root, "does-not-exist")).deleteAll()
    }
}
