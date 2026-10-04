package app.kino.demo.data

import android.content.Context
import org.json.JSONObject

/** One film of the demo catalog. */
data class Film(
    val id: String,
    val title: String,
    val year: Int,
    val durationMin: Int,
    val synopsis: String,
    val posterUrl: String,
    val videoUrl: String,
    val sourceUrl: String,
)

/** A titled row of films on Home. */
data class CatalogRow(val id: String, val title: String, val films: List<Film>)

/** "1940 · 1 h 32 min": the year and the running time, omitting what is unknown. */
fun Film.metaLine(): String {
    val duration = when {
        durationMin <= 0 -> ""
        durationMin < 60 -> "$durationMin min"
        durationMin % 60 == 0 -> "${durationMin / 60} h"
        else -> "${durationMin / 60} h ${durationMin % 60} min"
    }
    return listOf(if (year > 0) year.toString() else "", duration).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * Parses the bundled `home.json`. Rows without a title and items without a title or a video are
 * skipped instead of failing the whole catalog.
 */
fun parseCatalog(json: String): List<CatalogRow> {
    val rows = JSONObject(json).optJSONArray("rows") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { r ->
        val row = rows.optJSONObject(r) ?: return@mapNotNull null
        val items = row.optJSONArray("items")
        val films = if (items == null) emptyList() else (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title")
            val video = o.optString("video")
            if (title.isBlank() || video.isBlank()) return@mapNotNull null
            Film(
                id = o.optString("id", title),
                title = title,
                year = o.optInt("year", 0),
                durationMin = o.optInt("durationMin", 0),
                synopsis = o.optString("synopsis"),
                posterUrl = o.optString("poster"),
                videoUrl = video,
                sourceUrl = o.optString("source"),
            )
        }
        val title = row.optString("title")
        if (title.isBlank() || films.isEmpty()) null else CatalogRow(row.optString("id", title), title, films)
    }
}

/** Reads and parses `assets/home.json`. */
fun loadCatalog(context: Context): List<CatalogRow> =
    context.assets.open("home.json").bufferedReader().use { parseCatalog(it.readText()) }

/** Every film of the catalog once, in row order (a film can sit in several rows). */
fun allFilms(rows: List<CatalogRow>): List<Film> = rows.flatMap { it.films }.distinctBy { it.id }

/**
 * The films whose title or synopsis contains every word of [query], ignoring case and accents.
 * A blank query matches everything.
 */
fun searchFilms(rows: List<CatalogRow>, query: String): List<Film> {
    val words = fold(query).split(' ').filter { it.isNotBlank() }
    val films = allFilms(rows)
    if (words.isEmpty()) return films
    return films.filter { film ->
        val haystack = fold("${film.title} ${film.synopsis} ${film.year}")
        words.all { it in haystack }
    }
}

private fun fold(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
