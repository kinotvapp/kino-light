package com.arkiv.player.ui.search

import com.arkiv.player.data.SourceSearchTitle

/**
 * The search route that lists every source of [title]: the player's "Ver otras fuentes" when a
 * plugin source doesn't play (a "Mi biblioteca" card plays its one saved source straight away).
 * The same list a title picked in the search shows.
 */
fun otherSourcesRoute(title: SourceSearchTitle): String {
    val text = "text=${enc(title.title)}"
    val tmdbId = title.tmdbId ?: return "search?$text"
    return "search?kind=${if (title.isMovie) "movie" else "series"}&tmdbId=$tmdbId&$text"
}

/** `%20` for spaces: `URLEncoder`'s `+` would reach the screen as a literal plus (see `TitleRoute`). */
private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
