package com.arkiv.player.data.newcontent

/**
 * Which chapters to ask the source for when checking a series you're watching.
 *
 * What makes this check expensive is web: the gateway exposes no episode list for that source
 * (`MagisCatalog.detail` is magis-only, the rest answer 422), so it takes **one search per
 * candidate chapter**. With no bounds, following 15 series would be hundreds of searches on every
 * app startup. This object is what sets them.
 *
 * There are two bounds, and each one plugs a different hole:
 *
 * 1. **Only past the highest you already have.** A gap in the middle (you have 1,3,4,5) is almost
 *    always a chapter the source never had — a missing dub, a file the uploader never uploaded.
 *    Searching for it again on every startup means paying for that search forever to get the same
 *    "not there". Anything new, on the other hand, always arrives above.
 *
 * 2. **Cap per series.** A series you left a year ago might suddenly have 20 new chapters;
 *    bringing them all at once would eat the whole startup budget. The first ones are brought in,
 *    the rest on the next check, which is also the order you're going to watch them in.
 */
object MissingChapters {

    /** How many new chapters are brought in per series and per check. */
    const val MAX_PER_SERIES = 5

    /**
     * The numbers to ask the source for: the ones from [inSource] missing from [have], past the
     * highest one already saved, deduped, sorted and capped at [MAX_PER_SERIES].
     *
     * Never suggests deleting anything: if the source reports FEWER than what's saved (happens
     * when an item is re-derived or the portal hides old seasons) the result is empty, not a list
     * of removals.
     */
    fun toFetch(have: Collection<Int>, inSource: Collection<Int>): List<Int> {
        val floor = have.maxOrNull() ?: 0
        return inSource.asSequence()
            .filter { it > floor }
            .distinct()
            .sorted()
            .take(MAX_PER_SERIES)
            .toList()
    }

    /**
     * Season-aware variant of [toFetch], for a source that numbers chapters PER SEASON (Caracol):
     * plain [toFetch] compares against the highest NUMBER stored, so season 2's chapter 1 would
     * look like it's already covered by a season 1 that has ten chapters.
     *
     * The key is `(season ?: 0, number)`, compared season-first. Same two bounds as [toFetch] --
     * only keys past the highest stored key, capped at [MAX_PER_SERIES] -- just applied to the pair
     * instead of a single number.
     */
    fun toFetchBySeason(
        have: Collection<Pair<Int?, Int>>,
        inSource: Collection<Pair<Int?, Int>>,
    ): List<Pair<Int, Int>> {
        fun key(pair: Pair<Int?, Int>): Pair<Int, Int> = (pair.first ?: 0) to pair.second
        val floor = have.map(::key).maxWithOrNull(KEY_ORDER)
        return inSource.asSequence()
            .map(::key)
            .filter { floor == null || KEY_ORDER.compare(it, floor) > 0 }
            .distinct()
            .sortedWith(KEY_ORDER)
            .take(MAX_PER_SERIES)
            .toList()
    }

    /** Season first, then number -- the same order [toFetchBySeason]'s keys compare by. */
    private val KEY_ORDER = compareBy<Pair<Int, Int>>({ it.first }, { it.second })
}
