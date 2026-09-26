package com.arkiv.player.data.plugin

import com.arkiv.player.data.magis.MagisResult

/**
 * Collapses Magis's portal-error surface down to the 5 standard plugin codes ([PluginErrors]) --
 * built once so the 5 privileged Xuper functions (`xuperSearch`/`xuperHome`/`xuperBrowse`/
 * `xuperEpisodes`/`xuperResolve`, see [PrivilegedXuperHost]) all build their
 * `{"ok":false,"code":...,"message":...}` envelope through this instead of re-deriving it.
 *
 * Only two portal codes carry a distinct, confirmed meaning -- see `MagisResult.kt`'s
 * `PORTAL_ERROR_MESSAGES` KDoc, sourced from decompiling the original app's own error-message code:
 * - `portal100024` -- "this program can't be watched in this area" (a geographic/licensing block)
 *   -> [PluginErrors.GEO_BLOCKED].
 * - `portal100004` -- "this channel has been taken down" (the content itself is gone, not a login
 *   problem) -> [PluginErrors.NOT_FOUND].
 *
 * A message ending in the portal's own "does not exist" marker (`不存在`, see `isContentGone` in
 * `MagisResult.kt`) means the same thing as `portal100004` -- a stale/pulled title -- regardless of
 * which code carries it, so it also maps to [PluginErrors.NOT_FOUND].
 *
 * Every other portal code -- the ~90-code reseller-oriented surface `MagisResult.kt` describes, which
 * collapses into generic phrases in the original app and which Kino has no resellers to route to --
 * falls back to [PluginErrors.UNAVAILABLE].
 *
 * `MagisSession.withValidSession` reauthenticates and retries on any `PortalError` before returning
 * one, but if that reauthentication itself fails -- a linked account's relogin rejected, or every
 * seed-rescue round exhausted for an anonymous session -- it falls through to `return result` with the
 * ORIGINAL, still-session-dead-coded `PortalError` intact (not swallowed or replaced). So
 * `aaa100027`/`aaa100028` (`MagisSession.kt`'s private `SESSION_DEAD` set, "session token expired /
 * portal no longer recognizes it" -- inaccessible from this file, hence the literal string checks
 * below rather than a shared constant, same reasoning as `portal100024`/`portal100004` above, whose
 * `GEO_BLOCKED` constant is equally private to `MagisSession`) CAN reach this mapping, and map to
 * [PluginErrors.AUTH_REQUIRED]: a session that's genuinely dead after every retry is an auth problem,
 * not a "the server is down, wait" one -- [PluginErrors.UNAVAILABLE]'s message ("Xuper no está
 * disponible ahora") would send the wrong diagnosis. Nothing here produces [PluginErrors.RATE_LIMITED]:
 * Magis has no portal code for it.
 */
private const val CONTENT_GONE_MARKER = "不存在"

internal fun MagisResult.PortalError.toPluginError(): Pair<String, String> {
    val message = msg.orEmpty()
    return when {
        code == "portal100004" -> PluginErrors.NOT_FOUND to message
        code == "portal100024" -> PluginErrors.GEO_BLOCKED to message
        code == "aaa100027" || code == "aaa100028" -> PluginErrors.AUTH_REQUIRED to message
        msg?.contains(CONTENT_GONE_MARKER) == true -> PluginErrors.NOT_FOUND to message
        else -> PluginErrors.UNAVAILABLE to message
    }
}

/** No portal host answered at all (timeout, DNS, TLS, unparsable JSON) -- always [PluginErrors.UNAVAILABLE]. */
internal fun MagisResult.RedError.toPluginError(): Pair<String, String> =
    PluginErrors.UNAVAILABLE to (cause.message ?: "Xuper no disponible")
