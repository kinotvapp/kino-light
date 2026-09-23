package com.arkiv.player.ui.catalog

import com.arkiv.player.data.ditu.DituItem

/**
 * The Caracol catalog split into series and movies, with duplicates removed.
 *
 * Shared by the TV Caracol section ([com.arkiv.player.ui.tv.TvCaracolScreen]) and the phone one
 * ([CaracolScreen]): both need the SAME rule, so a title doesn't end up in one section on the TV
 * and another on the phone from porting the logic twice by accident.
 *
 * Deduped by `ref()`: that's the key each card uses in its lazy list/grid, and a repeated key
 * there crashes the screen.
 */
internal data class CaracolCatalog(val series: List<DituItem>, val movies: List<DituItem>) {
    companion object {
        fun split(titles: List<DituItem>): CaracolCatalog {
            val (movies, series) = titles.distinctBy { it.ref() }.partition { it.isMovie }
            return CaracolCatalog(series = series, movies = movies)
        }
    }
}
