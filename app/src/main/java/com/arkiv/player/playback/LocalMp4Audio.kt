package com.arkiv.player.playback

import com.arkiv.player.data.local.Mp4FastStart
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * A downloaded MP4's audio tracks, for the Chromecast: the Default Media Receiver plays only the
 * first audio track of a progressive MP4. So while casting a download that carries several
 * (`Mp4Prep` keeps them all), picking another audio on the phone makes a single-audio copy of the
 * file with that track ([TsRemuxer], through `Mp4Repackager`: seconds, nothing re-encoded) and
 * reloads the receiver with it at the TV's position -- the copy is faststart, so it seeks. Back on
 * the first audio, the file itself goes again.
 */
object LocalMp4Audio {

    private val counts = ConcurrentHashMap<String, Int>()

    /**
     * Whether casting [file] with the phone's audio [ordinal] (its index among the file's audio
     * tracks; null = the default) needs that single-audio copy: an MP4 with more than one audio
     * track and an audio other than the first.
     */
    fun needsCopy(file: File, ordinal: Int?): Boolean = (ordinal ?: 0) != 0 && audioTracks(file) > 1

    /** Whether picking another audio while casting [file] can reach the TV at all (a copy per audio). */
    fun switchable(file: File): Boolean = audioTracks(file) > 1

    /** The number of audio tracks of the MP4 [file] (0 for anything else, or unreadable). Cached by path and size. */
    fun audioTracks(file: File): Int {
        val key = "${file.absolutePath}:${file.length()}"
        counts[key]?.let { return it }
        val n = runCatching { count(file) }.getOrDefault(0)
        counts[key] = n
        return n
    }

    private fun count(file: File): Int = RandomAccessFile(file, "r").use { raf ->
        val moov = Mp4FastStart.topLevelBoxes(raf, untilType = "moov").firstOrNull { it.type == "moov" } ?: return 0
        if (moov.size > MAX_MOOV) return 0
        val bytes = ByteArray(moov.size.toInt())
        raf.seek(moov.offset)
        raf.readFully(bytes)
        countSoundTracks(bytes)
    }

    /** The `trak` boxes inside a `moov` box ([moov], header included) whose handler is `soun`. */
    fun countSoundTracks(moov: ByteArray): Int {
        var n = 0
        children(moov, 8, moov.size).filter { it.first == "trak" }.forEach { (_, start, end) ->
            val mdia = children(moov, start, end).firstOrNull { it.first == "mdia" } ?: return@forEach
            val hdlr = children(moov, mdia.second, mdia.third).firstOrNull { it.first == "hdlr" } ?: return@forEach
            // hdlr: version/flags (4), pre_defined (4), handler_type (4).
            val at = hdlr.second + 8
            if (at + 4 <= hdlr.third && String(moov, at, 4, Charsets.ISO_8859_1) == "soun") n++
        }
        return n
    }

    /** (type, payload start, end) of each box in [b] between [from] and [to]. */
    private fun children(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var at = from
        while (at + 8 <= to) {
            val size = ByteBuffer.wrap(b, at, 4).int
            if (size < 8 || at + size > to) break
            out += Triple(String(b, at + 4, 4, Charsets.ISO_8859_1), at + 8, at + size)
            at += size
        }
        return out
    }

    private const val MAX_MOOV = 64L * 1024 * 1024
}
