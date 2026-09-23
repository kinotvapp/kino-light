package com.arkiv.player.data.newcontent

import com.arkiv.player.data.ditu.DituSource

/**
 * A library series with just enough to decide whether it's worth checking.
 *
 * Deliberately flat and with no Room types: this way the decision of who to ask can be tested
 * with no database, which is where things can really go wrong.
 */
data class SeriesCandidate(
    val itemId: String,
    /** "magis" | "ditu" today; legacy rows can still carry "archive" | "web" | "torrent". */
    val source: String,
    /** When something of this series last played. 0 = never. */
    val lastWatchedMs: Long,
    /** Episodes the library has today for this series. */
    val episodeCount: Int,
)

/**
 * Which series to ask whether a new chapter came out, when the app opens.
 *
 * The check costs network -- and for web, a search per candidate chapter -- so asking the
 * library's 50 series on every launch would spend battery and data on 45 nobody's watching. The
 * filters here are what makes this cheap enough to run every time:
 *
 * - **With recent progress.** "The series I'm watching" is literally that: one where you left a
 *   mark not long ago. If you pick it back up within a year, its own progress brings it back on
 *   its own.
 * - **Series, not movies.** A single-episode item has no next chapter.
 * - **The freshest first, and capped.** If you follow 40 series, the 10 you last touched are the
 *   ones that matter today; the rest get their turn on the next launch.
 */
object SeriesToCheck {

    /** How many series get checked per launch. */
    const val MAX_SERIES = 10

    /** How far back counts as "I'm watching this". */
    const val WINDOW_DAYS = 30L

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * Sources this check lets through. "magis" and "ditu" (Caracol) both lead somewhere today
     * (`NewChapterFinder.checkMagis`/`checkDitu`). "archive" and "web" were removed in this
     * branch's pruning and are left out on purpose: keeping them here only cost a real series a
     * slot, since `NewChapterFinder` no-ops on both. Torrent stays out too (see the spec).
     */
    private val SOURCES = setOf("magis", DituSource.SOURCE)

    fun choose(candidates: List<SeriesCandidate>, nowMs: Long): List<SeriesCandidate> {
        val floor = nowMs - WINDOW_DAYS * DAY_MS
        return candidates.asSequence()
            .filter { it.source in SOURCES }
            .filter { it.episodeCount > 1 }
            .filter { it.lastWatchedMs > 0L && it.lastWatchedMs >= floor }
            .sortedByDescending { it.lastWatchedMs }
            .take(MAX_SERIES)
            .toList()
    }
}
