package com.arkiv.player.data.subtitles

import java.io.File
import java.security.MessageDigest

/** A downloaded online subtitle kept for a title: its language, menu label and UTF-8 SRT file. */
data class SavedOnlineSubtitle(val lang: String, val label: String, val path: String)

/**
 * Which online subtitles each title got, so reopening it ("Continuar") brings them back as tracks
 * and [TitleSubtitleMemory] can turn the chosen one on again. Pure codec (one line per subtitle:
 * title, lang, label, path, tab-separated, oldest first), stored by [SubtitlePrefs].
 */
object OnlineSubtitleMemory {
    /** Subtitles kept per title: the newest win. */
    const val MAX_PER_TITLE = 3

    /** Lines kept overall. */
    const val MAX_LINES = 600

    fun add(entries: List<Pair<String, SavedOnlineSubtitle>>, title: String, sub: SavedOnlineSubtitle): List<Pair<String, SavedOnlineSubtitle>> {
        val others = entries.filter { !(it.first == title && it.second.path == sub.path) }
        val mine = (others.filter { it.first == title } + (title to sub)).takeLast(MAX_PER_TITLE)
        return (others.filter { it.first != title } + mine).takeLast(MAX_LINES)
    }

    fun of(entries: List<Pair<String, SavedOnlineSubtitle>>, title: String): List<SavedOnlineSubtitle> =
        entries.filter { it.first == title }.map { it.second }

    fun encode(entries: List<Pair<String, SavedOnlineSubtitle>>): String =
        entries.joinToString("\n") { (t, s) -> listOf(t, s.lang, s.label, s.path).joinToString("\t") { clean(it) } }

    fun decode(raw: String?): List<Pair<String, SavedOnlineSubtitle>> =
        raw.orEmpty().lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 4 || p[0].isEmpty() || p[3].isEmpty()) null else p[0] to SavedOnlineSubtitle(p[1], p[2], p[3])
        }.toList()

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
}

/**
 * The downloaded subtitles on disk (`cacheDir/online_subtitles`), capped at [maxBytes]: past it the
 * least recently used go first (a file used again is touched). Android may also clear the cache
 * dir on its own; a remembered subtitle whose file is gone is simply not offered again.
 */
class OnlineSubtitleCache(private val dir: File, private val maxBytes: Long = MAX_BYTES) {

    /** Writes [bytes] as [name]'s file and trims the rest. Null when the disk refuses. */
    fun store(key: String, bytes: ByteArray): File? = runCatching {
        dir.mkdirs()
        val file = File(dir, fileName(key))
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        file.setLastModified(System.currentTimeMillis())
        trim(keep = file)
        file
    }.getOrNull()

    /** [path] if it is still here (touched, so the trim keeps it), else null. */
    fun existing(path: String): File? {
        val f = File(path)
        if (f.parentFile?.canonicalPath != dir.canonicalPath || !f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    /** Deletes the least recently used files until the rest fit in [maxBytes], never [keep]. */
    internal fun trim(keep: File? = null) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f == keep) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        const val DIR = "online_subtitles"

        fun fileName(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) } + ".srt"
        }
    }
}
