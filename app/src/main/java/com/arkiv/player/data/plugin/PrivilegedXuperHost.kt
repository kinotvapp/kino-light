package com.arkiv.player.data.plugin

/**
 * The 5 native functions the Xuper plugin's `plugin.js` calls instead of `kino.fetch` -- each does
 * the real, protected Magis work and returns an already-resolved, already-error-collapsed JSON
 * envelope. Only ever constructed for the one runtime whose installed source is an official Xuper repo
 * ([XuperPrivilege.grants]; see `AppGraph.openPluginRuntime`) -- no other plugin can implement or
 * reach this interface's methods.
 *
 * Envelope shape, as a string because it crosses the same JNI/QuickJS boundary every other kino.*
 * binding does: `{"ok":true,"data":<payload>}` or
 * `{"ok":false,"code":"auth_required"|"geo_blocked"|"not_found"|"rate_limited"|"unavailable","message":"<text>"}`.
 * `plugin.js` checks `.ok` and calls `kino.error(code, message)` itself on failure -- these methods
 * never throw.
 */
interface PrivilegedXuperHost : PluginHost {
    suspend fun xuperSearch(argsJson: String): String
    suspend fun xuperHome(): String
    suspend fun xuperBrowse(ref: String, cursor: String?): String
    suspend fun xuperEpisodes(ref: String): String
    suspend fun xuperResolve(ref: String): String
}
