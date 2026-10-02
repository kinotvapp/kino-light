package com.arkiv.player.data.plugin

import com.arkiv.player.data.magis.MagisResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MagisResult.kt`'s own KDoc (confirmed against the original app's decompiled strings) says what
 * each of the two known portal codes actually means, which is why the mapping here is NOT the one
 * the task brief sketched from a guess:
 * - `portal100024` = "can't be watched in this area" (a geographic/licensing block) -> `geo_blocked`,
 *   not `not_found`.
 * - `portal100004` = "this channel has been taken down" (the content itself is gone) -> `not_found`,
 *   not `auth_required`.
 *
 * `aaa100027`/`aaa100028` (`MagisSession.kt`'s private `SESSION_DEAD`, "session token expired / not
 * logged in") DO map to `auth_required` here (round 2): `MagisSession.withValidSession` retries and
 * reauthenticates on any `PortalError` first, but when that reauthentication itself exhausts every
 * retry, it returns the original, still-session-dead-coded error unchanged -- so this mapping can
 * receive it, and a genuinely dead session is an auth problem, not a "server is down" one.
 */
class XuperErrorMappingTest {
    @Test fun `portal100024 (geographic-licensing block) maps to geo_blocked`() {
        val e = MagisResult.PortalError(code = "portal100024", msg = "no disponible en tu zona")
        assertEquals(PluginErrors.GEO_BLOCKED to e.msg, e.toPluginError())
    }

    @Test fun `portal100004 (channel taken down) maps to not_found`() {
        val e = MagisResult.PortalError(code = "portal100004", msg = "canal dado de baja")
        assertEquals(PluginErrors.NOT_FOUND to e.msg, e.toPluginError())
    }

    @Test fun `aaa100027 (session token expired) maps to auth_required`() {
        val e = MagisResult.PortalError(code = "aaa100027", msg = "sesión expirada")
        assertEquals(PluginErrors.AUTH_REQUIRED to e.msg, e.toPluginError())
    }

    @Test fun `aaa100028 (not logged in) maps to auth_required`() {
        val e = MagisResult.PortalError(code = "aaa100028", msg = "未登录")
        assertEquals(PluginErrors.AUTH_REQUIRED to e.msg, e.toPluginError())
    }

    @Test fun `a message containing the content-gone marker maps to not_found regardless of code`() {
        val e = MagisResult.PortalError(code = "portal999999", msg = "视频不存在")
        assertEquals(PluginErrors.NOT_FOUND to e.msg, e.toPluginError())
    }

    @Test fun `an unrecognized portal code falls back to unavailable`() {
        val e = MagisResult.PortalError(code = "portal000000", msg = "raro")
        assertEquals(PluginErrors.UNAVAILABLE to e.msg, e.toPluginError())
    }

    @Test fun `a null msg falls back to an empty message instead of crashing`() {
        val e = MagisResult.PortalError(code = "portal000000", msg = null)
        assertEquals(PluginErrors.UNAVAILABLE to "", e.toPluginError())
    }

    // --- 0.9.45: ERRORES-AO3 / AKQ / ALU ---

    @Test fun `portal100006 (the series behind a chapter is gone) says so in Spanish`() {
        val e = MagisResult.PortalError(code = "portal100006", msg = "剧集不存在")
        assertEquals(PluginErrors.NOT_FOUND to XUPER_EPISODE_GONE, e.toPluginError())
        assertEquals(PluginErrors.NOT_FOUND to XUPER_SERIES_GONE, e.toPluginError(goneMessage = XUPER_SERIES_GONE))
    }

    @Test fun `a linked account's dead session asks to re-link it`() {
        for (code in listOf("aaa100027", "aaa100028")) {
            val (kind, message) = MagisResult.PortalError(code = code, msg = "未登录！").toPluginError(accountLinked = true)
            assertEquals(PluginErrors.AUTH_REQUIRED, kind)
            assertTrue(message, message.contains("Vuelve a vincularla en Ajustes, Cuenta"))
            assertTrue(message in XUPER_HOST_SENTENCES)
        }
    }

    @Test fun `a linked account logged in on another device says so`() {
        val (kind, message) = MagisResult.PortalError(code = "aaa100083", msg = "您的账号已经在其他设备登录").toPluginError(accountLinked = true)
        assertEquals(PluginErrors.AUTH_REQUIRED, kind)
        assertTrue(message, message.contains("otro dispositivo"))
        assertTrue(message in XUPER_HOST_SENTENCES)
    }

    @Test fun `without a linked account the session codes keep their old mapping`() {
        val e = MagisResult.PortalError(code = "aaa100028", msg = "未登录")
        assertEquals(PluginErrors.AUTH_REQUIRED to e.msg, e.toPluginError(accountLinked = false))
        val elsewhere = MagisResult.PortalError(code = "aaa100083", msg = "x")
        assertEquals(PluginErrors.UNAVAILABLE to "x", elsewhere.toPluginError(accountLinked = false))
    }

    @Test fun `a host sentence reaches the screen as it is, in the blocked dialog`() {
        for (sentence in XUPER_HOST_SENTENCES) {
            for (code in listOf(PluginErrors.NOT_FOUND, PluginErrors.AUTH_REQUIRED)) {
                val shown = PluginCalls.typed(PluginErrorException(code, sentence), "xuper", "Xuper", "resolve")
                assertTrue(shown is com.arkiv.player.data.gateway.GatewayBlockedException)
                assertEquals(sentence, shown.message)
            }
        }
        // Any other message keeps the code's generic line.
        val other = PluginCalls.typed(PluginErrorException(PluginErrors.NOT_FOUND, "剧集不存在"), "xuper", "Xuper", "resolve")
        assertEquals("No se encontró en Xuper", other.message)
    }

    @Test fun `a RedError (network-level failure) maps to unavailable`() {
        val e = MagisResult.RedError(cause = java.io.IOException("timeout"))
        assertEquals(PluginErrors.UNAVAILABLE to "timeout", e.toPluginError())
    }

    @Test fun `a RedError with no cause message still produces a non-null message`() {
        val e = MagisResult.RedError(cause = IllegalStateException())
        val (code, message) = e.toPluginError()
        assertEquals(PluginErrors.UNAVAILABLE, code)
        assertEquals(true, message.isNotEmpty())
    }
}
