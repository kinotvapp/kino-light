package com.arkiv.player.ui.plugin

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.onboarding.OnboardingPrefs
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.ui.rememberGraph

/*
 * "Elige tus fuentes" (spec 2026-09-28 §3): the recommended catalog and the community list, with the same
 * cards, consent, install and Configurar as Ajustes ▸ Plugins ▸ Recomendados. Phone and TV share this file.
 */

/** The picker's route in both roots. Not a tab: no top bar, no drawer. */
const val SOURCE_PICKER_ROUTE = "sources"

internal const val SOURCE_PICKER_TITLE = "Elige tus fuentes"
internal const val SOURCE_PICKER_LINE = "Instala las fuentes que quieras usar. Puedes cambiarlas cuando quieras en Ajustes ▸ Plugins."
internal const val SOURCE_PICKER_DONE = "Listo"
internal const val SOURCE_PICKER_SKIP = "Ahora no"
internal const val RECOMMENDED_TITLE = "Recomendados"

/** Ruling R10: "Listo" does something once at least one plugin is installed, whatever its state. */
internal fun pickerCanFinish(plugins: List<InstalledPlugin>): Boolean = plugins.isNotEmpty()

/**
 * The TV picker lost focus to nothing (a card that held it left the list, a refresh replaced the rows, a
 * dialog closed): it takes it back, but only after the initial focus was placed and never while a dialog
 * (consent, Configurar) has it.
 */
internal fun pickerNeedsRefocus(initialFocusPlaced: Boolean, screenHasFocus: Boolean, dialogOpen: Boolean): Boolean =
    initialFocusPlaced && !screenHasFocus && !dialogOpen

/**
 * Every way out of the picker ("Listo", "Ahora no", system Back; ruling R9) ends here: the done flag is
 * written FIRST, so the picker never opens by itself again even if navigation fails, then it pops back to
 * what was under it, or goes Home when nothing was ([goHome]).
 *
 * Navigation runs only while the picker is still the current destination ([isShowing]): during the
 * NavHost's exit fade the picker stays composed and clickable, and a second tap on "Listo" would otherwise
 * pop Home itself and leave a blank NavHost.
 */
internal fun leaveSourcePicker(prefs: OnboardingPrefs, isShowing: () -> Boolean, popBack: () -> Boolean, goHome: () -> Unit) {
    prefs.setSourcePickerDone(true)
    if (!isShowing()) return
    if (!popBack()) goHome()
}

/**
 * "Ahora no" is off while an install or check runs: leaving then would drop the picker's view model and
 * cancel the install half-way. Back stays active (ruling R9).
 */
internal fun pickerCanSkip(busy: Boolean): Boolean = !busy

/** The auto-open (ruling R8) only lands over Home, never over a player a notification deep link opened. */
internal fun pickerAutoOpensOver(currentRoute: String?): Boolean = currentRoute == "home"

/** The picker's own [PluginsViewModel], scoped to its route: catalog, art and community discovery. */
@Composable
internal fun sourcePickerViewModel(): PluginsViewModel {
    val graph = rememberGraph()
    return viewModel(
        key = "source-picker",
        factory = viewModelFactory {
            initializer {
                PluginsViewModel(graph.pluginAdmin, catalogProvider = graph.pluginCatalog, artProvider = graph.catalogArt, discovery = graph.pluginDiscovery)
            }
        },
    )
}
