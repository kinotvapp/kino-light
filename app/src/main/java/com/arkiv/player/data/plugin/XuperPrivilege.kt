package com.arkiv.player.data.plugin

/**
 * The one public repo whose installed plugin runtime gets the extra `kino.xuper.*` host functions.
 * Checked against [InstalledPlugin.record]'s `address` — set once by [PluginInstaller] from a
 * validated fetch, never editable by the person and never the manifest's self-declared `id` (which
 * any other repo could copy verbatim).
 */
object XuperPrivilege {
    const val SOURCE_REPO = "kinotvapp/kino-plugin-xuper"
}
