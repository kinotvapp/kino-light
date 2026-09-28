package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.PluginStatus

/** What the button on a recommended-plugin row does. Phone and TV share this decision. */
enum class CatalogAction {
    /** Not installed, or installed but damaged: goes through the consent sheet ([PluginsViewModel.installFromCatalog]). */
    INSTALL,

    /** Installed and usable but a required setting is empty: opens Configurar ([PluginsViewModel.openSettings]). */
    CONFIGURE,

    /** Installed but switched off or unresponsive: turns it on ([PluginsViewModel.setEnabled]). */
    ENABLE,

    /** Installed and working: nothing left to do here (a disabled button). */
    INSTALLED,
}

/**
 * Damage wins over everything else: the files no longer match what was approved, and installing over
 * them (same address, so the installer treats it as an update that needs no new hosts) is the way back.
 * A disabled or unresponsive plugin is enabled before it is configured, because Configurar on
 * something that will not run would not make the row any more useful.
 */
fun catalogActionOf(row: CatalogRow): CatalogAction {
    val plugin = row.installed ?: return CatalogAction.INSTALL
    return when {
        plugin.status == PluginStatus.DAMAGED -> CatalogAction.INSTALL
        !plugin.isUsable -> CatalogAction.ENABLE
        plugin.needsSetup -> CatalogAction.CONFIGURE
        else -> CatalogAction.INSTALLED
    }
}

/**
 * The row of what the person was already using ([com.arkiv.player.data.plugin.catalog.CatalogEntry.legacyDefault])
 * goes first; every other row keeps the order the catalog gave it (the sort is stable).
 */
fun legacyFirst(rows: List<CatalogRow>): List<CatalogRow> = rows.sortedByDescending { it.entry.legacyDefault }

/**
 * What a recommended or community card's action does: the action [catalogActionOf] says fits its state.
 * An installed plugin that needs nothing does nothing. Phone, TV and the source picker share it.
 */
internal fun runCatalogAction(vm: PluginsViewModel, row: CatalogRow) {
    val installed = row.installed
    when (catalogActionOf(row)) {
        CatalogAction.INSTALL -> vm.installFromCatalog(row.entry)
        CatalogAction.CONFIGURE -> installed?.let { vm.openSettings(it.id) }
        CatalogAction.ENABLE -> installed?.let { vm.setEnabled(it.id, true) }
        CatalogAction.INSTALLED -> Unit
    }
}
