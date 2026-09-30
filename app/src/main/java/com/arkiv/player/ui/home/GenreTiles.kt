package com.arkiv.player.ui.home

import com.arkiv.player.data.plugin.Genre
import com.arkiv.player.data.plugin.PluginHomeRow

/** The heading of the tiles whose title says no genre. */
const val OTHER_TILES_LABEL = "De tus plugins"

/**
 * One tile of the Categorías tab that comes from a plugin's browsable Home row (a row with a `ref`: tapping it opens
 * the same "Ver más" grid). [genre] is the row's declared one, else a guess from its title, else null.
 */
data class GenreTile(
    val pluginId: String,
    val pluginName: String,
    val title: String,
    val previewUrl: String?,
    val genre: String?,
    val ref: String,
)

data class GenreSection(val genre: String?, val label: String, val tiles: List<GenreTile>)

/**
 * The browsable rows of every plugin as tiles. [excludePluginId] is the Xuper plugin: its rows are already the
 * catalog tiles above, so they are not listed twice. Pure.
 */
fun genreTilesOf(rows: List<PluginHomeRow>, excludePluginId: String?): List<GenreTile> =
    rows.mapNotNull { row ->
        val ref = row.ref ?: return@mapNotNull null
        if (row.pluginId == excludePluginId) return@mapNotNull null
        val first = row.items.firstOrNull()
        val preview = first?.extra?.get("backdrop")?.ifBlank { null } ?: first?.extra?.get("poster")?.ifBlank { null }
        GenreTile(row.pluginId, row.pluginName, row.title, preview, Genre.of(row.genre, row.title), ref)
    }

/** Tiles grouped by genre in the vocabulary's order (tiles of different plugins together), the ones with no genre last. */
fun genreSectionsOf(tiles: List<GenreTile>): List<GenreSection> {
    val byGenre = Genre.IDS.mapNotNull { id ->
        tiles.filter { it.genre == id }.takeIf { it.isNotEmpty() }?.let { GenreSection(id, Genre.label(id), it) }
    }
    val rest = tiles.filter { it.genre == null }.takeIf { it.isNotEmpty() }?.let { GenreSection(null, OTHER_TILES_LABEL, it) }
    return byGenre + listOfNotNull(rest)
}
