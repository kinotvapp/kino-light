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
    fun `releases come from the newest year section`() {
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

        val releases = result.row("magis_new_peliculas")
        assertEquals("Estrenos 2026 · Películas", releases.title)
        assertEquals(listOf("new1", "new2"), releases.shown.map { it.id })
        // Series have no year section: no releases row for them.
        assertFalse(result.any { it.id == "magis_new_series" })
    }

    /** Measured 2026-09-19: the portal's plain "2026" movie section is just the latest uploads
     *  (small titles, scores 4-8); "2026 Peliculas teatrales" holds the year's cinema releases. */
    @Test
    fun `movie releases come from the year's theatrical section, not the plain year one`() {
        val result = MagisHomeClassifier.classify(
            mapOf(
                "peliculas" to listOf(
                    section("2026", many("upload", 3, "Comedy")),
                    section("2026 Peliculas teatrales", many("cinema", 2, "Action")),
                ),
            ),
        )

        val releases = result.row("magis_new_peliculas")
        assertEquals("Estrenos 2026 · Películas", releases.title)
        assertEquals(listOf("cinema1", "cinema2"), releases.shown.map { it.id })
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

        val releases = result.row("magis_new_peliculas")
        assertEquals("Estrenos 2026 · Películas", releases.title)
        assertEquals(listOf("new1", "new2"), releases.shown.map { it.id })
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
            listOf("magis_new_peliculas", "magis_top_peliculas", "magis_g_peliculas_action"),
            result.map { it.id },
        )
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
    fun `releases keep the root's year section even for titles another root claims`() {
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

        // "x" is in peliculas' year section: it belongs in Estrenos · Películas.
        assertTrue(result.row("magis_new_peliculas").shown.any { it.id == "x" })
        // "x" is an anime title (precedence): it's NOT in top-rated movies.
        assertFalse(result.row("magis_top_peliculas").all.any { it.id == "x" })
    }
}
