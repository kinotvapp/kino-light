package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.CatalogSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisHomeClassifierTest {

    private fun item(id: String, tags: String = "", score: Double? = 7.0, type: String = "movie") =
        CatalogItem(
            id = id, title = id, poster = null, durationS = 0, type = type,
            genres = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            score = score,
        )

    private fun section(name: String, items: List<CatalogItem>) =
        CatalogSection(id = 0, name = name, adult = false, items = items)

    private fun many(prefix: String, n: Int, tags: String, score: Double = 7.0, type: String = "movie") =
        (1..n).map { item("$prefix$it", tags, score, type) }

    private fun rows(vararg roots: Pair<String, List<CatalogItem>>) =
        MagisHomeClassifier.classify(roots.associate { (root, items) -> root to listOf(section("All", items)) })

    private fun List<MagisHomeRow>.row(id: String) = first { it.id == id }

    @Test
    fun `a genre with six titles gets a row, one with five doesn't`() {
        val result = rows("peliculas" to many("a", 6, "Action") + many("h", 5, "Horror"))

        assertTrue(result.any { it.id == "magis_g_peliculas_action" })
        assertFalse(result.any { it.id == "magis_g_peliculas_horror" })
    }

    @Test
    fun `rows use our own Spanish genre and type names`() {
        val result = rows(
            "peliculas" to many("a", 6, "Action"),
            "series" to many("s", 6, "Sci-Fi", type = "teleplay"),
        )

        assertEquals("Acción · Películas", result.row("magis_g_peliculas_action").title)
        assertEquals("Ciencia ficción · Series", result.row("magis_g_series_scifi").title)
    }

    @Test
    fun `Music and Musical are the same row`() {
        val result = rows("peliculas" to many("m", 3, "Music") + many("u", 3, "Musical"))

        val music = result.row("magis_g_peliculas_music")
        assertEquals("Música · Películas", music.title)
        assertEquals(6, music.all.size)
    }

    @Test
    fun `tags that aren't genres make no row`() {
        val result = rows("peliculas" to many("x", 8, "Short, 2021, Animation, Anime, Cartoon"))

        assertFalse(result.any { it.id.startsWith("magis_g_") })
    }

    @Test
    fun `trailers never reach the home`() {
        val trailer = item("t1", "Action", score = 9.9, type = "trailer")
        val result = rows("peliculas" to many("a", 6, "Action") + trailer)

        assertFalse(result.row("magis_g_peliculas_action").all.any { it.id == "t1" })
        assertFalse(result.row("magis_top_peliculas").all.any { it.id == "t1" })
    }

    @Test
    fun `a title in several roots keeps the most specific kind`() {
        val shared = item("x", "Action")
        val result = rows(
            "peliculas" to many("p", 5, "Action") + shared,
            "anime" to many("n", 5, "Action") + shared,
        )

        assertTrue(result.row("magis_g_anime_action").all.any { it.id == "x" })
        // Without "x" the movies' Action has only 5: no row.
        assertFalse(result.any { it.id == "magis_g_peliculas_action" })
    }

    @Test
    fun `recent movie uploads come from the newest plain year section`() {
        val result = MagisHomeClassifier.classify(
            mapOf(
                "peliculas" to listOf(
                    section("2025", many("old", 3, "Drama")),
                    section("2026", many("new", 2, "Drama")),
                    section("HBO Movie", many("hbo", 3, "Drama")),
                ),
                "series" to listOf(section("HBO Series", many("s", 3, "Drama", type = "teleplay"))),
            ),
        )

        val recent = result.row("magis_recent_peliculas")
        assertEquals("Recién agregadas · Películas", recent.title)
        assertEquals(listOf("new1", "new2"), recent.shown.map { it.id })
        // No theatrical section: no cinema row, and the plain year isn't reused for it.
        assertFalse(result.any { it.id == "magis_new_peliculas" })
        // Series have no year section: no row for them.
        assertFalse(result.any { it.id == "magis_new_series" })
    }

    /** Measured 2026-09-28: plain "2026" is strictly newest-first by shelveTime (~3 uploads a day),
     *  so its portal order IS "latest uploads" and must never be re-sorted by score. */
    @Test
    fun `recent uploads keep the portal's order`() {
        val upload = listOf(item("u1", score = 4.0), item("u2", score = 9.0), item("u3", score = 6.0))
        val result = MagisHomeClassifier.classify(mapOf("peliculas" to listOf(section("2026", upload))))

        assertEquals(listOf("u1", "u2", "u3"), result.row("magis_recent_peliculas").shown.map { it.id })
        assertEquals(listOf("u1", "u2", "u3"), result.row("magis_recent_peliculas").all.map { it.id })
    }

    /** Measured 2026-09-28: "2026 Peliculas teatrales" hasn't moved since 2026-01-22, so it's kept,
     *  but as the cinema-releases row, not the year's "Estrenos". */
    @Test
    fun `the theatrical section is its own cinema row, next to the recent uploads one`() {
        val result = MagisHomeClassifier.classify(
            mapOf(
                "peliculas" to listOf(
                    section("2026", many("upload", 3, "Comedy")),
                    section("2026 Peliculas teatrales", many("cinema", 2, "Action")),
                ),
            ),
        )

        assertEquals(listOf("upload1", "upload2", "upload3"), result.row("magis_recent_peliculas").shown.map { it.id })
        val cinema = result.row("magis_new_peliculas")
        assertEquals("Estrenos de cine", cinema.title)
        assertEquals(listOf("cinema1", "cinema2"), cinema.shown.map { it.id })
    }

    @Test
    fun `a theatrical section alone makes the cinema row and no recent row`() {
        val result = MagisHomeClassifier.classify(
            mapOf("peliculas" to listOf(section("2026 Peliculas teatrales", many("cinema", 2, "Action")))),
        )

        assertEquals(listOf("cinema1", "cinema2"), result.row("magis_new_peliculas").shown.map { it.id })
        assertFalse(result.any { it.id == "magis_recent_peliculas" })
    }

    @Test
    fun `the theatrical section is found regardless of accents and case`() {
        val result = MagisHomeClassifier.classify(
            mapOf("peliculas" to listOf(section("2026 PELÍCULAS TEATRALES", many("cinema", 2, "Action")))),
        )

        assertEquals(listOf("cinema1", "cinema2"), result.row("magis_new_peliculas").shown.map { it.id })
    }

    @Test
    fun `the newest theatrical section wins`() {
        val result = MagisHomeClassifier.classify(
            mapOf(
                "peliculas" to listOf(
                    section("2025 Peliculas teatrales", many("old", 2, "Action")),
                    section("2026 Peliculas teatrales", many("new", 2, "Action")),
                ),
            ),
        )

        val cinema = result.row("magis_new_peliculas")
        assertEquals("Estrenos de cine", cinema.title)
        assertEquals(listOf("new1", "new2"), cinema.shown.map { it.id })
    }

    /** The year rolls over: nothing is pinned to 2026 or to a columnId. */
    @Test
    fun `next year's plain section takes over once it has titles`() {
        val result = MagisHomeClassifier.classify(
            mapOf(
                "peliculas" to listOf(section("2026", many("old", 2, "Drama")), section("2027", many("new", 2, "Drama"))),
                "series" to listOf(section("2026", many("so", 2, "Drama", type = "teleplay")), section(" 2027 ", many("sn", 2, "Drama", type = "teleplay"))),
            ),
        )

        assertEquals(listOf("new1", "new2"), result.row("magis_recent_peliculas").shown.map { it.id })
        assertEquals(listOf("sn1", "sn2"), result.row("magis_new_series").shown.map { it.id })
    }

    @Test
    fun `an empty new year section falls back to the previous year`() {
        val trailer = item("t", type = "trailer")
        val result = MagisHomeClassifier.classify(
            mapOf(
                "peliculas" to listOf(section("2027", listOf(trailer)), section("2026", many("old", 2, "Drama"))),
                "series" to listOf(section("2027", emptyList()), section("2026", many("so", 2, "Drama", type = "teleplay"))),
            ),
        )

        assertEquals(listOf("old1", "old2"), result.row("magis_recent_peliculas").shown.map { it.id })
        assertEquals(listOf("so1", "so2"), result.row("magis_new_series").shown.map { it.id })
    }

    /** Measured 2026-09-28: the portal re-shelves a series on every new episode, so its plain year
     *  section is "shows updated most recently", not new series. */
    @Test
    fun `the plain year series section is the shows with new episodes, in portal order`() {
        val shows = listOf(item("s1", score = 5.0, type = "teleplay"), item("s2", score = 9.0, type = "teleplay"))
        val result = MagisHomeClassifier.classify(mapOf("series" to listOf(section("2026", shows))))

        val updated = result.row("magis_new_series")
        assertEquals("Series con capítulos nuevos", updated.title)
        assertEquals(listOf("s1", "s2"), updated.shown.map { it.id })
    }

    @Test
    fun `top rated keeps twenty, best first, and every title in all`() {
        val movies = (1..25).map { item("m$it", "Drama", score = it.toDouble()) }
        val top = rows("peliculas" to movies).row("magis_top_peliculas")

        assertEquals("Películas mejor valoradas", top.title)
        assertEquals(20, top.shown.size)
        assertEquals("m25", top.shown.first().id)
        assertEquals(25, top.all.size)
    }

    @Test
    fun `a genre row prefers titles not shown in an earlier genre row`() {
        // Drama (30) comes before Action (26). Drama shows a1..a20 (the best scored), so Action
        // shows its six unseen titles first and only then repeats a's to fill up to twenty.
        val result = rows(
            "peliculas" to many("a", 20, "Drama, Action", score = 9.0) +
                many("e", 10, "Drama", score = 2.0) +
                many("c", 6, "Action", score = 1.0),
        )

        val action = result.row("magis_g_peliculas_action")
        assertEquals((1..6).map { "c$it" }, action.shown.take(6).map { it.id })
        assertEquals(20, action.shown.size)
        // "Ver todo" isn't affected by what was shown: best scored first.
        assertEquals(9.0, action.all.first().score!!, 0.0)
        assertEquals(26, action.all.size)
    }

    @Test
    fun `genre rows alternate between kinds, bigger genres first`() {
        val result = rows(
            "peliculas" to many("a", 6, "Action") + many("c", 6, "Comedy"),
            "series" to many("s", 6, "Drama", type = "teleplay"),
        )

        val genreIds = result.map { it.id }.filter { it.startsWith("magis_g_") }
        // Same size in movies: ordered by label (Acción < Comedia).
        assertEquals(
            listOf("magis_g_peliculas_action", "magis_g_series_drama", "magis_g_peliculas_comedy"),
            genreIds,
        )
    }

    @Test
    fun `featured rows come before genre rows`() {
        val result = MagisHomeClassifier.classify(
            mapOf("peliculas" to listOf(section("2026", many("n", 2, "Action")), section("All", many("a", 6, "Action")))),
        )

        assertEquals(
            listOf("magis_recent_peliculas", "magis_top_peliculas", "magis_g_peliculas_action"),
            result.map { it.id },
        )
    }

    @Test
    fun `recent uploads lead the home and the cinema row closes the featured rows`() {
        val result = MagisHomeClassifier.classify(
            mapOf(
                "peliculas" to listOf(
                    section("2026", many("n", 2, "Action")),
                    section("2026 Peliculas teatrales", many("c", 2, "Action")),
                    section("All", many("a", 6, "Action")),
                ),
                "series" to listOf(section("2026", many("s", 6, "Drama", type = "teleplay"))),
            ),
        )

        assertEquals(
            listOf(
                "magis_recent_peliculas", "magis_new_series", "magis_top_peliculas", "magis_top_series",
                "magis_new_peliculas", "magis_g_peliculas_action", "magis_g_series_drama",
            ),
            result.map { it.id },
        )
    }

    /** `PluginOutput.MAX_ROWS` keeps the first 20 rows: the featured ones must all survive it and
     *  the rows it drops must be the tail of the genre round-robin (the smallest genres). */
    @Test
    fun `under the twenty row cap every featured row survives and only small genres drop`() {
        val genres = listOf("Action", "Comedy", "Drama", "Thriller", "Crime", "Sci-Fi", "Fantasy", "Romance", "Mystery", "Horror")
        // Movie genre i has 20 - i titles: Action is the biggest, Horror the smallest.
        val movies = genres.flatMapIndexed { i, g -> many("m$i-", 20 - i, g) }
        val series = genres.flatMapIndexed { i, g -> many("s$i-", 20 - i, g, type = "teleplay") }
        val result = MagisHomeClassifier.classify(
            mapOf(
                "peliculas" to listOf(
                    section("2026", many("n", 3, "Drama")),
                    section("2026 Peliculas teatrales", many("c", 3, "Drama")),
                    section("All", movies),
                ),
                "series" to listOf(section("2026", many("u", 3, "Drama", type = "teleplay")), section("All", series)),
            ),
        )

        val kept = result.take(20).map { it.id }
        val dropped = result.drop(20).map { it.id }
        assertTrue(kept.containsAll(listOf("magis_recent_peliculas", "magis_new_series", "magis_top_peliculas", "magis_top_series", "magis_new_peliculas")))
        assertTrue(dropped.isNotEmpty())
        assertTrue(dropped.all { it.startsWith("magis_g_") })
        assertTrue("magis_g_peliculas_horror" in dropped)
        assertTrue("magis_g_peliculas_action" in kept)
    }

    @Test
    fun `featured ids are the recent, release and top rated rows, never a genre`() {
        assertTrue(MagisHomeClassifier.isFeatured("magis_recent_peliculas"))
        assertTrue(MagisHomeClassifier.isFeatured("magis_new_series"))
        assertTrue(MagisHomeClassifier.isFeatured("magis_top_peliculas"))
        assertFalse(MagisHomeClassifier.isFeatured("magis_g_peliculas_action"))
        assertFalse(MagisHomeClassifier.isFeatured("continue_watching"))
    }

    @Test
    fun `nothing loaded means no rows`() {
        assertEquals(emptyList<MagisHomeRow>(), MagisHomeClassifier.classify(emptyMap()))
    }

    @Test
    fun `genre labels translate and drop what isn't a genre`() {
        assertEquals(
            listOf("Acción", "Drama"),
            MagisHomeClassifier.genreLabels(item("x", "Action, Short, Drama, Action")),
        )
    }

    @Test
    fun `recent uploads keep the root's year section even for titles another root claims`() {
        val shared = item("x", "Action")
        val result = MagisHomeClassifier.classify(
            mapOf(
                "peliculas" to listOf(
                    section("2026", listOf(shared) + many("p", 1, "Action")),
                    section("All", many("a", 6, "Action")),
                ),
                "anime" to listOf(section("All", listOf(shared) + many("n", 5, "Action"))),
            ),
        )

        // "x" is in peliculas' year section: it belongs in Recién agregadas · Películas.
        assertTrue(result.row("magis_recent_peliculas").shown.any { it.id == "x" })
        // "x" is an anime title (precedence): it's NOT in top-rated movies.
        assertFalse(result.row("magis_top_peliculas").all.any { it.id == "x" })
    }

    // --- "Recién agregadas" and "Series con capítulos nuevos": newest upload first, by the upload date when we have it.

    private fun shelved(id: String, at: Long, type: String = "movie") =
        CatalogItem(id = id, title = id, poster = null, durationS = 0, type = type, shelvedAtMs = at)

    @Test
    fun `recent movies are ordered by upload date, whatever order the portal sent`() {
        val result = MagisHomeClassifier.classify(
            mapOf("peliculas" to listOf(section("2026", listOf(shelved("old", 100), shelved("newest", 300), shelved("mid", 200))))),
        )
        assertEquals(listOf("newest", "mid", "old"), result.row("magis_recent_peliculas").shown.map { it.id })
    }

    @Test
    fun `without upload dates the portal's order is kept`() {
        val result = MagisHomeClassifier.classify(
            mapOf("peliculas" to listOf(section("2026", listOf(shelved("b", 0), shelved("a", 0), shelved("c", 0))))),
        )
        assertEquals(listOf("b", "a", "c"), result.row("magis_recent_peliculas").shown.map { it.id })
    }

    @Test
    fun `an item with no date sits after the dated ones, in its own order`() {
        val result = MagisHomeClassifier.classify(
            mapOf("peliculas" to listOf(section("2026", listOf(shelved("nodate1", 0), shelved("dated", 500), shelved("nodate2", 0))))),
        )
        assertEquals(listOf("dated", "nodate1", "nodate2"), result.row("magis_recent_peliculas").shown.map { it.id })
    }

    @Test
    fun `series with new chapters are ordered by upload date too`() {
        val result = MagisHomeClassifier.classify(
            mapOf("series" to listOf(section("2026", listOf(shelved("s1", 10, "teleplay"), shelved("s2", 20, "teleplay"))))),
        )
        assertEquals(listOf("s2", "s1"), result.row("magis_new_series").shown.map { it.id })
    }
}
