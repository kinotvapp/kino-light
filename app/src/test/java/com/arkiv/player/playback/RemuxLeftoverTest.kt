package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What an earlier cast's stopped remux becomes when the same title is cast again. */
class RemuxLeftoverTest {

    @get:Rule val tmp = TemporaryFolder()

    private val cdn = "http://yuwc.example.com/vod/B9EF0A4797094DE29B4B6599E723D38E_media.ts"

    private fun files(key: String): Triple<File, File, File> {
        val done = File(tmp.root, RemuxPolicy.fileName(key))
        return Triple(done, File(tmp.root, "${done.name}.part"), RemuxLeftover.fileFor(done))
    }

    @Test
    fun `a stopped remux is kept as the leftover instead of being thrown away`() {
        val key = RemuxPolicy.keyFrom(cdn, 0L, 0)
        val (_, part, leftover) = files(key)
        part.writeBytes(ByteArray(507))
        RemuxLeftover.rotate(part, leftover)
        assertFalse(part.exists())
        assertEquals(507L, leftover.length())
    }

    @Test
    fun `the longer of two stopped runs is the one kept`() {
        val (_, part, leftover) = files(RemuxPolicy.keyFrom(cdn, 0L, 0))
        leftover.writeBytes(ByteArray(507))
        part.writeBytes(ByteArray(216)) // a second run that stopped earlier
        RemuxLeftover.rotate(part, leftover)
        assertFalse(part.exists())
        assertEquals(507L, leftover.length())
        part.writeBytes(ByteArray(900)) // a third that got further than both
        RemuxLeftover.rotate(part, leftover)
        assertEquals(900L, leftover.length())
    }

    @Test
    fun `nothing written, nothing kept`() {
        val (_, part, leftover) = files(RemuxPolicy.keyFrom(cdn, 0L, 0))
        part.writeBytes(ByteArray(0))
        RemuxLeftover.rotate(part, leftover)
        assertFalse(part.exists())
        assertFalse(leftover.exists())
    }

    @Test
    fun `the leftover is only handed out while the title is unfinished`() {
        val (done, _, leftover) = files(RemuxPolicy.keyFrom(cdn, 0L, 0))
        assertNull(RemuxLeftover.usable(done, leftover))
        leftover.writeBytes(ByteArray(10))
        assertEquals(leftover, RemuxLeftover.usable(done, leftover))
        done.writeBytes(ByteArray(20))
        assertNull(RemuxLeftover.usable(done, leftover))
    }

    @Test
    fun `reuse follows the remux key, so another audio or another title never gets it`() {
        val spanish = files(RemuxPolicy.keyFrom(cdn, 0L, 0)).third
        assertEquals(spanish, files(RemuxPolicy.keyFrom(cdn, 0L, 0)).third)
        assertNotEquals(spanish, files(RemuxPolicy.keyFrom(cdn, 0L, 1)).third)
        assertNotEquals(spanish, files(RemuxPolicy.keyFrom(cdn.replace("B9EF", "C0DE"), 0L, 0)).third)
        // A hashed name: nothing of the origin url reaches the file system.
        assertTrue(spanish.name.matches(Regex("[0-9a-f]+\\.mp4\\.prev")))
    }
}
