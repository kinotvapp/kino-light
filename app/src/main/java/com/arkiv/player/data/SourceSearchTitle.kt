package com.arkiv.player.data

/**
 * A library title as the search needs it to list every source that has it: what the player's
 * "Ver otras fuentes" opens when a plugin source doesn't play (see
 * `ArkivRepository.sourceSearchTitle` and `otherSourcesRoute`).
 */
data class SourceSearchTitle(
    val isMovie: Boolean,
    /** TMDB's id when the item has one: the search then starts from TMDB's own card. */
    val tmdbId: Int?,
    val title: String,
)
