package com.arkiv.player.data.subtitles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * The subtitle inside a SubDL download: a zip (sometimes a season pack) holding one or more `.srt`
 * (or `.vtt`) files, next to junk (`__MACOSX/`, `._` forks, `.nfo`, images). Pure, so a test pins it.
 *
 * Some old uploads are a RAR under a `.zip` name: no RAR reader ships in the app, so those read as
 * null (the caller says the file has no usable subtitle). A body that is already a plain subtitle
 * (no zip header but a `-->` timing line) is taken as is.
 */
object SubtitleZip {

    /** No entry over this is read: bigger than any real subtitle, a bound against a zip bomb. */
    const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

    /** At most this many entries are looked at. */
    private const val MAX_ENTRIES = 400

    /** The subtitle to use from [bytes], or null. [season]/[episode] pick the right file of a pack. */
    fun extract(bytes: ByteArray, season: Int? = null, episode: Int? = null): ByteArray? {
        if (!isZip(bytes)) return bytes.takeIf { looksLikeSubtitle(it) }
        val found = mutableListOf<Pair<String, ByteArray>>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var seen = 0
                while (seen++ < MAX_ENTRIES) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || isJunk(name) || extensionRank(name) < 0) continue
                    readCapped(zip)?.let { found += name to it }
                }
            }
        }
        if (found.isEmpty()) return null
        return pick(found, season, episode)?.second
    }

    /** The best of [entries] (name, bytes): the requested episode's, then `.srt` before `.vtt`, then the biggest. */
    internal fun pick(entries: List<Pair<String, ByteArray>>, season: Int?, episode: Int?): Pair<String, ByteArray>? {
        val matching = if (episode != null && episode > 0) entries.filter { matchesEpisode(it.first, season, episode) } else emptyList()
        val pool = matching.ifEmpty { entries }
        return pool.sortedWith(compareBy<Pair<String, ByteArray>> { extensionRank(it.first) }.thenByDescending { it.second.size }).firstOrNull()
    }

    /** Does [name] say it is episode [episode] (of [season] when given): `S01E02`, `1x02`, `E02`? */
    internal fun matchesEpisode(name: String, season: Int?, episode: Int): Boolean {
        val base = name.substringAfterLast('/').lowercase()
        // A name that numbers itself (S01E02, 1x02) is judged by that alone.
        val numbered = (Regex("s(\\d{1,2})[ ._-]?e(\\d{1,3})").findAll(base) +
            Regex("(?<!\\d)(\\d{1,2})x(\\d{1,3})(?!\\d)").findAll(base)).toList()
        if (numbered.isNotEmpty()) {
            return numbered.any { m ->
                m.groupValues[2].toInt() == episode && (season == null || m.groupValues[1].toInt() == season)
            }
        }
        return Regex("(?<![a-z])(?:e|ep|episode)[ ._-]?0*$episode(?!\\d)").containsMatchIn(base)
    }

    /** 0 for `.srt`, 1 for `.vtt`, -1 for anything that is not a subtitle Kino plays. */
    private fun extensionRank(name: String): Int = when {
        name.endsWith(".srt", ignoreCase = true) -> 0
        name.endsWith(".vtt", ignoreCase = true) -> 1
        else -> -1
    }

    private fun isJunk(name: String): Boolean =
        name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith("._")

    private fun isZip(b: ByteArray): Boolean = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    /** A plain SRT/WebVTT body: some timing line in its first few KB. */
    internal fun looksLikeSubtitle(b: ByteArray): Boolean =
        b.isNotEmpty() && b.size <= MAX_ENTRY_BYTES && String(b, 0, minOf(b.size, 8192), Charsets.ISO_8859_1).contains("-->")

    private fun readCapped(zip: ZipInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_ENTRY_BYTES) return null
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}
