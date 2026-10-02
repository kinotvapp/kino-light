package com.arkiv.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TsStartDataSource]: the tables, then the file from the keyframe's byte, as one stream whose
 * every position -- the extractor's first read, its duration read at the end, a reopen after a
 * network error -- maps onto the right byte upstream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TsStartDataSourceTest {

    private val file = ByteArray(10_000) { (it * 7 + 3).toByte() }
    private val tables = ByteArray(376) { (100 + it % 50).toByte() }
    private val start = TsStart(byteOffset = 4_000L, startMs = 60_000L, tables = tables)
    private val virtual = tables + file.copyOfRange(4_000, file.size)

    private fun readAll(position: Long, length: Long = C.LENGTH_UNSET.toLong()): Pair<Long, ByteArray> {
        val source = TsStartDataSource(ByteArrayDataSource(file), start)
        val opened = source.open(DataSpec.Builder().setUri(Uri.parse("http://127.0.0.1/x")).setPosition(position).setLength(length).build())
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(333)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        source.close()
        return opened to out.toByteArray()
    }

    @Test
    fun `from the top it is the tables, then the file from the keyframe`() {
        val (length, bytes) = readAll(0L)
        assertEquals(virtual.size.toLong(), length)
        assertArrayEquals(virtual, bytes)
    }

    @Test
    fun `any position maps onto the same bytes`() {
        for (position in listOf(1L, 375L, 376L, 377L, 5_000L, virtual.size - 10L)) {
            val (length, bytes) = readAll(position)
            assertEquals("length at $position", virtual.size - position, length)
            assertArrayEquals("bytes at $position", virtual.copyOfRange(position.toInt(), virtual.size), bytes)
        }
    }

    @Test
    fun `a bounded read stops where it was asked to, inside the tables or across them`() {
        assertArrayEquals(virtual.copyOfRange(10, 110), readAll(10L, 100L).second)
        assertArrayEquals(virtual.copyOfRange(300, 900), readAll(300L, 600L).second)
        assertArrayEquals(virtual.copyOfRange(1_000, 1_050), readAll(1_000L, 50L).second)
    }
}
