package com.arkiv.player.ui.home

import com.arkiv.player.data.plugin.InstalledPlugin

/* The empty Home (spec 2026-09-28 §5), shared by the phone and the TV. */

internal const val EMPTY_HOME_TITLE = "Aún no tienes fuentes de contenido"
internal const val EMPTY_HOME_LINE = "Agrega un plugin para ver películas, series o canales en vivo."
internal const val EMPTY_HOME_ACTION = "Agregar plugin"

/**
 * Ruling R15: nothing can fill Home. No plugin is usable, no plugin row is on screen and the live module
 * has no provider. Library and continue-watching rows, if any, still show above the empty state.
 */
fun homeShowsEmptyState(plugins: List<InstalledPlugin>, pluginRowCount: Int, liveAvailable: Boolean): Boolean =
    plugins.none { it.isUsable } && pluginRowCount == 0 && !liveAvailable

/** Where the TV Home puts focus when nothing is in progress ("Continuar viendo" empty). */
enum class TvHomeLanding { ADD_SOURCES, TOP_BAR }

fun tvHomeDefaultLanding(homeEmpty: Boolean): TvHomeLanding = if (homeEmpty) TvHomeLanding.ADD_SOURCES else TvHomeLanding.TOP_BAR

/** The empty state's button left (a source became usable) and took focus with it: the top bar takes it. */
fun emptyStateNeedsRefocus(wasEmpty: Boolean, isEmpty: Boolean, screenHasFocus: Boolean): Boolean =
    wasEmpty && !isEmpty && !screenHasFocus
