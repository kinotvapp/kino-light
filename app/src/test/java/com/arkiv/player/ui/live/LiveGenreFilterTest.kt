package com.arkiv.player.ui.live

import com.arkiv.player.data.live.ProviderCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveGenreFilterTest {
    private val cats = listOf(
        ProviderCategory("a", "Deportes HD", "deportes"),
        ProviderCategory("b", "Noticias", "noticias"),
        ProviderCategory("c", "Fútbol", "deportes"),
        ProviderCategory("d", "Variedad"),
    )

    @Test fun `the genres on offer follow the vocabulary order, with no duplicates`() {
        assertEquals(listOf("deportes", "noticias"), LiveUiState(categories = cats).genres)
    }

    @Test fun `with fewer than two genres there is nothing to filter`() {
        val one = listOf(ProviderCategory("a", "x", "deportes"), ProviderCategory("b", "y"))
        assertEquals(emptyList<String>(), LiveUiState(categories = one).genres)
        assertEquals(one, LiveUiState(categories = one).visibleCategories)
    }

    @Test fun `no genre chosen shows every category`() {
        assertEquals(cats, LiveUiState(categories = cats).visibleCategories)
    }

    @Test fun `a chosen genre keeps only its categories`() {
        assertEquals(listOf("a", "c"), LiveUiState(categories = cats, genre = "deportes").visibleCategories.map { it.id })
    }

    @Test fun `the category being watched stays visible whatever the genre`() {
        val s = LiveUiState(categories = cats, genre = "deportes", activeCategory = "b")
        assertEquals(listOf("a", "b", "c"), s.visibleCategories.map { it.id })
    }
}
