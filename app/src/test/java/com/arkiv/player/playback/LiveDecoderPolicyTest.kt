package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDecoderPolicyTest {

    private data class Dec(val name: String, val hardware: Boolean)

    private val exynosHw = Dec("c2.exynos.h264.decoder", true)
    private val googleSw = Dec("c2.android.avc.decoder", false)
    private val qualcommHw = Dec("c2.qti.avc.decoder", true)

    private fun order(mime: String, vararg decoders: Dec) =
        LiveDecoderPolicy.order(mime, decoders.toList(), { it.name }, { it.hardware })

    @Test
    fun `an Exynos hardware decoder in front of an H264 channel gives way to the software one`() {
        assertEquals(listOf(googleSw, exynosHw), order("video/avc", exynosHw, googleSw))
    }

    @Test
    fun `the old OMX Exynos names count too`() {
        val omx = Dec("OMX.Exynos.avc.dec", true)
        assertEquals(listOf(googleSw, omx), order("video/avc", omx, googleSw))
    }

    @Test
    fun `any other device keeps its hardware decoder first`() {
        assertEquals(listOf(qualcommHw, googleSw), order("video/avc", qualcommHw, googleSw))
    }

    @Test
    fun `only H264 is moved, other codecs on an Exynos chip stay as offered`() {
        val hevc = Dec("c2.exynos.hevc.decoder", true)
        val hevcSw = Dec("c2.android.hevc.decoder", false)
        assertEquals(listOf(hevc, hevcSw), order("video/hevc", hevc, hevcSw))
    }

    @Test
    fun `with no software decoder available the order does not change`() {
        assertEquals(listOf(exynosHw), order("video/avc", exynosHw))
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<Dec>(), order("video/avc"))
    }

    @Test
    fun `a channel already known to fail in hardware gets software first on any chip`() {
        val forced = LiveDecoderPolicy.order("video/avc", listOf(qualcommHw, googleSw), { it.name }, { it.hardware }, forceSoftware = true)
        assertEquals(listOf(googleSw, qualcommHw), forced)
    }

    @Test
    fun `forcing software leaves other codecs alone`() {
        val hevc = Dec("c2.qti.hevc.decoder", true)
        val hevcSw = Dec("c2.android.hevc.decoder", false)
        val forced = LiveDecoderPolicy.order("video/hevc", listOf(hevc, hevcSw), { it.name }, { it.hardware }, forceSoftware = true)
        assertEquals(listOf(hevc, hevcSw), forced)
    }

    @Test
    fun `Exynos names are recognised regardless of case`() {
        assertTrue(LiveDecoderPolicy.isExynos("C2.EXYNOS.h264.decoder"))
        assertFalse(LiveDecoderPolicy.isExynos("c2.android.avc.decoder"))
    }
}
