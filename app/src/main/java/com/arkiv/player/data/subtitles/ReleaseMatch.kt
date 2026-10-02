package com.arkiv.player.data.subtitles

/**
 * What is known about the file being played, to pick the subtitle made for it: its OpenSubtitles
 * [hash] (null = not computed), [fileName] (URL's last segment, no extension) and the [title] and
 * [year] of the work. Everything is optional; with nothing, ranking falls back to language and downloads.
 */
data class FileHint(
    val hash: String? = null,
    val fileName: String? = null,
    val title: String = "",
    val year: Int? = null,
) {
    /** Nothing to compare release names with. */
    val isBlank: Boolean get() = ReleaseMatch.profile(listOfNotNull(fileName, title).joinToString(" ")).let { it.words.isEmpty() } && year == null
}

/** Pure release-name similarity: how much a subtitle's release looks like the playing file. */
object ReleaseMatch {

    /** A release name read into its parts. */
    data class Profile(
        val words: Set<String>,
        val year: Int?,
        val resolution: String?,
        val source: String?,
        val codec: String?,
    )

    private val RESOLUTION = mapOf(
        "480p" to "480p", "576p" to "576p", "720p" to "720p", "1080p" to "1080p", "1080i" to "1080p",
        "2160p" to "2160p", "4k" to "2160p", "uhd" to "2160p",
    )
    private val SOURCE = mapOf(
        "bluray" to "bluray", "blu" to "bluray", "bdrip" to "bluray", "brrip" to "bluray", "bd" to "bluray", "remux" to "bluray",
        "webrip" to "web", "webdl" to "web", "web" to "web", "amzn" to "web", "nf" to "web",
        "hdtv" to "hdtv", "pdtv" to "hdtv", "dvdrip" to "dvd", "dvd" to "dvd", "hdrip" to "hdrip", "cam" to "cam", "hdcam" to "cam",
    )
    private val CODEC = mapOf("x264" to "avc", "h264" to "avc", "avc" to "avc", "x265" to "hevc", "h265" to "hevc", "hevc" to "hevc", "xvid" to "xvid", "divx" to "xvid")

    /** Tokens that say how a file was made, not what it is: they never count as the title's words. */
    private val NOISE = setOf(
        "aac", "ac3", "dts", "ddp5", "dd5", "mp3", "5", "1", "0", "2", "hdr", "hdr10", "10bit", "8bit", "sdr", "dv",
        "yts", "yify", "rarbg", "etrg", "ettv", "eztv", "mx", "am", "lt", "ag", "psa", "ntb", "flux", "evo", "sparks", "geckos",
        "internal", "proper", "repack", "extended", "unrated", "remastered", "criterion", "multi", "dual", "sub", "subs", "subbed",
        "eng", "spa", "esp", "lat", "english", "spanish", "mp4", "mkv", "avi", "srt", "mpeg", "mpg", "ia", "archive", "org",
    )

    fun profile(release: String): Profile {
        val tokens = release.lowercase().replace('\'', ' ').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        var year: Int? = null
        var res: String? = null
        var src: String? = null
        var codec: String? = null
        val words = linkedSetOf<String>()
        for (t in tokens) {
            val y = t.toIntOrNull()?.takeIf { t.length == 4 && it in 1890..2100 }
            when {
                y != null -> if (year == null) year = y
                t in RESOLUTION -> if (res == null) res = RESOLUTION.getValue(t)
                t in SOURCE -> if (src == null) src = SOURCE.getValue(t)
                t in CODEC -> if (codec == null) codec = CODEC.getValue(t)
                t in NOISE -> Unit
                else -> words += t
            }
        }
        return Profile(words, year, res, src, codec)
    }

    /**
     * How well [release] matches [hint]: the overlap (Jaccard) of the meaningful words, 1.0 at most,
     * +0.5 / -0.5 when both name a year and it agrees / differs (the same title's other cut), and a
     * small bonus for each of resolution, source and codec that agree. 0 when [hint] has nothing to compare.
     */
    fun score(release: String, hint: FileHint): Double {
        val want = profile(listOfNotNull(hint.fileName, hint.title).joinToString(" "))
        val wantYear = want.year ?: hint.year
        if (want.words.isEmpty() && wantYear == null) return 0.0
        val have = profile(release)
        var s = 0.0
        if (want.words.isNotEmpty() || have.words.isNotEmpty()) {
            val union = (want.words + have.words).size
            if (union > 0) s += (want.words intersect have.words).size.toDouble() / union
        }
        if (wantYear != null && have.year != null) s += if (wantYear == have.year) 0.5 else -0.5
        if (want.resolution != null && want.resolution == have.resolution) s += 0.15
        if (want.source != null && want.source == have.source) s += 0.15
        if (want.codec != null && want.codec == have.codec) s += 0.05
        return s
    }
}
