package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.LiveSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The hard stop behind every Xuper live surface: while the Xuper plugin is off, opening a channel
 * fails with [GatewayBlockedException] (the player's "No se puede reproducir" dialog) BEFORE any
 * portal call -- and before a session cached while it was on can be handed out.
 */
class LiveControllerGateTest {

    private fun session(code: String) = LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0)

    @Test fun `a closed gate refuses the open before the resolver is ever called`() = runBlocking {
        var resolutions = 0
        val ctrl = LiveController(
            resolver = { code -> resolutions++; session(code) },
            urlFor = { "http://127.0.0.1:9999/live.m3u8" },
            gate = { "Activa el plugin Xuper para ver este canal" },
        )
        try {
            ctrl.open("c1")
            fail("a closed gate must refuse the channel")
        } catch (e: GatewayBlockedException) {
            assertEquals("Activa el plugin Xuper para ver este canal", e.message)
        }
        assertEquals("no portal call while the gate is closed", 0, resolutions)
    }

    @Test fun `a session cached while the gate was open is not handed out once it closes`() = runBlocking {
        var blocked: String? = null
        var resolutions = 0
        val ctrl = LiveController(
            resolver = { code -> resolutions++; session(code) },
            urlFor = { "http://127.0.0.1:9999/live.m3u8" },
            gate = { blocked },
        )
        ctrl.open("c2")
        blocked = "Instala el plugin Xuper para ver este canal"
        val refused = runCatching { ctrl.open("c2") }.exceptionOrNull()
        assertTrue("got $refused", refused is GatewayBlockedException)
        assertEquals(1, resolutions)
    }

    @Test fun `preheat stays silent and resolves nothing while the gate is closed`() = runBlocking {
        var resolutions = 0
        val ctrl = LiveController(
            resolver = { code -> resolutions++; session(code) },
            urlFor = { "http://127.0.0.1:9999/live.m3u8" },
            gate = { "Activa el plugin Xuper para ver este canal" },
        )
        ctrl.preheat("c3") // must not throw
        assertEquals(0, resolutions)
    }

    @Test fun `an open gate changes nothing`() = runBlocking {
        var resolutions = 0
        val ctrl = LiveController(
            resolver = { code -> resolutions++; session(code) },
            urlFor = { "http://127.0.0.1:9999/live.m3u8" },
            gate = { null },
        )
        assertTrue(ctrl.open("c4").startsWith("http://127.0.0.1:"))
        assertEquals(1, resolutions)
    }
}
