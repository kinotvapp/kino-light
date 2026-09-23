package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [liveErrorMessage] is what decides whether, when a channel fails to open, the screen shows
 * something the person can fix or a "couldn't" that says nothing.
 */
class LiveErrorMessageTest {

    @Test
    fun `with no Magis account the message says to link one`() {
        val msg = liveErrorMessage(hasMagisAccount = false, channelName = "Canal 5")

        assertTrue("must name the Xuper account: $msg", msg.contains("cuenta de Xuper"))
        assertTrue("must say where to link it: $msg", msg.contains("Ajustes"))
        // Doesn't name the channel: the problem isn't THAT channel, it's that live is down entirely.
        assertTrue(!msg.contains("Canal 5"))
    }

    @Test
    fun `with an account linked the failure is the channel's and it is named`() {
        assertEquals(
            "No se pudo abrir Canal 5",
            liveErrorMessage(hasMagisAccount = true, channelName = "Canal 5"),
        )
    }
}
