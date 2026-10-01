package com.arkiv.player.cast

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** One subtitle cue, in ms of the title. [text] keeps only `<i>`, `<b>`, `<u>` as tags, raw otherwise. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * A source's subtitle file (SRT or WebVTT, any common encoding) turned into what a TV reads: WebVTT
 * for a Chromecast, SRT for a DLNA renderer. Pure: no Android, every quirk pinned by a test.
 *
 * Why the phone converts at all: the Default Media Receiver reads WebVTT only (TTML too, never SRT),
 * and a Spanish SRT is very often Windows-1252, which a receiver decodes as UTF-8 into "Ã±" garbage.
 * The phone already plays these files (ExoPlayer guesses well), so it is the one that normalizes them.
 */
object CastSubtitleText {

    /** Bigger than any real subtitle file (a 3 h film is ~150 KB): a bound against a hostile URL. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * [bytes] as text. A BOM decides (UTF-8, UTF-16 LE/BE); otherwise strict UTF-8, and when that
     * fails -- the usual case for a Spanish SRT saved on Windows -- Windows-1252, which is Latin-1
     * plus the curly quotes and dashes those files carry in 0x80-0x9F. A BOM-less UTF-16 file is
     * told apart by its zero bytes.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        utf16WithoutBom(bytes)?.let { return String(bytes, it) }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, WINDOWS_1252)
        }
    }

    /** UTF-16 with no BOM: text that is mostly ASCII has a zero in every other byte. */
    private fun utf16WithoutBom(bytes: ByteArray): Charset? {
        val n = minOf(bytes.size, 4096) and 1.inv()
        if (n < 8) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until n step 2) {
            if (bytes[i].toInt() == 0) evenZeros++
            if (bytes[i + 1].toInt() == 0) oddZeros++
        }
        val half = n / 2
        return when {
            oddZeros > half * 0.4 && evenZeros < half * 0.05 -> Charsets.UTF_16LE
            evenZeros > half * 0.4 && oddZeros < half * 0.05 -> Charsets.UTF_16BE
            else -> null
        }
    }

    private val WINDOWS_1252: Charset =
        runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    private val TIMING = Regex(
        "^\\s*((?:\\d+:)?\\d{1,2}:\\d{1,2}(?:[.,:]\\d{1,3})?)\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{1,2}(?:[.,:]\\d{1,3})?)",
    )

    /**
     * The cues of an SRT or WebVTT [text], sorted by start. Tolerant on purpose -- these files are
     * hand-made: index lines are optional, a missing blank line between cues is fine, `,` or `.`
     * before the ms, hours optional, cue settings after the timing ignored; WebVTT's header, NOTE,
     * STYLE and REGION blocks never become cues. A cue with no text left, or that ends before it
     * starts, is dropped.
     */
    fun parse(text: String): List<SubtitleCue> {
        val clean = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val vtt = clean.trimStart().startsWith("WEBVTT")
        val lines = clean.split('\n')
        val cues = ArrayList<SubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val timing = timingOf(lines[i])
            i++
            if (timing == null) continue
            val body = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (timingOf(lines[i]) != null) break
                // An SRT index right before the next timing: the blank line between cues was missing.
                if (lines[i].trim().all { it.isDigit() } && i + 1 < lines.size && timingOf(lines[i + 1]) != null) break
                body += lines[i]
                i++
            }
            val cueText = cleanText(body, vtt)
            if (cueText.isNotEmpty() && timing.second > timing.first) {
                cues += SubtitleCue(timing.first, timing.second, cueText)
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun timingOf(line: String): Pair<Long, Long>? {
        val m = TIMING.find(line) ?: return null
        val start = timeMs(m.groupValues[1]) ?: return null
        val end = timeMs(m.groupValues[2]) ?: return null
        return start to end
    }

    /** `HH:MM:SS,mmm`, `MM:SS.mmm`, `H:MM:SS` (no ms) or `HH:MM:SS:mmm` to ms. */
    internal fun timeMs(s: String): Long? {
        val parts = s.trim().split(':', ',', '.')
        // The last part is ms only when a ',' or '.' introduced it, or when there are 4 parts.
        val hasFraction = s.contains(',') || s.contains('.') || parts.size == 4
        val nums = parts.map { it.toLongOrNull() ?: return null }
        val (whole, fraction) = if (hasFraction) nums.dropLast(1) to parts.last() else nums to ""
        val seconds = when (whole.size) {
            2 -> whole[0] * 60 + whole[1]
            3 -> whole[0] * 3600 + whole[1] * 60 + whole[2]
            else -> return null
        }
        // "1,5" is 500 ms, not 5: the digits are a decimal fraction.
        val ms = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').take(3).toLong()
        return seconds * 1000 + ms
    }

    private val ASS_OVERRIDE = Regex("\\{\\\\[^}]*\\}")
    private val TAG = Regex("<\\s*(/?)\\s*([A-Za-z]+)[^>]*>")
    private val TIMESTAMP_TAG = Regex("<\\d[^>]*>")
    private val KEPT = setOf("i", "b", "u")

    /**
     * A cue's lines, with only `<i>`/`<b>`/`<u>` left as tags (both formats render those): `<font>`,
     * WebVTT's voice/class/ruby tags and inline timestamps, and ASS overrides like `{\an8}` that some
     * SRTs carry, go. WebVTT input is unescaped so it is not escaped twice on the way out.
     */
    private fun cleanText(lines: List<String>, vtt: Boolean): String =
        lines.asSequence()
            .map { line ->
                var t = line.replace(ASS_OVERRIDE, "").replace(TIMESTAMP_TAG, "")
                t = TAG.replace(t) { m ->
                    val name = m.groupValues[2].lowercase()
                    if (name in KEPT) "<${m.groupValues[1]}$name>" else ""
                }
                if (vtt) t = unescapeVtt(t)
                t.trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun unescapeVtt(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&lrm;", "‎").replace("&rlm;", "‏").replace("&amp;", "&")

    /**
     * [cues] moved [shiftMs] earlier (the receiver's timeline starts [shiftMs] into the title, see
     * `CastRequest.offsetMs`): a cue that ends before the new zero is gone, one that straddles it
     * starts at 0.
     */
    fun shift(cues: List<SubtitleCue>, shiftMs: Long): List<SubtitleCue> {
        if (shiftMs == 0L) return cues
        return cues.mapNotNull { c ->
            val end = c.endMs - shiftMs
            if (end <= 0L) null else SubtitleCue((c.startMs - shiftMs).coerceAtLeast(0L), end, c.text)
        }
    }

    /** The cues that show at some point in `[fromMs, toMs)`: one HLS subtitle segment's worth. */
    fun window(cues: List<SubtitleCue>, fromMs: Long, toMs: Long): List<SubtitleCue> =
        cues.filter { it.endMs > fromMs && it.startMs < toMs }

    /** WebVTT for [cues] (already on the receiver's timeline). */
    fun toVtt(cues: List<SubtitleCue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { c ->
            append(clock(c.startMs, '.')).append(" --> ").append(clock(c.endMs, '.')).append('\n')
            append(escapeVtt(c.text)).append("\n\n")
        }
    }

    private val KEPT_TAG = Regex("</?[ibu]>")

    /** `&`, `<` and `>` escaped everywhere but in the kept tags; `-->` can't appear in a cue. */
    private fun escapeVtt(text: String): String {
        val out = StringBuilder()
        var last = 0
        KEPT_TAG.findAll(text).forEach { m ->
            out.append(escapeChars(text.substring(last, m.range.first)))
            out.append(m.value)
            last = m.range.last + 1
        }
        out.append(escapeChars(text.substring(last)))
        return out.toString()
    }

    private fun escapeChars(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("--&gt;", "→")

    /** SRT for [cues], for a DLNA renderer. Text as it is: SRT has no entities. */
    fun toSrt(cues: List<SubtitleCue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append("\r\n")
            append(clock(c.startMs, ',')).append(" --> ").append(clock(c.endMs, ',')).append("\r\n")
            append(c.text.replace("\n", "\r\n")).append("\r\n\r\n")
        }
    }

    /**
     * [srt] as bytes a TV reads: UTF-8 WITH a BOM. Most TVs only take the file as UTF-8 when the
     * BOM says so and fall back to Latin-1 otherwise, which turns "ñ" into "Ã±"; with it, the ones
     * that know UTF-8 get it right. An old set that only reads Latin-1 gets [latin1Srt].
     */
    fun utf8SrtBytes(srt: String): ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt.toByteArray(Charsets.UTF_8)

    /** [srt] in Windows-1252: what an old LG asks for. Characters it lacks become '?'. */
    fun latin1SrtBytes(srt: String): ByteArray = srt.toByteArray(WINDOWS_1252)

    /** `HH:MM:SS.mmm` (or `,mmm` for SRT). */
    internal fun clock(ms: Long, sep: Char): String {
        val t = ms.coerceAtLeast(0L)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1000 % 60
        val f = t % 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d%c%03d", h, m, s, sep, f)
    }
}
