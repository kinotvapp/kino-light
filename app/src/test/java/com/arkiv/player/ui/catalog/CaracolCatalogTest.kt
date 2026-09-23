package com.arkiv.player.ui.catalog

import com.arkiv.player.data.ditu.DituItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CaracolCatalogTest {

    private fun item(id: String, type: String, title: String = id) =
        DituItem(contentId = id, title = title, contentType = type)

    @Test fun splits_movies_and_series() {
        val series = item("1", "BUNDLE", "Serie A")
        val movie = item("2", "VOD", "Película A")

        val catalog = CaracolCatalog.split(listOf(series, movie))

        assertEquals(listOf(series), catalog.series)
        assertEquals(listOf(movie), catalog.movies)
    }

    @Test fun dedupes_by_ref_keeping_order() {
        val a = item("1", "VOD", "A")
        val b = item("2", "VOD", "B")
        val aAgain = item("1", "VOD", "A again")

        val catalog = CaracolCatalog.split(listOf(a, b, aAgain))

        assertEquals(listOf(a, b), catalog.movies)
    }

    @Test fun an_empty_catalog_gives_two_empty_lists() {
        val catalog = CaracolCatalog.split(emptyList())

        assertEquals(emptyList<DituItem>(), catalog.series)
        assertEquals(emptyList<DituItem>(), catalog.movies)
    }

    @Test fun group_of_bundles_counts_as_series_not_movie() {
        val group = item("1", "GROUP_OF_BUNDLES", "Grupo A")

        val catalog = CaracolCatalog.split(listOf(group))

        assertEquals(listOf(group), catalog.series)
        assertEquals(emptyList<DituItem>(), catalog.movies)
    }
}
