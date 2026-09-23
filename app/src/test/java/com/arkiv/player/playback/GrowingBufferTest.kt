package com.arkiv.player.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * The startup buffer, read WHILE it fills. See [GrowingBuffer] for the why.
 */
class GrowingBufferTest {

    private fun data(from: Int, n: Int) = ByteArray(n) { ((from + it) % 251).toByte() }

    @Test fun what_was_written_can_be_read_right_away() {
        val b = GrowingBuffer(1000)
        b.write(data(0, 10), 10)
        assertEquals(10, b.available)
        assertArrayEquals(data(0, 10), b.slice(0))
    }

    @Test fun slice_returns_only_what_is_new_since_an_offset() {
        // It's how the proxy consumes it: writes to VLC whatever arrived and advances its mark.
        val b = GrowingBuffer(1000)
        b.write(data(0, 30), 30)
        assertArrayEquals(data(10, 20), b.slice(10))
    }

    @Test fun slice_up_to_date_returns_empty() {
        val b = GrowingBuffer(1000)
        b.write(data(0, 10), 10)
        assertEquals(0, b.slice(10).size)
    }

    @Test fun waiting_returns_as_soon_as_the_bytes_arrive() {
        // The case that gives this its whole purpose: the reader arrives BEFORE the data and isn't
        // made to wait any longer than the origin takes to send it.
        val b = GrowingBuffer(1000)
        thread { Thread.sleep(50); b.write(data(0, 40), 40) }
        assertTrue("it had to wake up once the bytes arrived", b.waitUntil(40, 5_000))
        assertEquals(40, b.available)
    }

    @Test fun waiting_gives_up_if_the_buffer_closes_before_reaching_that_size() {
        // The origin cut off earlier. Not an error: the reader has to be able to leave the loop.
        val b = GrowingBuffer(1000)
        thread { Thread.sleep(50); b.write(data(0, 5), 5); b.close() }
        assertFalse(b.waitUntil(40, 5_000))
        assertTrue("whatever did arrive has to stay available", b.available == 5)
    }

    @Test fun waiting_does_not_block_forever_if_nothing_ever_arrives() {
        val b = GrowingBuffer(1000)
        val t0 = System.currentTimeMillis()
        assertFalse(b.waitUntil(10, 120))
        assertTrue("it had to time out on its own", System.currentTimeMillis() - t0 < 3_000)
    }

    @Test fun waiting_on_an_already_closed_buffer_returns_right_away() {
        val b = GrowingBuffer(1000)
        b.write(data(0, 10), 10)
        b.close()
        assertTrue("what's already available is granted even if closed", b.waitUntil(10, 5_000))
        assertFalse(b.waitUntil(11, 5_000))
    }

    @Test fun it_does_not_go_past_capacity() {
        // The cap is the hot-startup size: going past it would corrupt someone else's memory.
        val b = GrowingBuffer(16)
        b.write(data(0, 100), 100)
        assertEquals(16, b.available)
        assertArrayEquals(data(0, 16), b.slice(0))
    }

    @Test fun it_reports_when_nothing_more_is_ever_going_to_arrive() {
        val b = GrowingBuffer(1000)
        assertFalse(b.closed)
        b.close()
        assertTrue(b.closed)
    }
}
