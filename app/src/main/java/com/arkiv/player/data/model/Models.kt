package com.arkiv.player.data.model

/** A logical episode/video. */
data class Episode(
    val id: String,          // stable: "<identifier>::<baseKey>"
    val itemId: String,      // the item's identifier
    val section: String,     // folder (empty if at the root)
    val displayName: String, // clean name to display
    val orderIndex: Int,     // natural order within the item
    val durationSeconds: Double,
    val thumbPath: String?,  // thumbnail path in .thumbs (or null)
    /**
     * Where this episode came from, when the source records it: today Magis and Caracol write
     * their `ref` here (see [com.arkiv.player.data.ditu.DituRef]); legacy rows may still hold the
     * page URL or magnet left by the now-removed web/torrent sources before this branch's
     * pruning. It's the `torrentData` of [com.arkiv.player.data.db.EpisodeEntity] exposed to the
     * domain, and the UI needs it to tell apart TWO rows of the same (season, chapter) saved from
     * different sites -- the number alone can't tell them apart. Null for legacy archive.org rows
     * (that source never wrote it) and for other old episodes saved without this data.
     */
    val sourceRef: String? = null,
    /**
     * Season and chapter number, set by the source when it builds the episode (see
     * `MagisEntities`/`DituEntities`). Null when the source doesn't provide them. Used to ask
     * TMDB for the chapter's real title: neither Magis nor Caracol return the episode name, only
     * its number.
     */
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Season/episode of a series episode, deduced from the texts it was saved with (`section` =
 * "Temporada N", `displayName` = "TN · EM …" — see `ArkivRepository.addWebSeriesEpisode` and
 * `addSeriesEpisodeMagnet`). `section`/`displayName` have no int column of their own, so this
 * parsing is where those two numbers come from when they need to be shown.
 *
 * Verified with `grep -rn "seasonOf(\|episodeOf(" app/src/main/java`: besides [displayLabel] (via
 * `ArkivRepository.headerInfo`, for the player header's "T1 · E3" label, which reuses [seasonOf]
 * as one of its season fallbacks), today they're called directly by
 * `ArkivRepository.triviaSubjectFor`, to deduce an episode's season and number when
 * `EpisodeEntity.season`/`.episode` don't carry them.
 */
object EpisodeNumbering {
    /** First number in the section ("Temporada 2" → 2). Null if the section isn't a series one. */
    fun seasonOf(section: String): Int? = Regex("\\d+").find(section)?.value?.toIntOrNull()

    /** Number after the "E" in the name ("T1 · E7  Title" → 7). Null if there's no episode mark. */
    fun episodeOf(displayName: String): Int? =
        Regex("(?i)E(\\d+)").find(displayName)?.groupValues?.get(1)?.toIntOrNull()

    private val SXE = Regex("(?i)s(\\d+)\\s*e(\\d+)")
    private val SEASON = Regex("(?i)\\bT\\s*(\\d+)")
    private val EPISODE = Regex("(?i)\\bE(?:pisodio|p)?\\.?\\s*(\\d+)")

    /**
     * Season/episode label to SHOW in the player ("T1 · E3", or "E7" when there's no season).
     * Null if the name declares no episode: showing nothing is preferred over making something up
     * or dumping dirty text — the real database has displayNames with the whole synopsis and date
     * glued on, and others that are pure noise ("TPO Neon Genesis Evangelion 04 · Trapo2019 …").
     *
     * Has its own regexes (SXE/SEASON/EPISODE) for the episode and for the season when the name
     * carries it glued on; it only falls back to [seasonOf] as a last resort, when none of those
     * find a season and `section` does carry something. That's why [seasonOf]'s regex shouldn't be
     * widened to swallow more formats: that would also touch this fallback, not just an external
     * consumer — here the worst case of getting it wrong is a weird label, not losing or making up
     * the real number.
     */
    fun displayLabel(section: String?, displayName: String): String? {
        SXE.find(displayName)?.let { m ->
            val e = m.groupValues[2].toIntOrNull()
            if (e != null) {
                val s = m.groupValues[1].toIntOrNull()
                return if (s != null) "T$s · E$e" else "E$e"
            }
        }
        val episode = EPISODE.find(displayName)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val season = SEASON.find(displayName)?.groupValues?.get(1)?.toIntOrNull()
            ?: section?.takeIf { it.isNotBlank() }?.let { seasonOf(it) }
        return if (season != null) "T$season · E$episode" else "E$episode"
    }
}
