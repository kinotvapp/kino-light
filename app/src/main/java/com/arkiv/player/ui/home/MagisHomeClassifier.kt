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

    /** Kinds that get "Estrenos" and "mejor valoradas" rows. */
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
        return releaseRows(sectionsOf) + topRatedRows(byKind) + genreRows(byKind)
    }

    private fun genresOf(item: CatalogItem): List<Pair<String, String>> =
        item.genres.mapNotNull { GENRES[it.trim()] }.distinctBy { it.first }

    /**
     * Builds "Estrenos" rows from each root's release section as the portal publishes it.
     * Deliberately does NOT apply cross-root kind precedence: a 2026 anime film listed under
     * Películas' release section belongs in "Estrenos · Películas", not hidden from it by Anime's
     * claim. There is no Estrenos row for Anime/Infantil -- not because they lack year sections in
     * the portal, but because [FEATURED] (which this iterates over) only lists Películas/Series.
     * A title can appear in Estrenos under one root and in top-rated under another (winning root
     * by precedence there).
     */
    private fun releaseRows(sectionsOf: Map<MagisKind, List<CatalogSection>>): List<MagisHomeRow> =
        FEATURED.mapNotNull { kind ->
            val (year, section) = releaseSection(kind, sectionsOf.getValue(kind)) ?: return@mapNotNull null
            val items = section.items.filter { it.type != TRAILER }.distinctBy { it.id }
            if (items.isEmpty()) return@mapNotNull null
            MagisHomeRow(
                id = "magis_new_${kind.root}",
                title = "Estrenos $year · ${kind.label}",
                shown = items.take(MAX_ROW_SIZE),
                all = items,
            )
        }

    /**
     * Which section holds a kind's releases, and its year.
     *
     * Movies prefer the newest "<year> … teatrales" section: measured 2026-09-19, the portal's
     * plain "2026" movie section is just its latest uploads (small titles, mostly the same as the
     * top of "All"), while "2026 Peliculas teatrales" holds the year's cinema releases (F1, Tron:
     * Ares, The Running Man…). The plain year section is the fallback. Series use the plain year
     * section, which does hold new seasons (MobLand T2, Strange New Worlds T4…).
     */
    private fun releaseSection(kind: MagisKind, sections: List<CatalogSection>): Pair<String, CatalogSection>? {
        val byYear = sections.mapNotNull { s ->
            val m = YEAR_SECTION.matchEntire(s.name.trim()) ?: return@mapNotNull null
            Triple(m.groupValues[1], plain(m.groupValues[2]), s)
        }
        val theatrical = if (kind == MagisKind.PELICULAS) {
            byYear.filter { "teatral" in it.second }.maxByOrNull { it.first.toInt() }
        } else {
            null
        }
        val chosen = theatrical ?: byYear.filter { it.second.isBlank() }.maxByOrNull { it.first.toInt() }
        return chosen?.let { it.first to it.third }
    }

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
