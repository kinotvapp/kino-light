package com.arkiv.player.data.plugin

/**
 * The public repos whose installed plugin runtime gets the extra `kino.xuper.*` host functions: the
 * official one ([SOURCE_REPO]) and the address it had before it moved ([LEGACY_SOURCE_REPOS]).
 * Checked against [InstalledPlugin.record]'s `address` — set once by [PluginInstaller] from a
 * validated fetch, never editable by the person and never the manifest's self-declared `id` (which
 * any other repo could copy verbatim).
 */
object XuperPrivilege {
    /** Where Xuper lives since 2026-09-28, and what every new install (the updating-device migration) uses. */
    const val SOURCE_REPO = "xuper-plugin/kino-plugin-xuper"

    /**
     * Where Xuper lived before it moved. That repo stays online without the `kino-plugin` topic, so
     * discovery never offers it again, but whoever installed from it keeps a working Xuper, updates included.
     */
    val LEGACY_SOURCE_REPOS: Set<String> = setOf("kinotvapp/kino-plugin-xuper")

    /** [SOURCE_REPO] and [LEGACY_SOURCE_REPOS]: every address [isOfficial] accepts. */
    val OFFICIAL_REPOS: Set<String> = setOf(SOURCE_REPO) + LEGACY_SOURCE_REPOS

    /** The `id` in [SOURCE_REPO]'s `kino-plugin.json`. */
    const val MANIFEST_ID = "xuper"

    /**
     * Whether [address] (a [PluginAddress.canonical]) is one of the official Xuper repos, new or legacy.
     * Exact-string equality -- never loosened to an owner-only, prefix or case-insensitive match:
     * [PluginAddress] appends `@<ref>` for anything but the default branch and `/<path>` for a
     * subfolder, so `xuper-plugin/kino-plugin-xuper@some-branch`, `kinotvapp/kino-plugin-xuper/sub`,
     * and any other owner (e.g. `someone-else/kino-plugin-xuper`) must all be refused, not just a
     * different repo name. Every place that tells Xuper apart by address calls this (or [grants]).
     */
    fun isOfficial(address: String?): Boolean = address in OFFICIAL_REPOS

    /**
     * Whether [record] is the recognized Xuper install: [isOfficial] on its install address.
     * Production callers: [pluginHostFor] (the `kino.xuper.*` functions) and [PluginContentSource] (the [XuperStreams] carve-out on its streams) and [PluginRegistry.accessFor]
     * (the same carve-out at playback, via `PluginAccess.Ready.xuper`); call THIS from a test too, never
     * reproduce the comparison, or a test can go on passing after the real gate silently changes.
     */
    fun grants(record: InstalledRecord): Boolean = isOfficial(record.address)
}

/**
 * Manifest ids only their own repos may use in the community list: any other repo claiming one is an
 * impostor and is never shown, whatever its stars (`owner/repo` compared case-insensitively). Only the
 * list: the `kino.xuper.*` gate stays [XuperPrivilege.grants], by exact install address.
 */
object ReservedPluginIds {
    val OWNERS: Map<String, Set<String>> =
        mapOf(XuperPrivilege.MANIFEST_ID to XuperPrivilege.OFFICIAL_REPOS)
}
