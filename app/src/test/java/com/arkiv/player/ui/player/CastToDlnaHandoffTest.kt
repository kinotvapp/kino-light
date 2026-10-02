package com.arkiv.player.ui.player

import com.arkiv.player.dlna.DlnaDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** "Probar por DLNA" reaches the player screen once, with the TV and where the Chromecast was; a stale one never. */
class CastToDlnaHandoffTest {
    private val tv = DlnaDevice("KALLEY Android TV", "http://192.168.1.40:49152/ctl")

    @After fun tearDown() {
        CastToDlnaHandoff.requests.value = null
    }

    @Test fun `the request is taken once, with the TV and the position`() {
        CastToDlnaHandoff.offer(tv, 754_000L)
        val r = CastToDlnaHandoff.requests.value
        val taken = CastToDlnaHandoff.take(r)!!
        assertSame(tv, taken.device)
        assertEquals(754_000L, taken.atMs)
        assertNull("never twice", CastToDlnaHandoff.take(r))
        assertNull(CastToDlnaHandoff.requests.value)
    }

    @Test fun `a request no screen took in time is dropped`() {
        CastToDlnaHandoff.offer(tv, 1_000L)
        val r = CastToDlnaHandoff.requests.value
        assertNull(CastToDlnaHandoff.take(r, now = r!!.createdAt + CastToDlnaHandoff.MAX_AGE_MS + 1))
        assertNull(CastToDlnaHandoff.requests.value)
    }
}
