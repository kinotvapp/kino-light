package com.arkiv.player.ui.catalog

import com.arkiv.player.data.gateway.GatewayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaySourceDituTest {

    @Test fun `a Caracol source has its own color`() {
        val ditu = PlaySource.Ditu(GatewayResult(source = "ditu", title = "Rigo", ref = "ditu1:BUNDLE:1"))
        val plugin = PlaySource.Plugin(
            "demo", "Demo", 0xFFE0A030, GatewayResult(source = "plugin:demo", title = "Rigo", ref = "plg1:demo:1"),
        )

        assertEquals(ArkivCaracolVerde, accentOf(ditu))
        assertNotEquals(accentOf(plugin), accentOf(ditu))
    }
}
