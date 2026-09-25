package com.arkiv.player.ui.tv

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.ui.plugin.PluginMorePager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fix round 1, finding 2: on TV, once the grid had any items, `state.error` was never reached (the
 * screen's `when` checked `hasItems` first and an error branch after it, so it was unreachable once
 * there were items) -- a setup-required error mid-paging left "Configurar" unreachable, with the
 * only visible behavior a silent `loadMore()` retry loop on every focus into the last row.
 *
 * [tvMoreFooter] is the pure decision behind the grid's trailing item, extracted so it's testable
 * in a plain JVM (Compose for TV has no UI test infrastructure in this project, same reason as
 * `TvHomeScreenForYouTest`); the composable now renders it unconditionally in the grid's trailing
 * item -- whether or not there are already items on screen -- instead of gating it behind `hasItems`.
 */
class TvPluginMoreScreenTest {
    private val item = GatewayResult(source = "plugin:p", title = "X", ref = "r")

    @Test fun `an error must be visible even with items already on screen`() {
        val state = PluginMorePager.State(items = listOf(item), error = "Configura X en Ajustes ▸ Plugins", setupPluginId = "x")
        assertEquals(TvMoreFooter.ERROR, tvMoreFooter(state))
    }

    @Test fun `still paging with no error shows loading, with or without items`() {
        assertEquals(TvMoreFooter.LOADING, tvMoreFooter(PluginMorePager.State(items = listOf(item))))
        assertEquals(TvMoreFooter.LOADING, tvMoreFooter(PluginMorePager.State()))
    }

    @Test fun `ended with no error shows nothing more`() {
        assertEquals(TvMoreFooter.NONE, tvMoreFooter(PluginMorePager.State(items = listOf(item), ended = true)))
    }
}
