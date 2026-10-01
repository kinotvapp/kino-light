package com.arkiv.player.playback

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import android.net.JvmUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger

class PacedDataSourceTest {

    private fun source(gate: () -> Boolean, clock: () -> Long = System::currentTimeMillis) =
        PacedDataSource(ByteArrayDataSource(ByteArray(100) { it.toByte() }), gate, sleepMs = 1, checkEveryMs = 500, clock = clock)
            .apply { open(DataSpec(JvmUri("file:///x"))) }

    @Test
    fun `reads pass straight through while the gate is open`() {
        val ds = source({ false })
        val buf = ByteArray(10)
        assertEquals(10, ds.read(buf, 0, 10))
        assertEquals(9, buf[9].toInt())
    }

    @Test
    fun `a closed gate holds the read until it opens`() {
        val asks = AtomicInteger()
        val ds = source({ asks.incrementAndGet() < 5 })
        assertEquals(10, ds.read(ByteArray(10), 0, 10))
        assertEquals(5, asks.get())
    }

    @Test
    fun `the gate is not asked on every read`() {
        var now = 0L
        val asks = AtomicInteger()
        val ds = source({ asks.incrementAndGet(); false }, clock = { now })
        repeat(5) { ds.read(ByteArray(1), 0, 1) }
        assertEquals(1, asks.get())
        now = 600L
        ds.read(ByteArray(1), 0, 1)
        assertEquals(2, asks.get())
    }

    @Test
    fun `cancelling the loader ends the wait`() {
        val ds = source({ true })
        var thrown: Throwable? = null
        val t = Thread { thrown = runCatching { ds.read(ByteArray(1), 0, 1) }.exceptionOrNull() }
        t.start()
        Thread.sleep(50)
        t.interrupt()
        t.join(2_000)
        assertTrue(thrown is InterruptedIOException)
    }
}
