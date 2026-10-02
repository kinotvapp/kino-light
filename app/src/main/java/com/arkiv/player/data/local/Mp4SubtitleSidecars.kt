package com.arkiv.player.data.local

import com.arkiv.player.cast.CastSubtitleText
import com.arkiv.player.data.subtitles.SavedOnlineSubtitle
import java.io.File

/**
 * A prepared download's subtitles: one SubRip file per subtitle, UTF-8 WITH a BOM, named after the
 * video and its language -- `<video>.es.srt`, `<video>.en.srt` (`<video>.es.2.srt` for a second
 * Spanish one) -- which is what a TV, a PC player or a file manager recognizes next to
 * `<video>.mp4`. Covers the download's own subtitles (saved by [SubtitleSidecars] as
 * `<id>.sub.<i>.<vtt|srt>`) and the online subtitles the person got for that title, which live in
 * the app's cache and may be cleared: copied here, they stay with the download.
 *
 * Every file goes through [CastSubtitleText] (the encoding guess for a Windows-1252 SRT, WebVTT to
 * SRT, tags trimmed to i/b/u). The manifest ([OfflineSubtitleFiles]) is rewritten to the new files,
 * keeping each label, so offline playback and the per-title subtitle memory see the same subtitles;
 * an online copy carries its cache path as `origin`, so it is not offered twice while the online
 * one is still there.
 *
 * Idempotent: running it again rewrites the same names. Files that are replaced (the old
 * `.sub.<i>` names) are deleted; the online cache is never touched.
 */
object Mp4SubtitleSidecars {

    /**
     * Rewrites [episodeId]'s subtitles next to [video], adding [online]. The files are named after
     * [video] when it is this episode's own file, else after the episode (an adopted twin's file is
     * named after the twin, and its sidecars must go with THIS row when it is removed). Returns what
     * the manifest now lists.
     */
    fun rewrite(dir: File, episodeId: String, video: File, online: List<SavedOnlineSubtitle>): List<OfflineSubtitleFiles.Saved> {
        val own = LocalFilePaths.sanitize(episodeId)
        val base = video.nameWithoutExtension.takeIf { it == own || it.startsWith("$own.") } ?: own
        val existing = OfflineSubtitleFiles.readAll(dir, episodeId)
        val sources = existing.map { Source(it.file, it.lang, codeFor(it.lang), it.origin) } +
            online.filter { o -> existing.none { it.origin == o.path } }
                .map { Source(File(it.path), it.label.ifBlank { it.lang }, codeFor(it.lang.ifBlank { it.label }), it.path) }
        if (sources.isEmpty()) return emptyList()

        val taken = HashSet<String>()
        val written = ArrayList<OfflineSubtitleFiles.Saved>()
        val converted = sources.mapNotNull { src ->
            val bytes = runCatching { src.file.readBytes() }.getOrNull() ?: return@mapNotNull null
            val srt = toSrtBytes(bytes) ?: return@mapNotNull null
            val name = nameFor(base, src.code, taken)
            taken += name
            Triple(src, File(dir, name), srt)
        }
        converted.forEach { (src, target, srt) ->
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(srt)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@forEach
            }
            written += OfflineSubtitleFiles.Saved(target, src.label, srt = true, origin = src.origin)
        }
        // The old names are gone once their replacement is written; never anything outside [dir].
        val keep = written.map { it.file.absolutePath }.toSet()
        existing.forEach { old ->
            if (old.file.absolutePath !in keep && old.file.parentFile?.absolutePath == dir.absolutePath &&
                converted.any { it.first.file == old.file }
            ) {
                old.file.delete()
            }
        }
        if (written.isNotEmpty()) OfflineSubtitleFiles.writeManifest(dir, episodeId, written)
        return written
    }

    /** `<base>.<code>.srt`, or `<base>.<code>.<n>.srt` when [taken] already has that language. */
    fun nameFor(base: String, code: String, taken: Set<String>): String {
        val first = "$base.$code.srt"
        if (first !in taken) return first
        var n = 2
        while ("$base.$code.$n.srt" in taken) n++
        return "$base.$code.$n.srt"
    }

    /** [raw] (SRT or WebVTT, any of the encodings [CastSubtitleText.decode] knows) as UTF-8 SRT with a BOM; null without a cue. */
    fun toSrtBytes(raw: ByteArray): ByteArray? {
        val cues = CastSubtitleText.parse(CastSubtitleText.decode(raw))
        if (cues.isEmpty()) return null
        return CastSubtitleText.utf8SrtBytes(CastSubtitleText.toSrt(cues))
    }

    /**
     * The two-letter code for a subtitle's language as the sources spell it: a code (`es`,
     * `es-419`, `spa`) or a name in Spanish or English ("Español (Latino)", "Inglés", "English").
     * `und` when nothing says.
     */
    fun codeFor(label: String): String {
        val t = label.trim().lowercase()
        if (t.isEmpty()) return UNDETERMINED
        val primary = t.substringBefore('-').substringBefore('_').substringBefore(' ').substringBefore('(')
        if (primary.length == 2 && primary.all { it in 'a'..'z' }) return primary
        ISO3[primary]?.let { return it }
        NAMES.firstOrNull { (prefix, _) -> t.startsWith(prefix) }?.let { return it.second }
        return UNDETERMINED
    }

    private class Source(val file: File, val label: String, val code: String, val origin: String?)

    private const val UNDETERMINED = "und"

    private val ISO3 = mapOf(
        "spa" to "es", "eng" to "en", "por" to "pt", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de",
        "ita" to "it", "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh", "rus" to "ru", "ara" to "ar",
        "cat" to "ca", "dut" to "nl", "nld" to "nl", "pol" to "pl", "tur" to "tr",
    )

    private val NAMES = listOf(
        "español" to "es", "espanol" to "es", "castellano" to "es", "latino" to "es", "spanish" to "es",
        "inglés" to "en", "ingles" to "en", "english" to "en",
        "portugués" to "pt", "portugues" to "pt", "português" to "pt", "portuguese" to "pt",
        "francés" to "fr", "frances" to "fr", "français" to "fr", "french" to "fr",
        "alemán" to "de", "aleman" to "de", "deutsch" to "de", "german" to "de",
        "italiano" to "it", "italian" to "it",
        "japonés" to "ja", "japones" to "ja", "japanese" to "ja",
        "coreano" to "ko", "korean" to "ko",
        "chino" to "zh", "chinese" to "zh",
        "ruso" to "ru", "russian" to "ru",
        "árabe" to "ar", "arabe" to "ar", "arabic" to "ar",
        "catalán" to "ca", "catalan" to "ca",
    )
}
