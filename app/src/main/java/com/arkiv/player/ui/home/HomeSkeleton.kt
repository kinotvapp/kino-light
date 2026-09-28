package com.arkiv.player.ui.home

import com.arkiv.player.data.plugin.InstalledPlugin

/*
 * Home's loading state, shared by the phone and the TV: grey placeholder rows where the plugin rows
 * will land, instead of a black gap (or a centered spinner) while the plugins answer. Skeletons also
 * cover the case where nothing at all fills Home yet: they sit exactly where the content arrives, so
 * the screen does not jump from a spinner to rows.
 */

/** What a screen reader says for the skeleton rows (they show no text of their own). */
internal const val HOME_LOADING_LINE = "Cargando tus fuentes…"

/** The phone holds room for this many plugin rows while they load. */
const val PHONE_HOME_SKELETON_SLOTS = 2

/** The TV Home's rows zone shows exactly this many rows (see `rowsRegionHeight` in TvHomeScreen). */
const val TV_HOME_VISIBLE_ROWS = 2

/**
 * Whether plugin rows may still arrive: a usable plugin exists and the Home pass is not over
 * ([pluginRowsSettled] false, see `PluginHomeLoad`). Never true together with [homeShowsEmptyState],
 * which needs no usable plugin.
 */
fun homePluginRowsLoading(plugins: List<InstalledPlugin>, pluginRowsSettled: Boolean): Boolean =
    !pluginRowsSettled && plugins.any { it.isUsable }

/**
 * How many skeleton rows to draw below the real plugin rows: the [slots] still free after the
 * [rowsAbove] other rows (continue watching, "Para ti", live channels) and the [pluginRowCount] plugin
 * rows that already arrived. Each real row takes a skeleton's place, so skeletons never pad out a set of
 * rows that already fills the space, and none show once the pass settled ([loading] false).
 */
fun homeSkeletonRowCount(loading: Boolean, rowsAbove: Int, pluginRowCount: Int, slots: Int): Int =
    if (!loading) 0 else (slots - rowsAbove - pluginRowCount).coerceAtLeast(0)

/** The lazy list key of skeleton row [index]: stable, so a real row replacing it does not move the list. */
fun homeSkeletonKey(index: Int): String = "skeleton-$index"

/** The TV hero shows grey title/subtitle bars only while something is about to be featured. */
fun tvHeroShowsSkeleton(hasFeatured: Boolean, loading: Boolean): Boolean = !hasFeatured && loading
