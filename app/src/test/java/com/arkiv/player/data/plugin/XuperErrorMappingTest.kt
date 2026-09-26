package com.arkiv.player.data.plugin

import com.arkiv.player.data.magis.MagisResult
import org.junit.Assert.assertEquals
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
