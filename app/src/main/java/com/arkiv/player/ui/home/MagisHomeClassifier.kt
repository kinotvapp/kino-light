package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.CatalogSection
import java.text.Normalizer

/** The four VOD roots the home reads. [root] is `MagisLiveCatalog.tree`'s key, [label] what rows say. */
enum class MagisKind(val root: String, val label: String) {
    PELICULAS("peliculas", "Películas"),
    SERIES("series", "Series"),
    ANIME("anime", "Anime"),
    INFANTIL("infantil", "Infantil"),
}

/** A home row built from the Magis catalog: [shown] is what the row draws, [all] feeds "Ver todo". */
data class MagisHomeRow(
    val id: String,
    val title: String,
    val shown: List<CatalogItem>,
    val all: List<CatalogItem>,
)

/**
 * Turns the Magis catalog roots into the home's rows, grouped by our own type × genre instead of
 * the portal's sections -- which mix platforms ("HBO Movie"), years, one-off themes ("Dwayne
 * Johnson") and a few genres. Every list item already carries IMDb-style genres in `tags`
 * (measured 2026-09-18: 529/529), so this needs no TMDB/AniList lookup.
 *
 * See docs/superpowers/specs/2026-09-18-magis-home-categorization-design.md for the measurements
 * behind each rule.
 */
object MagisHomeClassifier {
    const val MIN_GENRE_SIZE = 6
    const val MAX_ROW_SIZE = 20

    private const val TRAILER = "trailer"

    /** When a title is in several roots, the most specific one wins. */
    private val PRECEDENCE = listOf(MagisKind.ANIME, MagisKind.INFANTIL, MagisKind.SERIES, MagisKind.PELICULAS)

    /** Kinds that get "mejor valoradas" rows (and, from their year sections, the recent/release rows). */
    private val FEATURED = listOf(MagisKind.PELICULAS, MagisKind.SERIES)

    /** Portal tag → (key for the row id, Spanish label). Any tag not here is ignored. */
    private val GENRES: Map<String, Pair<String, String>> = mapOf(
        "Action" to ("action" to "Acción"),
        "Adventure" to ("adventure" to "Aventura"),
        "Comedy" to ("comedy" to "Comedia"),
        "Drama" to ("drama" to "Drama"),
        "Thriller" to ("thriller" to "Suspenso"),
        "Crime" to ("crime" to "Crimen"),
        "Sci-Fi" to ("scifi" to "Ciencia ficción"),
        "Fantasy" to ("fantasy" to "Fantasía"),
        "Romance" to ("romance" to "Romance"),
        "Mystery" to ("mystery" to "Misterio"),
        "Horror" to ("horror" to "Terror"),
        "Family" to ("family" to "Familia"),
        "Biography" to ("biography" to "Biografía"),
        "History" to ("history" to "Historia"),
        "Documentary" to ("documentary" to "Documental"),
        "Western" to ("western" to "Western"),
        "War" to ("war" to "Guerra"),
        "Reality-TV" to ("reality" to "Reality"),
        "Sport" to ("sport" to "Deportes"),
        "Music" to ("music" to "Música"),
        "Musical" to ("music" to "Música"),
    )

    /** A section named after a year, optionally followed by more words ("2026 Peliculas teatrales"). */
    private val YEAR_SECTION = Regex("(\\d{4})(.*)")
    private val MARKS = Regex("\\p{Mn}+")

    /** Best score first; ties by title, then id, so the order never depends on the portal's. */
    private val BY_SCORE: Comparator<CatalogItem> =
        compareByDescending<CatalogItem> { it.score ?: -1.0 }.thenBy { it.title }.thenBy { it.id }

    fun genreLabels(item: CatalogItem): List<String> = genresOf(item).map { it.second }

    /** Row id prefixes of the featured (non-genre) rows: recent uploads, releases, top rated. */
    private val FEATURED_PREFIXES = listOf("magis_recent_", "magis_new_", "magis_top_")

    /** Whether [rowId] is a featured row (Categorías' "Destacadas"), as opposed to a genre row. */
    fun isFeatured(rowId: String): Boolean = FEATURED_PREFIXES.any { rowId.startsWith(it) }

    fun classify(roots: Map<String, List<CatalogSection>>): List<MagisHomeRow> {
        val sectionsOf = MagisKind.entries.associateWith { roots[it.root].orEmpty() }
        val kindOf = LinkedHashMap<String, Pair<MagisKind, CatalogItem>>()
        for (kind in PRECEDENCE) {
            for (section in sectionsOf.getValue(kind)) {
                for (item in section.items) {
                    if (item.type != TRAILER) kindOf.putIfAbsent(item.id, kind to item)
                }
            }
        }
        val byKind = MagisKind.entries.associateWith { kind ->
            kindOf.values.filter { it.first == kind }.map { it.second }
        }
        // Order (measured 2026-09-28, see .superpowers/magis-portal-measure.md): the latest movie
        // uploads and the shows with new episodes lead; the theatrical row, frozen since January,
        // closes the featured block -- still inside PluginOutput.MAX_ROWS, so the cap only ever
        // drops the tail of the genre round-robin.
        return listOfNotNull(recentMoviesRow(sectionsOf), updatedSeriesRow(sectionsOf)) +
            topRatedRows(byKind) +
            listOfNotNull(cinemaRow(sectionsOf)) +
            genreRows(byKind)
    }

    private fun genresOf(item: CatalogItem): List<Pair<String, String>> =
        item.genres.mapNotNull { GENRES[it.trim()] }.distinctBy { it.first }

    /**
     * "Recién agregadas · Películas": the newest plain-year movie section ("2026") as the portal
     * orders it. Measured 2026-09-28: strictly newest-first by `shelveTime`, about 3 uploads a day,
     * so the portal's order IS the upload order and is never re-sorted.
     *
     * Release rows deliberately do NOT apply cross-root kind precedence: a 2026 anime film listed
     * in Películas' year section belongs here, not hidden by Anime's claim. Anime/Infantil get no
     * such rows (only [FEATURED] kinds do).
     */
    private fun recentMoviesRow(sectionsOf: Map<MagisKind, List<CatalogSection>>): MagisHomeRow? =
        plainYearSection(sectionsOf.getValue(MagisKind.PELICULAS))?.let { items ->
            row("magis_recent_${MagisKind.PELICULAS.root}", "Recién agregadas · ${MagisKind.PELICULAS.label}", items)
        }

    /**
     * "Series con capítulos nuevos": the newest plain-year series section. Measured 2026-09-28: the
     * portal re-shelves a series each time it gets new episodes, so this is "shows updated most
     * recently" (often a batch of anime seasons), not new series. The id stays `magis_new_series`
     * so snapshots and "Ver más" refs from older versions still resolve.
     */
    private fun updatedSeriesRow(sectionsOf: Map<MagisKind, List<CatalogSection>>): MagisHomeRow? =
        plainYearSection(sectionsOf.getValue(MagisKind.SERIES))?.let { items ->
            row("magis_new_${MagisKind.SERIES.root}", "Series con capítulos nuevos", items)
        }

    /**
     * "Estrenos de cine": the newest "<year> … teatrales" movie section. Kept, but no longer the
     * lead row: measured 2026-09-28 its newest title was shelved 2026-01-22. Never falls back to
     * the plain year section -- that one is already [recentMoviesRow].
     */
    private fun cinemaRow(sectionsOf: Map<MagisKind, List<CatalogSection>>): MagisHomeRow? {
        val items = yearSections(sectionsOf.getValue(MagisKind.PELICULAS))
            .filter { "teatral" in it.suffix }
            .map { playable(it.section) }
            .firstOrNull { it.isNotEmpty() } ?: return null
        return row("magis_new_${MagisKind.PELICULAS.root}", "Estrenos de cine", items)
    }

    private fun row(id: String, title: String, items: List<CatalogItem>) =
        MagisHomeRow(id = id, title = title, shown = items.take(MAX_ROW_SIZE), all = items)

    /** A section named after a year: the year, the rest of its name ([plain]ed) and the section. */
    private class YearSection(val year: Int, val suffix: String, val section: CatalogSection)

    /** The year sections, newest year first. Found by name, never by columnId: the year rolls over. */
    private fun yearSections(sections: List<CatalogSection>): List<YearSection> =
        sections.mapNotNull { s ->
            val m = YEAR_SECTION.matchEntire(s.name.trim()) ?: return@mapNotNull null
            YearSection(m.groupValues[1].toInt(), plain(m.groupValues[2]), s)
        }.sortedByDescending { it.year }

    /**
     * The playable items of the newest year-only section ("2026") that has any, in portal order.
     * A brand-new year's section that is still empty (or only trailers) falls back to last year's.
     */
    private fun plainYearSection(sections: List<CatalogSection>): List<CatalogItem>? =
        yearSections(sections)
            .filter { it.suffix.isEmpty() }
            .map { playable(it.section) }
            .firstOrNull { it.isNotEmpty() }

    private fun playable(section: CatalogSection): List<CatalogItem> =
        section.items.filter { it.type != TRAILER }.distinctBy { it.id }

    /** Lowercase, no accents, trimmed: "PELÍCULAS TEATRALES" → "peliculas teatrales". */
    private fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").trim()

    private fun topRatedRows(byKind: Map<MagisKind, List<CatalogItem>>): List<MagisHomeRow> =
        FEATURED.mapNotNull { kind ->
            val ranked = byKind.getValue(kind).sortedWith(BY_SCORE)
            if (ranked.isEmpty()) return@mapNotNull null
            MagisHomeRow(
                id = "magis_top_${kind.root}",
                title = "${kind.label} mejor valoradas",
                shown = ranked.take(MAX_ROW_SIZE),
                all = ranked,
            )
        }

    /** One genre's candidates for one kind, before deciding what the row shows. */
    private class Group(val kind: MagisKind, val key: String, val label: String, val items: List<CatalogItem>)

    private fun genreRows(byKind: Map<MagisKind, List<CatalogItem>>): List<MagisHomeRow> {
        val perKind: List<List<Group>> = MagisKind.entries.map { kind ->
            val members = LinkedHashMap<String, MutableList<CatalogItem>>()
            val labels = HashMap<String, String>()
            for (item in byKind.getValue(kind)) {
                for ((key, label) in genresOf(item)) {
                    members.getOrPut(key) { mutableListOf() }.add(item)
                    labels[key] = label
                }
            }
            members.filterValues { it.size >= MIN_GENRE_SIZE }
                .map { (key, items) -> Group(kind, key, labels.getValue(key), items) }
                .sortedWith(compareByDescending<Group> { it.items.size }.thenBy { it.label })
        }
        // Round-robin across kinds, so the home doesn't show every movie genre before any series.
        val ordered = buildList {
            val longest = perKind.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) perKind.forEach { groups -> groups.getOrNull(i)?.let(::add) }
        }
        // Titles already drawn by an earlier GENRE row. Featured rows don't count: a top-rated
        // drama must still be findable under Drama.
        val seen = HashSet<String>()
        return ordered.map { group ->
            val shown = group.items
                .sortedWith(compareBy<CatalogItem> { it.id in seen }.then(BY_SCORE))
                .take(MAX_ROW_SIZE)
            seen += shown.map { it.id }
            MagisHomeRow(
                id = "magis_g_${group.kind.root}_${group.key}",
                title = "${group.label} · ${group.kind.label}",
                shown = shown,
                all = group.items.sortedWith(BY_SCORE),
            )
        }
    }
}
