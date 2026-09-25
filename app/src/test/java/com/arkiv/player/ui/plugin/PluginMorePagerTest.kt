package com.arkiv.player.ui.plugin

import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPage
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.plugin.PluginSetupRequiredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginMorePagerTest {
    private fun r(id: String) = GatewayResult("plugin:p", id, "ref-$id", extra = mapOf("pluginItemId" to id))
    private fun page(vararg ids: String, next: String? = null) = GatewayPage(ids.map(::r), next)

    @Test fun `pages follow the cursor to the end`() = runTest {
        val asked = mutableListOf<String?>()
        val pages = mapOf(null to page("a", "b", next = "2"), "2" to page("c", next = "3"), "3" to page("d"))
        val pager = PluginMorePager({ c -> asked += c; pages.getValue(c) })
        repeat(5) { pager.loadMore() }
        assertEquals(listOf(null, "2", "3"), asked)
        assertEquals(listOf("a", "b", "c", "d"), pager.state.value.items.map { it.title })
        assertTrue(pager.state.value.ended)
    }

    @Test fun `a repeated cursor, a page with nothing new, or the cap end it`() = runTest {
        val loop = PluginMorePager({ c -> if (c == null) page("a", next = "x") else page("b", next = "x") })
        repeat(4) { loop.loadMore() }
        assertEquals(listOf("a", "b"), loop.state.value.items.map { it.title })
        assertTrue(loop.state.value.ended)
        val same = PluginMorePager({ c -> page("a", next = (c ?: "0") + "1") })
        repeat(4) { same.loadMore() }
        assertEquals(listOf("a"), same.state.value.items.map { it.title })
        assertTrue(same.state.value.ended)
        var n = 0
        val endless = PluginMorePager({ _ -> n++; page("i$n", "j$n", next = "c$n") }, maxItems = 5)
        repeat(10) { endless.loadMore() }
        assertEquals(5, endless.state.value.items.size)
        assertTrue(endless.state.value.ended)
    }

    /**
     * Review Focus 4: the real, un-injected [PluginMorePager.MAX_ITEMS] — the plan's own prose
     * ("stops at... 500 items") is stale; the shipped constant is 1000. This test pins THAT
     * constant, not just an injected small `maxItems`, so a future edit to the real cap is caught
     * here too, not only by the small-value test above.
     */
    @Test fun `an endless plugin is capped at the real MAX_ITEMS constant`() = runTest {
        assertEquals(1000, PluginMorePager.MAX_ITEMS)
        var n = 0
        val endless = PluginMorePager({ _ -> n++; page("i$n", "j$n", "k$n", "l$n", next = "c$n") })
        // 4 fresh items per page; enough calls to comfortably exceed MAX_ITEMS if uncapped.
        repeat(PluginMorePager.MAX_ITEMS) { endless.loadMore() }
        assertEquals(PluginMorePager.MAX_ITEMS, endless.state.value.items.size)
        assertTrue(endless.state.value.ended)
    }

    @Test fun `only one load at a time`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val pager = PluginMorePager({ _ -> calls++; gate.await(); page("a", next = "2") })
        val first = async { pager.loadMore() }
        testScheduler.runCurrent()
        pager.loadMore()
        gate.complete(Unit)
        first.await()
        assertEquals(1, calls)
    }

    @Test fun `errors are worded, setup errors carry the plugin, and retry asks the same page`() = runTest {
        var fail: Exception? = PluginSetupRequiredException("jf", "Configura Jellyfin en Ajustes ▸ Plugins")
        val asked = mutableListOf<String?>()
        val pager = PluginMorePager({ c -> asked += c; fail?.let { throw it }; page("a") })
        pager.loadMore()
        assertEquals("Configura Jellyfin en Ajustes ▸ Plugins", pager.state.value.error)
        assertEquals("jf", pager.state.value.setupPluginId)
        assertFalse(pager.state.value.loading)
        fail = GatewayException("Jellyfin no está disponible ahora")
        pager.retry()
        assertEquals("Jellyfin no está disponible ahora", pager.state.value.error)
        assertNull(pager.state.value.setupPluginId)
        fail = null
        pager.retry()
        assertEquals(listOf(null, null, null), asked)
        assertEquals(listOf("a"), pager.state.value.items.map { it.title })
        assertNull(pager.state.value.error)
    }
}
