package com.arkiv.player.ui.home

import com.arkiv.player.data.plugin.InstalledPlugin

/* The empty Home (spec 2026-09-28 §5), shared by the phone and the TV. */

internal const val EMPTY_HOME_TITLE = "Aún no tienes fuentes de contenido"
internal const val EMPTY_HOME_LINE = "Agrega un plugin para ver películas, series o canales en vivo."
internal const val EMPTY_HOME_ACTION = "Agregar plugin"
internal const val BROKEN_HOME_TITLE = "Tus fuentes no están funcionando"
internal const val BROKEN_HOME_LINE = "Revisa tus plugins en Ajustes ▸ Plugins o agrega otro."

data class HomeEmptyCopy(val title: String, val line: String, val action: String)

/**
 * What the empty state says. With plugins installed (all disabled, damaged or unresponsive) "you have no
 * sources yet" is false: it points the person at the plugins they already have instead.
 */
fun homeEmptyCopy(plugins: List<InstalledPlugin>): HomeEmptyCopy =
    if (plugins.isEmpty()) HomeEmptyCopy(EMPTY_HOME_TITLE, EMPTY_HOME_LINE, EMPTY_HOME_ACTION)
    else HomeEmptyCopy(BROKEN_HOME_TITLE, BROKEN_HOME_LINE, EMPTY_HOME_ACTION)

/**
 * Ruling R15: nothing can fill Home. No plugin is usable, no plugin row is on screen and the live module
 * has no provider. Library and continue-watching rows, if any, still show above the empty state.
 */
fun homeShowsEmptyState(plugins: List<InstalledPlugin>, pluginRowCount: Int, liveAvailable: Boolean): Boolean =
    plugins.none { it.isUsable } && pluginRowCount == 0 && !liveAvailable

/** Where the TV Home puts focus when it opens and there is no card to restore. */
enum class TvHomeLanding { ADD_SOURCES, FIRST_CARD, TOP_BAR }

/**
 * An empty Home lands on its "Agregar plugin" button even when "Continuar viendo" still holds cards
 * (orphans of plugins that are gone or broken): the button is the only way forward. Otherwise the
 * first continue card, and with none the top bar.
 */
fun tvHomeDefaultLanding(homeEmpty: Boolean, hasContinueCard: Boolean): TvHomeLanding = when {
    homeEmpty -> TvHomeLanding.ADD_SOURCES
    hasContinueCard -> TvHomeLanding.FIRST_CARD
    else -> TvHomeLanding.TOP_BAR
}

/** The empty state's button left (a source became usable) and took focus with it: the top bar takes it. */
fun emptyStateNeedsRefocus(wasEmpty: Boolean, isEmpty: Boolean, screenHasFocus: Boolean): Boolean =
    wasEmpty && !isEmpty && !screenHasFocus
