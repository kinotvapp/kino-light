package com.arkiv.player.playback

import java.io.File
import java.nio.ByteBuffer

/**
 * The last KB of a file, saved to disk so they survive the app's restart.
 *
 * Exists because of a Fire TV measurement from 2026-08-14, opening an MPEG-TS movie for the first
 * time:
 *
 * ```
 * 09:35:12.667  ← pide rango=bytes=660308868-           ← libVLC wanted the END of the file
 * 09:35:13.921  origen rechazó ... con -1 (intento 1/3)
 * 09:35:15.121  origen rechazó ... con -1 (intento 1/3)
 * 09:35:16.320  precalentada la cola: 256KB en 6205ms
 * 09:35:16.386  ⏱ abrió en 5376ms
 * ```
 *
 * With a TS, libVLC used to probe the end of the file to deduce the duration, and until that
 * range arrived there was NO picture (see [HotTail], which already serves it from memory).
 * The comparison from the same day leaves no doubt: the new mp4 asked ONLY for `bytes=0-` and
 * opened in 1525ms; the new mpegts also asked for the end and took 5376ms. And the mpegts files
 * that opened in 393 and 421ms were the same title already seen, with the tail still in memory.
 *
 * That's the waste this fixes: [HotTail] and `openWithDuplicate` already tackle the probing
 * and the CDN's rejections, but those 256 KB lived in an in-memory map, so EVERY app startup paid
 * the first-time cost again. On a Fire TV, which kills the app as soon as it goes to the
 * background, that's almost always.
 *
 * The key is stable across sessions because it's the SHA-1 of the ORIGIN URL, and magis's doesn't
 * carry the token inside it (it travels in the headers): `…/vod/<contentId>_media.mp4`.
 */
class TailOnDisk(private val dir: File, private val maxTails: Int = MAX_TAILS) {

    /** The final bytes of a file and where they start within it. */
    data class Tail(val start: Long, val total: Long, val bytes: ByteArray) {
        // `equals`/`hashCode` by hand: a data class with a ByteArray generates them by identity,
        // and that's surprising. What matters here is the content.
        override fun equals(other: Any?): Boolean = other is Tail &&
            start == other.start && total == other.total && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int =
            (start.hashCode() * 31 + total.hashCode()) * 31 + bytes.contentHashCode()
    }

    private fun file(key: String) = File(dir, "$key$SUFFIX")

    /**
     * Saves [bytes] as [key]'s tail. An empty tail is not saved: it would be useless and would
     * take up a slot toward the cap.
     */
    fun save(key: String, start: Long, total: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching {
            dir.mkdirs()
            val header = ByteBuffer.allocate(HEADER_BYTES).putLong(start).putLong(total)
            // Written to a temp file and renamed: if the app dies midway, what's left is the temp
            // file and not a half-written tail that would be read as good.
            val tmp = File(dir, "$key$SUFFIX.tmp")
            tmp.outputStream().use { it.write(header.array()); it.write(bytes) }
            tmp.renameTo(file(key))
            dropOld()
        }
    }

    /**
     * [key]'s saved tail, or null if there isn't one or it can't be read whole.
     *
     * Any problem silently returns null: an unreadable tail just means going to the origin as
     * usual, which is exactly what happened before this existed.
     */
    fun read(key: String): Tail? = runCatching {
        val f = file(key)
        if (!f.isFile) return null
        val raw = f.readBytes()
        if (raw.size <= HEADER_BYTES) return null
        val buf = ByteBuffer.wrap(raw)
        val start = buf.long
        val total = buf.long
        val bytes = raw.copyOfRange(HEADER_BYTES, raw.size)
        f.setLastModified(System.currentTimeMillis())
        Tail(start, total, bytes)
    }.getOrNull()

    /** Keeps at most [maxTails], dropping the ones with the oldest modification date. */
    private fun dropOld() {
        val files = dir.listFiles { f -> f.name.endsWith(SUFFIX) } ?: return
        if (files.size <= maxTails) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxTails)
            .forEach { runCatching { it.delete() } }
    }

    companion object {
        /** Two `Long`s: where the tail starts within the file, and how big the whole file is. */
        const val HEADER_BYTES = 16

        /** ~256 KB each. 64 titles ≈ 16 MB, negligible next to the video cache. */
        const val MAX_TAILS = 64

        private const val SUFFIX = ".cola"
    }
}
