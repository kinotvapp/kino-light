package com.arkiv.player.data.subtitles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The OpenSubtitles hash (OSDb): the file's size plus the sum, as unsigned 64-bit little-endian
 * words with wraparound, of its first and last 64 KB, as 16 lowercase hex chars. It names ONE exact
 * file, which a title+year search cannot. Files under two windows (128 KB) are never hashed.
 *
 * Only three small reads are needed ([RangeReader.length], then the head and the tail window), so
 * the same code serves a local file and a remote one read with HTTP ranges ([HttpRangeReader]).
 */
object MovieHash {
    const val CHUNK = 65536

    /** Smaller files can't be hashed (OSDb's rule). */
    const val MIN_SIZE = 2L * CHUNK

    /** The whole hash computation gives up after this long, so the subtitle menu never waits on it. */
    const val BUDGET_MS = 4_000L

    /** A file's bytes by range: one [length] and two [read]s per hash. Never throws; null = not available. */
    interface RangeReader {
        suspend fun length(): Long?
        suspend fun read(offset: Long, len: Int): ByteArray?
    }

    /** The hash of what [reader] serves, or null when it can't be hashed or anything fails or runs past [budgetMs]. */
    suspend fun compute(reader: RangeReader, budgetMs: Long = BUDGET_MS): String? = try {
        withTimeoutOrNull(budgetMs) {
            val size = reader.length()?.takeIf { it >= MIN_SIZE } ?: return@withTimeoutOrNull null
            val head = reader.read(0, CHUNK)?.takeIf { it.size == CHUNK } ?: return@withTimeoutOrNull null
            val tail = reader.read(size - CHUNK, CHUNK)?.takeIf { it.size == CHUNK } ?: return@withTimeoutOrNull null
            hashOf(size, head, tail)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The 16-hex hash from the file [size] and its first and last [CHUNK] bytes. */
    fun hashOf(size: Long, head: ByteArray, tail: ByteArray): String =
        String.format("%016x", size + sum(head) + sum(tail))

    private fun sum(bytes: ByteArray): Long {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var acc = 0L
        repeat(bytes.size / 8) { acc += bb.long }
        return acc
    }

    /** A file on disk as a [RangeReader]: nothing but the size and the two windows is read. */
    class LocalReader(private val file: File) : RangeReader {
        override suspend fun length(): Long? = file.takeIf { it.isFile }?.length()

        override suspend fun read(offset: Long, len: Int): ByteArray? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(len)
                raf.seek(offset)
                raf.readFully(buf)
                buf
            }
        }.getOrNull()
    }
}
