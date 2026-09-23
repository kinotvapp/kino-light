package com.arkiv.player.ui.catalog

import com.arkiv.player.data.gateway.GatewayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaySourceDituTest {

    @Test fun `a Caracol source has its own color`() {
        val ditu = PlaySource.Ditu(GatewayResult(source = "ditu", title = "Rigo", ref = "ditu1:BUNDLE:1"))
        val magis = PlaySource.Magis(GatewayResult(source = "magis", title = "Rigo", ref = "magis1:movie:0:C1"))

        assertEquals(ArkivCaracolVerde, accentOf(ditu))
        assertNotEquals(accentOf(magis), accentOf(ditu))
    }
}
