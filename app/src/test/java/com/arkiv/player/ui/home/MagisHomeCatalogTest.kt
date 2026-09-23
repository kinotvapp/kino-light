package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.CatalogSection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisHomeCatalogTest {

    private fun dramas(type: String) = listOf(
        CatalogSection(
            id = 1, name = "All", adult = false,
            items = (1..6).map {
                CatalogItem(id = "$type$it", title = "t$it", poster = null, durationS = 0, type = type, genres = listOf("Drama"))
            },
        ),
    )

    @Test
    fun `asks for the four roots and never the adults one`() = runTest {
        val asked = mutableListOf<String>()
        MagisHomeCatalog { root -> synchronized(asked) { asked += root }; emptyList() }.rows()

        assertEquals(setOf("peliculas", "series", "anime", "infantil"), asked.toSet())
        assertEquals(4, asked.size)
    }

    @Test
    fun `a root that fails doesn't take the others down`() = runTest {
        val rows = MagisHomeCatalog { root ->
            when (root) {
                "peliculas" -> error("portal down")
                "series" -> dramas("teleplay")
                else -> emptyList()
            }
        }.rows()

        assertTrue(rows.any { it.id == "magis_g_series_drama" })
        assertTrue(rows.none { it.id.contains("peliculas") })
    }

    @Test
    fun `reports the roots that failed or came back empty as missing`() = runTest {
        val home = MagisHomeCatalog { root ->
            when (root) {
                "peliculas" -> error("portal down")
                "anime" -> emptyList()
                "series" -> dramas("teleplay")
                else -> dramas("kids")
            }
        }.load()

        assertEquals(setOf(MagisKind.PELICULAS, MagisKind.ANIME), home.missing)
        assertTrue(home.rows.any { it.id == "magis_g_series_drama" })
    }

    @Test
    fun `a pass where every root answered misses nothing`() = runTest {
        val home = MagisHomeCatalog { dramas("teleplay") }.load()

        assertEquals(emptySet<MagisKind>(), home.missing)
    }
}
