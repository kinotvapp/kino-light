package com.arkiv.player.data.plugin.catalog

import java.text.Normalizer

private val COMBINING_MARKS = Regex("\\p{M}+")
private val WHITESPACE = Regex("\\s+")

/** Lower-case with the accents taken off, so "película" and "PELICULA" compare equal. */
internal fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase()

/** The entries whose name, description or tags contain EVERY word of [query]; blank = all, order kept. */
fun filterCatalog(entries: List<CatalogEntry>, query: String): List<CatalogEntry> {
    val words = fold(query).split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.isEmpty()) return entries
    return entries.filter { entry ->
        val haystack = fold("${entry.name} ${entry.description} ${entry.tags.joinToString(" ")}")
        words.all { it in haystack }
    }
}
