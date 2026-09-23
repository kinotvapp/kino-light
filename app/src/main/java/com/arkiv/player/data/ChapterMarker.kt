package com.arkiv.player.data

import com.arkiv.player.data.db.SkipMarkerEntity

/**
 * Which intro/outro marker applies to an episode.
 *
 * Times are PER EPISODE and not per series: measured against AniSkip, in Demon Slayer episode 1's
 * opening starts at 1270 s and episode 2's at 57 s (long cold opens, recaps, specials). A single
 * marker per series would send the skip to the middle of the episode.
 *
 * The series marker (empty `episodeId`) does NOT disappear: it's the one set by hand, and still
 * applies to episodes that don't have their own.
 *
 * **Source and precedence.** AniSkip sometimes gets it wrong (measured against the real device: an
 * episode brought the credits labeled as opening) and there's no reliable way to detect it. The
 * way out is for the person to correct it by hand -- so MANUAL wins over automatic **regardless of
 * scope**, and only when the source ties does scope decide (episode > series). If the automatic
 * per-episode one always beat the manual one, a mislabeled time could never be fixed.
 */
object ChapterMarker {

    /** What already exists and what a person sets by hand: holds until something manual corrects it. */
    const val SOURCE_MANUAL = "manual"

    /** What AniSkip brings untouched: yields to any manual correction. */
    const val SOURCE_AUTO = "auto"

    /**
     * The row's key. Derived and not composite on purpose: this branch's future sync (see the
     * `updatedAt`/`deleted` columns kept for it) looks up the remote row by ONE natural field per
     * collection, so a composite key would force changing that mechanism for all of them. Same
     * criterion as `episodes`, which syncs by `epId`.
     */
    fun idFor(itemId: String, episodeId: String): String = "$itemId|$episodeId"

    /**
     * What wins, in order: manual-episode, manual-series, auto-episode, auto-series. Manual beats
     * automatic regardless of scope; when the source ties, episode beats series. A marker with no
     * times at all doesn't count.
     */
    fun choose(fromChapter: SkipMarkerEntity?, fromSeries: SkipMarkerEntity?): SkipMarkerEntity? {
        val chapter = fromChapter?.takeIf { it.hasTimes }
        val series = fromSeries?.takeIf { it.hasTimes }
        val manualChapter = chapter?.takeIf { it.origen == SOURCE_MANUAL }
        val manualSeries = series?.takeIf { it.origen == SOURCE_MANUAL }
        return manualChapter ?: manualSeries ?: chapter ?: series
    }

    /**
     * Does [positionMs] fall inside [marker]'s opening? With no `openingEndMs` there's no opening
     * to mark (a marker may carry only the ending). Extracted from PlayerScreen so it can be
     * tested without Compose -- it's the math that decides whether the "Skip intro" button shows.
     */
    fun inOpening(marker: SkipMarkerEntity?, positionMs: Long): Boolean {
        val end = marker?.openingEndMs ?: return false
        return positionMs in (marker.openingStartMs ?: 0L)..end
    }

    /**
     * Has [positionMs] already entered [marker]'s ending? With no `endingStartMs` there's no
     * ending to mark. Same reason as [inOpening]: the math that decides "Skip outro".
     */
    fun inEnding(marker: SkipMarkerEntity?, positionMs: Long): Boolean {
        val start = marker?.endingStartMs ?: return false
        return positionMs >= start
    }

    private val SkipMarkerEntity.hasTimes: Boolean
        get() = openingEndMs != null || endingStartMs != null
}
