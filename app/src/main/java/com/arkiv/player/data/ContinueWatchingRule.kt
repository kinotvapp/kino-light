package com.arkiv.player.data

/** The progress of ONE chapter, with the minimum [ContinueWatchingRule] needs to decide. */
data class ChapterProgress(
    val episodeId: String,
    val positionMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long,
)

/**
 * A chapter's progress alongside the item it belongs to and the chapter that follows it, which is
 * the shape it comes out of the database in to build the home row in a single pass (see
 * `PlaybackDao.observeProgressWithNext`).
 */
data class ItemProgress(
    val itemId: String,
    val progress: ChapterProgress,
    val nextEpisodeId: String?,
)

/** The chapter that has to be offered, and since when the series counts as "touched". */
data class ChapterToOffer(
    val episodeId: String,
    /**
     * The `lastPlayedAt` of the ANCHOR (the last thing you played of the series), not the offered
     * chapter's --which has none when [isNext]--. It's what orders the home row.
     */
    val lastPlayedAt: Long,
    /**
     * true if the one offered is the chapter that FOLLOWS the anchor, meaning: you finished the
     * previous one and never touched this one.
     *
     * It does NOT mean "has no saved position". A chapter you opened and closed after three
     * seconds also starts at zero, but it's where you're at, and the detail screen marks it as
     * current (see `ItemDetail.inProgressEpisode`). The difference matters: the anchor is a
     * chapter you're in, the next one is one you haven't reached yet.
     */
    val isNext: Boolean,
)

/**
 * Where you're at in a series.
 *
 * Lives here, alongside [WatchedThreshold] and for the same reason: it's a product rule that
 * feeds TWO surfaces -- the home's "Continue watching" row and the detail screen's "Reproducir"
 * button -- and keeping it in two places is exactly how it broke before.
 *
 * ### The anchor is the last thing you played, FINISHED OR NOT
 *
 * The previous rule looked for "the most recent UNFINISHED chapter". Reported on device
 * (2026-08-13) and verified against the Fire TV's database: Dragon Ball watched through e136
 * --127 through 136 all finished that night-- plus an e104 abandoned at 38% the morning before.
 * Since e104 was the only unfinished one, it was "the most recent unfinished", so the home put it
 * on Dragon Ball's card and the detail screen offered to play it: both surfaces sent you thirty
 * chapters back, and the series you were actually watching didn't show up anywhere.
 *
 * Here the anchor is simply the most recent playback, period. If that one was left halfway, it's
 * where you're at; if you finished it, you're on the one that follows. An abandoned chapter can't
 * come back to life because, unless it's the last thing you touched, it's never the anchor.
 *
 * ### Chapters only OPENED don't displace ones actually played
 *
 * `markInProgress` writes a row at `positionMs == 0` just from opening a chapter. Without the
 * preference for the ones with a position, opening three chapters without playing them turned the
 * last one into "where I'm at" ahead of the one you were actually watching (measured on Dragon
 * Ball on 2026-08-12: e126 at 3:30 lost to e127 and e128, opened later and at 0). That's why the
 * anchor is looked for first among the ones with real playback, and the merely-opened ones are
 * the fallback.
 *
 * ### There's NO second-count floor here
 *
 * [choose] answers "where you're at" and nothing else: a chapter with two seconds played is where
 * you're at if it's the last thing you touched. The floor ("don't fill my home with what I opened
 * for three seconds") is a rule of the ROW, not of the series, and that's why it lives in
 * [byItem]. Putting it in here would bring back the bug `inProgressEpisode` fixed: hitting play on
 * E5, leaving after three seconds, and the detail screen telling you "you're on E1".
 */
object ContinueWatchingRule {

    /**
     * @param progressRows every progress row for ONE item (other items' rows don't go here).
     * @param nextIdOf given an episodeId, the one that follows it in the item's list, or null if
     *   it's the last one. The list lives in a different place depending on who's calling (the
     *   detail screen has it in memory, the home resolves it in SQL), so it comes in as a function
     *   and not a list.
     * @return the chapter to offer, or null if there's nothing to continue for this item.
     */
    fun choose(
        progressRows: List<ChapterProgress>,
        nextIdOf: (String) -> String?,
    ): ChapterToOffer? {
        if (progressRows.isEmpty()) return null
        val withPlayback = progressRows.filter { it.positionMs > 0 }
        // The episodeId tiebreak isn't cosmetic: two rows with the same lastPlayedAt (sync, clocks
        // that tie to the millisecond) have to ALWAYS give the same answer, or the home card
        // changes on its own between emissions.
        val anchor = withPlayback.ifEmpty { progressRows }
            .maxWithOrNull(compareBy({ it.lastPlayedAt }, { it.episodeId })) ?: return null

        return if (!anchor.watched) {
            ChapterToOffer(anchor.episodeId, anchor.lastPlayedAt, isNext = false)
        } else {
            // You finished it: the next one goes. If there's no next one, you finished the series
            // (or it was a movie) and there's nothing to continue.
            nextIdOf(anchor.episodeId)
                ?.let { ChapterToOffer(it, anchor.lastPlayedAt, isNext = true) }
        }
    }

    /**
     * The entire "Continue watching" row: ONE card per item, from the most recently touched series
     * to the oldest.
     *
     * One card per ITEM and not per chapter because the query brings one row per chapter with
     * progress, and so a single series would fill the row with the same cover repeated
     * (GetBackers got to nine cards). Grouped by `itemId`, NOT by title: two different items that
     * happen to share a name are two different things.
     *
     * @param minPositionMs the row's floor. An item gets in if you finished something of it (then
     *   you're really watching it, even if the current chapter is at three seconds) or if some
     *   chapter of it passed the floor. What falls off is what you touched for a moment and never
     *   again: a movie you opened for twenty seconds has no reason to occupy the row forever.
     */
    fun byItem(
        rows: List<ItemProgress>,
        minPositionMs: Long,
        limit: Int = 20,
    ): List<ChapterToOffer> =
        rows.groupBy { it.itemId }
            .values
            .mapNotNull { forItem ->
                val progress = forItem.map { it.progress }
                val isBeingWatched = progress.any { it.watched || it.positionMs > minPositionMs }
                if (!isBeingWatched) return@mapNotNull null
                val nextIds = forItem.associate { it.progress.episodeId to it.nextEpisodeId }
                choose(progress) { nextIds[it] }
            }
            // The episodeId tiebreak, same as in [choose], keeps the row from reordering itself
            // between emissions when two series tie to the millisecond.
            .sortedWith(compareByDescending<ChapterToOffer> { it.lastPlayedAt }.thenBy { it.episodeId })
            .take(limit)
}
