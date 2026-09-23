package com.arkiv.player.ui.catalog

import com.arkiv.player.data.gateway.GatewayEpisode

/**
 * How chapters are listed in a season's window (phone: `MagisSeasonDialog`; TV:
 * `TvMagisSeasonContent`) when the list brings several seasons.
 *
 * Exists because of Caracol: a `GROUP_OF_BUNDLES` arrives as ONE list with all its seasons
 * flattened (`DituEpisodes`), and each season can bring its own chapter 1. If Caracol also sends
 * no `episodeTitle`, the title falls back to "Episodio N" (`DituEpisodes`) and T1's 1 ends up
 * identical to T2's 1. With several seasons, each row states its own and the list goes by season
 * and then by number.
 *
 * With `season` null --Magis never sends it: `MagisSource` doesn't pass it-- or with a single
 * season, everything stays as it was: the order it arrived in and the bare number.
 */
object ChaptersBySeason {

    /** Whether the list brings more than one distinct season. */
    fun hasMultipleSeasons(chapters: List<GatewayEpisode>): Boolean =
        chapters.mapNotNull { it.season }.distinct().size > 1

    /** In the order they're shown: the same they arrived in, except with several seasons. */
    fun sorted(chapters: List<GatewayEpisode>): List<GatewayEpisode> =
        if (!hasMultipleSeasons(chapters)) chapters
        else chapters.sortedWith(compareBy({ it.season ?: 0 }, { it.number }))

    /**
     * "T2 · E1" when there are [hasMultipleSeasons] and the chapter brings its own; if not,
     * [noSeason] followed by the number: the phone paints the bare number (`""`) and the TV "E"
     * plus the number.
     */
    fun label(chapter: GatewayEpisode, multipleSeasons: Boolean, noSeason: String = ""): String {
        val season = chapter.season
        return if (multipleSeasons && season != null) "T$season · E${chapter.number}"
        else "$noSeason${chapter.number}"
    }
}
