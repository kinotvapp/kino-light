package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.catalog.CatalogOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRefreshLineTest {
    private fun state(origin: CatalogOrigin?, refreshing: Boolean) = CatalogUiState(loading = false, origin = origin, refreshing = refreshing)

    @Test fun `while a refresh runs the seed shows no notice and an inert Actualizando action`() {
        val line = catalogRefreshLine(state(CatalogOrigin.SEED, refreshing = true))!!
        assertNull(line.notice)
        assertEquals("Actualizando…", line.actionLabel)
        assertFalse(line.actionEnabled)
    }

    @Test fun `once the refresh is over and the list is still the seed the notice says it could not be updated`() {
        val line = catalogRefreshLine(state(CatalogOrigin.SEED, refreshing = false))!!
        assertEquals("No se pudo actualizar la lista de recomendados; mostrando la que viene con la app.", line.notice)
        assertEquals("Reintentar", line.actionLabel)
        assertTrue(line.actionEnabled)
    }

    @Test fun `the notice never claims there is no connection`() {
        val notice = catalogRefreshLine(state(CatalogOrigin.SEED, refreshing = false))!!.notice!!
        assertFalse(notice.contains("conexión", ignoreCase = true))
    }

    @Test fun `a list that came from the network or the cache has no line at all`() {
        assertNull(catalogRefreshLine(state(CatalogOrigin.FRESH, refreshing = false)))
        assertNull(catalogRefreshLine(state(CatalogOrigin.CACHE, refreshing = false)))
        assertNull(catalogRefreshLine(state(CatalogOrigin.CACHE, refreshing = true)))
        assertNull(catalogRefreshLine(state(null, refreshing = false)))
    }
}
