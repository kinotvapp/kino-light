package com.arkiv.player.ui.catalog

import com.arkiv.player.data.ditu.DituChannel
import com.arkiv.player.data.gateway.GatewayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** That the "En vivo" tab tells "failed" apart from "no channels". See [ChannelsState]. */
class ChannelsStateTest {

    private val channel = DituChannel(channelId = 1, name = "Caracol TV", logoUrl = "", assetId = 11)

    /** With the TV offline the tab used to say "Unable to resolve host…": now it says so in plain human words. */
    @Test fun `a failure gives the error state in plain human words`() {
        val noDns = GatewayException(
            "Caracol no responde: Unable to resolve host \"middleware.ditu.caracoltv.com\"",
            java.net.UnknownHostException("Unable to resolve host \"middleware.ditu.caracoltv.com\""),
        )
        val state = ChannelsState.from(Result.failure(noDns))

        assertEquals(ChannelsState.Failed("Caracol no respondió: sin conexión a internet"), state)
    }

    /** What isn't recognized is the generic channels message, never the raw one. */
    @Test fun `an unrecognized failure doesn't show the raw message`() {
        val state = ChannelsState.from(Result.failure(GatewayException("JSONObject[\"resultObj\"] not found")))

        assertEquals(ChannelsState.Failed("No se pudieron cargar los canales de Caracol"), state)
    }

    /** An error with no message is still an error: it can't end up as "no channels". */
    @Test fun `an error with no message is still an error`() {
        val state = ChannelsState.from(Result.failure(RuntimeException()))

        assertTrue(state is ChannelsState.Failed && state.message.isNotBlank())
    }

    @Test fun `an empty list gives the empty state`() {
        assertSame(ChannelsState.Empty, ChannelsState.from(Result.success(emptyList())))
    }

    @Test fun `with channels it gives the list`() {
        assertEquals(ChannelsState.Ready(listOf(channel)), ChannelsState.from(Result.success(listOf(channel))))
    }
}
