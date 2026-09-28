package com.arkiv.player.data.plugin

/**
 * The one public repo whose installed plugin runtime gets the extra `kino.xuper.*` host functions.
 * Checked against [InstalledPlugin.record]'s `address` — set once by [PluginInstaller] from a
 * validated fetch, never editable by the person and never the manifest's self-declared `id` (which
 * any other repo could copy verbatim).
 */
object XuperPrivilege {
    const val SOURCE_REPO = "kinotvapp/kino-plugin-xuper"

    /** The `id` in [SOURCE_REPO]'s `kino-plugin.json`. */
    const val MANIFEST_ID = "xuper"

    /**
     * Whether [record] is that one recognized Xuper install. Exact-string equality against
     * [PluginAddress.canonical] -- never loosened to an owner-only or prefix match: [PluginAddress]
     * appends `@<ref>` for anything but the default branch and `/<path>` for a subfolder, so
     * `kinotvapp/kino-plugin-xuper@some-branch`, `kinotvapp/kino-plugin-xuper/sub`, and any other
     * owner (e.g. `someone-else/kino-plugin-xuper`) must all be refused, not just a different repo
     * name. Production callers: [pluginHostFor] (the `kino.xuper.*` functions) and
     * [PluginContentSource] (the [XuperStreams] carve-out on its streams) and [PluginRegistry.accessFor]
     * (the same carve-out at playback, via `PluginAccess.Ready.xuper`); call THIS from a test too, never
     * reproduce the comparison, or a test can go on passing after the real gate silently changes.
     */
    fun grants(record: InstalledRecord): Boolean = record.address == SOURCE_REPO
}

/**
 * Manifest ids only one repo may use in the community list: any other repo claiming one is an impostor
 * and is never shown, whatever its stars (`owner/repo` compared case-insensitively). Only the list:
 * the `kino.xuper.*` gate stays [XuperPrivilege.grants], by exact install address.
 */
object ReservedPluginIds {
    val OWNERS: Map<String, String> = mapOf(XuperPrivilege.MANIFEST_ID to XuperPrivilege.SOURCE_REPO)
}
