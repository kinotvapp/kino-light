package com.arkiv.player.data.plugin.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSearchTest {
    private fun e(id: String, name: String, description: String = "", tags: List<String> = emptyList()) =
        CatalogEntry(id, "o/$id", name, description, tags)

    private val all = listOf(
        e("ia", "Internet Archive", "Películas de dominio público y televisión clásica", listOf("películas", "clásicos")),
        e("xuper", "Xuper", "Películas y series", listOf("series")),
        e("own", "Tu servidor", "Tus propios archivos", listOf("servidor")),
    )

    @Test fun `an empty or blank query shows everything in catalog order`() {
        assertEquals(listOf("ia", "xuper", "own"), filterCatalog(all, "").map { it.id })
        assertEquals(listOf("ia", "xuper", "own"), filterCatalog(all, "   ").map { it.id })
    }

    @Test fun `case and accents are ignored`() {
        assertEquals(listOf("ia", "xuper"), filterCatalog(all, "PELICULAS").map { it.id })
        assertEquals(listOf("ia"), filterCatalog(all, "television").map { it.id })
    }

    @Test fun `every word must match somewhere in the name, description or tags`() {
        assertEquals(listOf("ia"), filterCatalog(all, "peliculas clasicos").map { it.id })
        assertEquals(emptyList<String>(), filterCatalog(all, "peliculas servidor").map { it.id })
    }

    @Test fun `no match gives an empty list`() {
        assertEquals(emptyList<String>(), filterCatalog(all, "zzz").map { it.id })
    }
}
