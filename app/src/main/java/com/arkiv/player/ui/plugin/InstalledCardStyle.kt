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
    /** The tile's pill: [SEALED_CODE_PILL] for sealed code (apiVersion 5), else none -- as every installed card had. */
    val pill: String? = null,
)

/**
 * [plugin] as its Instalados card reads it. [art] is its catalog entry's art, used only for whichever of the
 * tile's icon and colour the plugin's own manifest and files do not supply (see [installedTileArt]); a
 * plugin installed from a repo the catalog no longer lists passes null and falls back entirely to its own
 * icon/colour or the neutral default, exactly as the row it replaces did.
 */
internal fun installedCardModel(plugin: InstalledPlugin, art: CatalogArt?): InstalledCardModel {
    val status = plugin.status
    val tileArt = installedTileArt(plugin, art)
    return InstalledCardModel(
        name = plugin.manifest.name,
        nameLine = "${plugin.manifest.name} · ${plugin.record.version}",
        tileColorArgb = tileColor(tileArt),
        iconFile = tileArt.iconFile,
        statusLabel = cardStatusLabel(status),
        statusIsProblem = status != PluginStatus.ACTIVE,
        switchChecked = plugin.isUsable,
        switchEnabled = status != PluginStatus.DAMAGED,
        hasSettings = plugin.manifest.settings.isNotEmpty(),
        pill = if (plugin.manifest.entrySealed) SEALED_CODE_PILL else null,
    )
}

/**
 * The [CatalogArt] an installed plugin's card actually draws: [plugin]'s own colour
 * ([com.arkiv.player.data.plugin.PluginManifest.color]) and icon ([installedIconFile]) first -- what a
 * plugin installed by `usuario/repositorio` outside the catalog (the whole point of "Agregar") ships in its
 * own manifest and files -- [art]'s for whichever side [plugin] lacks, and neither once [plugin] has none
 * and [art] has nothing to offer either (then [tileColor] and the initial draw the neutral default, exactly
 * as they always did). Feeding this straight into [CardTile] means the tile code itself is not duplicated
 * for an installed card.
 */
internal fun installedTileArt(plugin: InstalledPlugin, art: CatalogArt?): CatalogArt = CatalogArt(
    colorHex = plugin.manifest.color ?: art?.colorHex,
    iconFile = installedIconFile(plugin.iconFile, art),
)

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

/**
 * Which cards reserve a line kind that only some cards have (a live plugin's "Lista recortada: …"
 * notice): every card of a grid line where at least one card [has] it, so the line ends at one
 * height -- the same rule as [installedGridLinesWithMessage], for any number of cards. Pure.
 */
internal fun installedGridLinesReserving(has: List<Boolean>, columns: Int): List<Boolean> {
    val lines = has.indices.filter { has[it] }.mapTo(HashSet()) { it / columns }
    return has.indices.map { it / columns in lines }
}

/**
 * How many lines a card's message reserves, as BOTH `minLines` and `maxLines` -- on the card that has one
 * and on the blank placeholder its line's neighbours show instead ([installedGridLinesWithMessage]). Fixed,
 * not however many lines the message's own text happens to need: a `maxLines`-only cap still lets a
 * one-line message (or a blank placeholder) render at one line's height, which is exactly what made a
 * one-line message's card taller than its line's neighbour before this was pinned.
 */
internal fun installedMessageLines(): Int = 2

/**
 * How many lines the hosts line ("Se conectará a: …") reserves, as BOTH `minLines` and `maxLines`, on every
 * card: unlike the message it is never absent (every installed plugin has hosts), so it is not reserved per
 * grid line -- every card always uses this many lines for it, so a plugin with more hosts than its neighbour
 * never makes its own card, or the line it shares, taller.
 */
internal fun installedHostsLines(): Int = 2
