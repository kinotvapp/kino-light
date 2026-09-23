package com.arkiv.player.ui.live

import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveRecentEntity
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
                code = "a", categoria = 3, nombre = "Canal A", numero = 5,
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
                code = "a", categoria = 1, nombre = "Nombre viejo de la caché", numero = 9,
                logo = null, guardadoAt = 0,
            ),
        )
        val channel = recentChannelsForHome(recent, cache).single()
        assertEquals("Nombre actual", channel.name)
    }
}
