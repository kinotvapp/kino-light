package com.arkiv.player.playback

import com.arkiv.player.data.magis.TweakedMd5
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only one implementation is left: the signature is calculated on the device. The gateway backup
 * (`FirmaDelGateway`/`FirmaConRespaldo`/`FirmaSegunAjustes`) left with the server, so what's left to
 * pin down is that the local signature is correct and what counts as a CDN rejection.
 */
class SegmentSignatureTest {

    @Test
    fun `the local signature matches the verified algorithm`() = runBlocking {
        val token = "941d98961990d67e249dcd1ac57378c8"

        val f = LocalSignature().sign(token)

        assertEquals(TweakedMd5.signO3(token, f.moment), f.sign2)
    }

    @Test
    fun `each signature uses its own moment`() = runBlocking {
        val local = LocalSignature()
        val token = "941d98961990d67e249dcd1ac57378c8"

        val first = local.sign(token)
        val second = local.sign(token)

        // Same moment => same signature; different moment => different. What can't happen is the
        // signature being calculated with a moment other than the one it reports.
        assertEquals(TweakedMd5.signO3(token, second.moment), second.sign2)
        assertTrue(second.moment >= first.moment)
    }

    /** This CDN rejects with 401, not 403, and the code only checked for 403: no channel loaded. */
    @Test
    fun `401 is also a rejected signature, not just 403`() {
        assertTrue(isSignatureRejection(401))
        assertTrue(isSignatureRejection(403))
    }

    @Test
    fun `the other codes are not a signature rejection`() {
        // A 5xx or a timeout is the CDN having a problem, not the signature: counting them would
        // discard good signatures over any network hiccup.
        listOf(200, 206, 302, 404, 500, 502, 504).forEach {
            assertFalse("code $it", isSignatureRejection(it))
        }
    }
}
