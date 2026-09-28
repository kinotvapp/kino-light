package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.catalog.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CommunitySectionTest {
    @Test fun `the header says what is happening and never shows an error`() {
        assertEquals(CommunityHeader("Buscando…", false, "Buscando plugins de la comunidad…"), communityHeader(CommunityUiState(loading = true, refreshing = true)))
        assertEquals(CommunityHeader("Actualizar", true, "Por ahora no hay plugins de la comunidad para mostrar."), communityHeader(CommunityUiState(loading = false)))
        val row = CatalogRow(CatalogEntry("x", "o/x", "X", ""), null, community = true)
        assertEquals(CommunityHeader("Buscando…", false, null), communityHeader(CommunityUiState(loading = false, refreshing = true, rows = listOf(row))))
        assertEquals(CommunityHeader("Actualizar", true, null), communityHeader(CommunityUiState(loading = false, rows = listOf(row))))
        assertEquals("De la comunidad", COMMUNITY_TITLE)
    }

    @Test fun `a search that found only what Recomendados already lists says so, a failure keeps the neutral line`() {
        assertEquals(
            CommunityHeader("Actualizar", true, "Todos los plugins de la comunidad que encontramos ya están en Recomendados."),
            communityHeader(CommunityUiState(loading = false, allRecommended = true)),
        )
        assertEquals("Por ahora no hay plugins de la comunidad para mostrar.", communityHeader(CommunityUiState(loading = false)).line)
        val row = CatalogRow(CatalogEntry("x", "o/x", "X", ""), null, community = true)
        assertEquals(null, communityHeader(CommunityUiState(loading = false, rows = listOf(row), allRecommended = true)).line)
    }

    @Test fun `a community card key never collides with a catalog card key`() {
        val sameId = CatalogRow(CatalogEntry("internet-archive", "Someone/Copy", "Copy", ""), null, community = true)
        assertEquals("community-someone/copy", communityCardKey(sameId))
        assertNotEquals("card-internet-archive", communityCardKey(sameId))
    }
}
