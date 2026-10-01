package com.arkiv.player.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One problem = one GlitchTip issue: the changing values ride in extras, never in the message. */
class StableReportsTest {

    @Test
    fun `a live channel failure is one issue per reason, not per channel`() {
        val a = StableReports.liveResolveFailed("cyx-123", "portal100024")
        val b = StableReports.liveResolveFailed("hbo-9", "portal100024")
        assertEquals(a.message, b.message)
        assertTrue(a.message.contains("portal100024"))
        assertFalse(a.message.contains("cyx-123"))
        assertEquals("cyx-123", a.extras["channel"])
    }

    @Test
    fun `no sources is one issue whatever the title`() {
        val a = StableReports.noSources("Dune", 438631, "movie", 0, 0, false, listOf("dune"), emptyList(), listOf("Dune"))
        val b = StableReports.noSources("Shogun", 126308, "tv", 1, 3, true, listOf("shogun"), listOf("x"), listOf("Shōgun"))
        assertEquals("0 sources", a.message)
        assertEquals(a.message, b.message)
        assertEquals("Dune", a.extras["query"])
        assertEquals("S1E3", b.extras["season_episode"])
    }

    @Test
    fun `a portal error keeps its code in the message and its text in extras`() {
        val a = StableReports.portalErrorReached("aaa100027", "未登录", accountLinked = false, seedsExhausted = true)
        val b = StableReports.portalErrorReached("aaa100027", "您的账号已经在其他设备登录", accountLinked = true, seedsExhausted = false)
        assertEquals(a.message, b.message)
        assertTrue(a.message.endsWith("code=aaa100027"))
        assertEquals("未登录", a.extras["msg"])
        assertEquals("true", b.extras["account_linked"])
    }

    @Test
    fun `an offline download failure is one issue per reason, not per episode`() {
        val a = StableReports.offlineDownloadFailed("magis:1::e1", "no se pudo hablar con Xuper")
        val b = StableReports.offlineDownloadFailed("magis:2::e7", "no se pudo hablar con Xuper")
        assertEquals("no se pudo hablar con Xuper", a.message)
        assertEquals(a.message, b.message)
        assertEquals("magis:1::e1", a.extras["episode"])
        // A reason that quotes the episode loses it from the message.
        assertEquals("[episode] is gone", StableReports.offlineDownloadFailed("magis:1::e1", "magis:1::e1 is gone").message)
    }

    @Test
    fun `no video frame is one issue per player, not per codec or size`() {
        val a = StableReports.noVideoFrame("local", 8_000, "video/hevc", 3840, 2160, 2, 3)
        val b = StableReports.noVideoFrame("local", 12_345, "video/avc", 1280, 720, 1, 2, mapOf("software" to "true"))
        assertEquals(a.message, b.message)
        assertEquals("3840x2160", a.extras["video_size"])
        assertEquals("video/avc", b.extras["codec"])
        assertEquals("true", b.extras["software"])
        assertEquals("", StableReports.noVideoFrame("in-screen live", 1, null, null, null, 0, 1).extras["video_size"])
    }

    @Test
    fun `a converted Nuvio plugin id stays readable, other long tokens do not`() {
        assertEquals(
            "plugin nuvio-xupalace-c1a132 resolve no_streams",
            PrivateText.scrubAddresses("plugin nuvio-xupalace-c1a132 resolve no_streams", keepCodeNames = true),
        )
        assertEquals("session [id]", PrivateText.scrubAddresses("session a1b2c3d4e5f6a7b8c9d0"))
        // Only the Nuvio id shape is spared: a long token merely starting with "nuvio" is not.
        assertEquals("[id]", PrivateText.scrubAddresses("nuvio1234567890abcdef"))
    }
}
