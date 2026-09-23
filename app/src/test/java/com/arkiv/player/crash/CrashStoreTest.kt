package com.arkiv.player.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The disk queue is what makes the report arrive "no matter what": sending the request from the
 * crash handler loses the race against the dying process, so there only a file gets written and
 * the send is left for the next launch.
 */
class CrashStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L

    private fun store(maxPending: Int = 20) =
        CrashStore(dir = tmp.newFolder("crashes-${clock}-${maxPending}"), maxPending = maxPending, now = { clock })

    @Test
    fun `save leaves a pending report with the content intact`() {
        val store = store()

        store.save("""{"mensaje":"se cayo"}""")

        val pending = store.pending()
        assertEquals(1, pending.size)
        assertEquals("""{"mensaje":"se cayo"}""", pending.single().readText())
    }

    @Test
    fun `pending reports come out from oldest to newest`() {
        val store = store()
        store.save("viejo")
        clock = 2_000L
        store.save("nuevo")

        assertEquals(listOf("viejo", "nuevo"), store.pending().map { it.readText() })
    }

    @Test
    fun `two reports in the same millisecond don't overwrite each other`() {
        val store = store()

        store.save("primero")
        store.save("segundo")

        assertEquals(listOf("primero", "segundo"), store.pending().map { it.readText() })
    }

    @Test
    fun `past the cap the oldest one is discarded`() {
        val store = store(maxPending = 2)
        store.save("uno")
        clock = 2_000L
        store.save("dos")
        clock = 3_000L

        store.save("tres")

        assertEquals(listOf("dos", "tres"), store.pending().map { it.readText() })
    }

    @Test
    fun `save creates the folder if it doesn't exist yet`() {
        val store = CrashStore(dir = tmp.root.resolve("sin/crear"), maxPending = 20, now = { clock })

        store.save("uno")

        assertEquals("uno", store.pending().single().readText())
    }

    @Test
    fun `an unrelated file in the folder isn't treated as pending`() {
        val dir = tmp.newFolder("mezclada")
        dir.resolve("basura.txt").writeText("no es un reporte")
        val store = CrashStore(dir = dir, maxPending = 20, now = { clock })

        store.save("uno")

        assertEquals(listOf("uno"), store.pending().map { it.readText() })
        assertTrue(dir.resolve("basura.txt").exists())
    }

    @Test
    fun `pending on a folder that doesn't exist is an empty list`() {
        val store = CrashStore(dir = tmp.root.resolve("nunca/creada"), maxPending = 20, now = { clock })

        assertTrue(store.pending().isEmpty())
        assertFalse(tmp.root.resolve("nunca/creada").exists())
    }

    /**
     * A `.json.tmp` is what a process dead mid-write leaves behind. That fragment must NOT enter
     * the queue: the server would reject it as invalid JSON and the drainer would retry it on
     * every launch forever.
     */
    @Test
    fun `a half-written leftover from an earlier crash doesn't enter the queue`() {
        val dir = tmp.newFolder("a-medias")
        dir.resolve("0000000000999-0000.json.tmp").writeText("""{"mensaje":"corta""")
        val store = CrashStore(dir = dir, maxPending = 20, now = { clock })

        store.save("entero")

        assertEquals(listOf("entero"), store.pending().map { it.readText() })
    }

    /** That the temp file gets renamed and doesn't sit next to the good one taking up disk forever. */
    @Test
    fun `save leaves no temp leftovers`() {
        val dir = tmp.newFolder("sin-restos")
        val store = CrashStore(dir = dir, maxPending = 20, now = { clock })

        store.save("uno")

        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.single().name.endsWith(".json"))
    }
}
