package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.catalog.CatalogArt
import java.io.File

/*
 * The pure decisions behind an installed plugin's card (the Instalados tab, phone and TV): what the card's
 * tile, name/version line, status line and switch show, and what would fix a plugin that isn't just working.
 * Nothing here touches Compose, so the phone and the TV share it and it is tested on the JVM. It reuses
 * [tileColor], [installedIconFile] and [cardStatusLabel] (see CatalogCardStyle.kt) so an installed card and a
 * recommended one read the same tile colour, the same icon-or-initial rule and the same short status words.
 */

/** What one installed plugin's card draws, computed once so the phone and the TV read the same values. */
internal data class InstalledCardModel(
    /** The plugin's own name, undecorated: what the tile's initial and the switch's description are built from. */
    val name: String,
    /** "Internet Archive · 1.1.0": the name and the installed version, the card's one line for both. */
    val nameLine: String,
    /** The tile's background, `0xAARRGGBB` (see [tileColor]). */
    val tileColorArgb: Long,
    /** The icon drawn on the tile, or null for the plugin's initial (see [installedIconFile]). */
    val iconFile: File?,
    /** The status in the short words a card has room for (see [cardStatusLabel]); every status has one, "Activo" included. */
    val statusLabel: String,
    /** Whether [statusLabel] reads as a problem (red), which is every status but [PluginStatus.ACTIVE] -- the
     * same rule the row this card replaces used, so a pending update stays flagged red as it always was. */
    val statusIsProblem: Boolean,
    /** The on/off switch's position: whether the plugin is usable right now ([InstalledPlugin.isUsable]). */
    val switchChecked: Boolean,
    /** Whether the switch can be toggled at all: not while the plugin is [PluginStatus.DAMAGED] (same as today). */
    val switchEnabled: Boolean,
    /** Whether "Configurar" belongs among this plugin's actions: only when its manifest declares settings. */
    val hasSettings: Boolean,
    /** The one action that would fix [statusLabel], in the word the switch/dialog already uses for it, or null
     * for a plugin that needs nothing fixed (see [installedFixingAction]). */
    val fixingAction: String?,
)

/**
 * [plugin] as its Instalados card reads it. [art] is its catalog entry's art, used only while the plugin has
 * no icon of its own (see [installedIconFile]); a plugin installed from a repo the catalog no longer lists
 * passes null and falls back to its own icon or the initial, exactly as the row it replaces did.
 */
internal fun installedCardModel(plugin: InstalledPlugin, art: CatalogArt?): InstalledCardModel {
    val status = plugin.status
    return InstalledCardModel(
        name = plugin.manifest.name,
        nameLine = "${plugin.manifest.name} · ${plugin.record.version}",
        tileColorArgb = tileColor(art),
        iconFile = installedIconFile(plugin.iconFile, art),
        statusLabel = cardStatusLabel(status),
        statusIsProblem = status != PluginStatus.ACTIVE,
        switchChecked = plugin.isUsable,
        switchEnabled = status != PluginStatus.DAMAGED,
        hasSettings = plugin.manifest.settings.isNotEmpty(),
        fixingAction = installedFixingAction(status),
    )
}

/**
 * The one action that would fix [status]: "Configurar" for a required setting left empty, "Activar" for a
 * plugin switched off or gone unresponsive (the switch's own action), or "Reinstalar" for one whose files no
 * longer match what was approved -- the same way out a damaged plugin has today, installing over it again.
 * Null for [PluginStatus.ACTIVE] (nothing to fix) and [PluginStatus.UPDATE_PENDING] (already reachable
 * through "Buscar actualización", not a problem with the plugin as it stands).
 */
internal fun installedFixingAction(status: PluginStatus): String? = when (status) {
    PluginStatus.NEEDS_SETUP -> "Configurar"
    PluginStatus.DAMAGED -> "Reinstalar"
    PluginStatus.DISABLED, PluginStatus.UNRESPONSIVE -> "Activar"
    PluginStatus.ACTIVE, PluginStatus.UPDATE_PENDING -> null
}

/**
 * For each of [count] cards laid out [columns] at a time, whether its line of the grid needs room for a
 * message: [messageIndex] is the one card that has one (at most one does -- [PluginsUiState.messagePluginId]
 * names a single plugin, see [rowMessagePluginId]), or null while nothing is showing one. A card without the
 * message still reserves its line's room for it, so every card of that line ends at the same height; every
 * other line reserves nothing.
 */
internal fun installedGridLinesWithMessage(count: Int, messageIndex: Int?, columns: Int): List<Boolean> {
    if (messageIndex == null) return List(count) { false }
    val messageLine = messageIndex / columns
    return (0 until count).map { it / columns == messageLine }
}
