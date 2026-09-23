package com.arkiv.player.data.model

/**
 * Whether a work is a series (`"tv"`) or a movie (`"movie"`) for TMDB.
 *
 * **Getting this wrong doesn't give "no data", it gives data for ANOTHER WORK**: a TMDB id only
 * means something within its own catalog. Measured in production: `movie:82452` was requested for
 * Avatar, and on TMDB `tv:82452` is "Avatar: The Last Airbender" while `movie:82452` is "Savage
 * Water", a 1979 rafting movie.
 *
 * That's why every signal is checked, from most to least reliable:
 *  1. `itemKind`: the type the source brought (`ItemEntity.tipo`).
 *  2. `categoryOverride`, which is what the app already uses to decide if something is a series,
 *     and can come hand-corrected by the person.
 *  3. Whether THIS episode carries a number.
 *
 * Lived in `TriviaDelPlayer.tipoDe` until `e161b231`; it moved back to the data layer because now
 * the trivia fact and "For you" use it too.
 */
object WorkKind {
    fun of(itemKind: String?, categoryOverride: String?, episode: Int?): String = when {
        itemKind == "tv" || itemKind == "movie" -> itemKind
        categoryOverride == "series" -> "tv"
        categoryOverride == "movie" -> "movie"
        episode != null -> "tv"
        else -> "movie"
    }
}
