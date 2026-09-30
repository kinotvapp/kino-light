package com.arkiv.player.data.live

import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveCategory
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.data.gateway.LiveSession
import com.arkiv.player.ui.live.LiveController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XuperLiveProviderTest {
    private val asked = mutableListOf<Any>()
    private val portal = object : LiveCatalogGateway {
        override suspend fun categories(includeAdults: Boolean) = listOf(LiveCategory(76182, "Todos"), LiveCategory(12, "Deportes"))
        override suspend fun channels(category: Int, force: Boolean): List<LiveChannel> { asked.add(category); return listOf(LiveChannel("c1", "RCN", 5, null)) }
        override suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>> { asked.add(codes); return emptyMap<String, List<LiveProgram>>() to codes }
    }
    private var gate: String? = null
    private val controller = LiveController(
        resolver = { code -> LiveSession("cdn", "https://cdn/?token=0123456789abcdef0123456789abcdef", "lic", code, 0L) },
        urlFor = { "http://127.0.0.1:1/live/${it.channel}.m3u8" },
        gate = { gate },
    )
    private val xuper = XuperLiveProvider(portal, controller)

    @Test fun `it is the portal's catalog with string category ids`() = runBlocking {
        assertEquals("xuper", xuper.id)
        assertEquals("76182", xuper.initialCategory())
        assertEquals(listOf(ProviderCategory("76182", "Todos"), ProviderCategory("12", "Deportes", "deportes")), xuper.categories(false))
        assertEquals(listOf("xuper"), xuper.channels("12").map { it.provider })
        assertEquals(emptyList<LiveChannel>(), xuper.channels("not-a-number"))
        assertEquals(listOf<Any>(12), asked)
    }

    @Test fun `the guide only asks the portal for xuper channels`() = runBlocking {
        xuper.guide(listOf(LiveChannel("c1", "RCN", 5, null), LiveChannel("c1", "Uno", 1, null, provider = "plugin:tv1")))
        assertEquals(listOf<Any>(listOf("c1")), asked)
    }

    @Test fun `open goes through the live controller and its gate`() = runBlocking {
        assertEquals(LiveOpening.Proxied("http://127.0.0.1:1/live/c1.m3u8"), xuper.open(LiveChannel("c1", "RCN", 5, null)))
        gate = "Activa el plugin Xuper para ver este canal"
        assertThrows(GatewayBlockedException::class.java) { runBlocking { xuper.open(LiveChannel("c2", "Caracol", 6, null)) } }
        Unit
    }
}
