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
 * This mapping has no explicit branch for the session-dead codes (`aaa100027`/`aaa100028`, see
 * `MagisSession.kt`'s `SESSION_DEAD`). `MagisSession.withValidSession` reauthenticates and retries on
 * any `PortalError` before returning one, but if that reauthentication itself fails -- a linked
 * account's relogin rejected, or every seed-rescue round exhausted for an anonymous session -- it
 * falls through to `return result` with the ORIGINAL, still-`SESSION_DEAD`-coded `PortalError` intact
 * (not swallowed or replaced). So one of those codes CAN reach here; when it does, it has no explicit
 * branch below and falls into the generic [PluginErrors.UNAVAILABLE] case rather than
 * [PluginErrors.AUTH_REQUIRED] -- a safe fallback, not a deliberate claim that this can never happen.
 * Nothing here produces [PluginErrors.RATE_LIMITED] either: Magis has no portal code for it.
 */
private const val CONTENT_GONE_MARKER = "不存在"

internal fun MagisResult.PortalError.toPluginError(): Pair<String, String> {
    val message = msg.orEmpty()
    return when {
        code == "portal100004" -> PluginErrors.NOT_FOUND to message
        code == "portal100024" -> PluginErrors.GEO_BLOCKED to message
        msg?.contains(CONTENT_GONE_MARKER) == true -> PluginErrors.NOT_FOUND to message
        else -> PluginErrors.UNAVAILABLE to message
    }
}

/** No portal host answered at all (timeout, DNS, TLS, unparsable JSON) -- always [PluginErrors.UNAVAILABLE]. */
internal fun MagisResult.RedError.toPluginError(): Pair<String, String> =
    PluginErrors.UNAVAILABLE to (cause.message ?: "Xuper no disponible")
