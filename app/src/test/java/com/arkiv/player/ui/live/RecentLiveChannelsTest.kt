package com.arkiv.player.ui.live

import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.data.live.LiveProviderTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentLiveChannelsTest {

    @Test
    fun `with no recents there are no channels -- the home row shouldn't draw`() {
        assertTrue(recentChannelsForHome(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `doesn't reorder -- respects the order it arrives in (already vistoAt DESC)`() {
        val recent = listOf(
            LiveRecentEntity(code = "b", nombre = "Canal B", vistoAt = 200),
            LiveRecentEntity(code = "a", nombre = "Canal A", vistoAt = 100),
        )
        val result = recentChannelsForHome(recent, emptyMap())
        assertEquals(listOf("b", "a"), result.map { it.code })
    }

    @Test
    fun `fills in logo and number when the channel is in the cache`() {
        val recent = listOf(LiveRecentEntity(code = "a", nombre = "Canal A", vistoAt = 100))
        val cache = mapOf(
            "a" to LiveChannelCacheEntity(
                code = "a", categoria = "3", nombre = "Canal A", numero = 5,
                logo = "https://logo/a.png", guardadoAt = 0,
            ),
        )
        val channel = recentChannelsForHome(recent, cache).single()
        assertEquals(5, channel.number)
        assertEquals("https://logo/a.png", channel.logo)
    }

    @Test
    fun `a channel missing from the cache falls back to number 0 and logo null, doesn't break`() {
        val recent = listOf(LiveRecentEntity(code = "a", nombre = "Canal A", vistoAt = 100))
        val channel = recentChannelsForHome(recent, emptyMap()).single()
        assertEquals(0, channel.number)
        assertNull(channel.logo)
    }

    @Test
    fun `the name comes from recent, not from the cache`() {
        val recent = listOf(LiveRecentEntity(code = "a", nombre = "Nombre actual", vistoAt = 100))
        val cache = mapOf(
            "a" to LiveChannelCacheEntity(
                code = "a", categoria = "1", nombre = "Nombre viejo de la caché", numero = 9,
                logo = null, guardadoAt = 0,
            ),
        )
        val channel = recentChannelsForHome(recent, cache).single()
        assertEquals("Nombre actual", channel.name)
    }

    @Test fun `recents keep their provider and are enriched only from their own provider's cache`() {
        val recent = listOf(LiveRecentEntity("c1", "Uno", 2L, provider = "plugin:tv"), LiveRecentEntity("c1", "RCN", 1L))
        val cache = mapOf("plugin:tv:c1" to LiveChannelCacheEntity("c1", "news", "Uno", 7, "https://l/1.png", 0L, "plugin:tv"))
        val out = recentChannelsForHome(recent, cache)
        assertEquals(listOf("plugin:tv:c1", "c1"), out.map { it.liveCode })
        assertEquals(listOf(7, 0), out.map { it.number })
    }

    @Test fun `the screens' recents hide providers that are gone and take loaded details`() {
        val raw = listOf(LiveRecentEntity("c1", "Uno", 2L, provider = "plugin:tv"), LiveRecentEntity("c2", "Caracol", 1L))
        val loaded = listOf(LiveChannel("c2", "Caracol HD", 6, "https://l/2.png"))
        val out = recentsForScreen(raw, loaded, available = setOf("xuper"))
        assertEquals(listOf(LiveChannel("c2", "Caracol", 6, "https://l/2.png")), out)
    }

    @Test fun `a provider badge shows only when there is more than one provider`() {
        val tabs = listOf(LiveProviderTab("xuper", "Xuper", 1L), LiveProviderTab("plugin:tv", "Tu servidor", 2L))
        assertEquals("Tu servidor", providerBadge(LiveChannel("c1", "Uno", 1, null, provider = "plugin:tv"), tabs)?.name)
        assertEquals(null, providerBadge(LiveChannel("c1", "Uno", 1, null), tabs.take(1)))
    }
}
