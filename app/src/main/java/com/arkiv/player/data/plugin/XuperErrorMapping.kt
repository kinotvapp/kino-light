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
 * Nothing here ever produces [PluginErrors.AUTH_REQUIRED]: a session-dead rejection wants
 * reauthentication, not one of these 5 codes, and `MagisSession.withValidSession` already retries or
 * fails before a [MagisResult.PortalError] reaches this mapping (see `MagisResult.kt`'s
 * `isContentGone` KDoc). Nothing here produces [PluginErrors.RATE_LIMITED] either: Magis has no
 * portal code for it.
 */
private const val CONTENT_GONE_MARKER = "不存在"

internal fun MagisResult.PortalError.toPluginError(): Pair<String, String> {
    val message = msg.orEmpty()
    return when {
        msg?.contains(CONTENT_GONE_MARKER) == true -> PluginErrors.NOT_FOUND to message
        code == "portal100004" -> PluginErrors.NOT_FOUND to message
        code == "portal100024" -> PluginErrors.GEO_BLOCKED to message
        else -> PluginErrors.UNAVAILABLE to message
    }
}

/** No portal host answered at all (timeout, DNS, TLS, unparsable JSON) -- always [PluginErrors.UNAVAILABLE]. */
internal fun MagisResult.RedError.toPluginError(): Pair<String, String> =
    PluginErrors.UNAVAILABLE to (cause.message ?: "Xuper no disponible")
