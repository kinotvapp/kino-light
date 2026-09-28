package com.arkiv.player.ui.plugin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.onboarding.Onboarding
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.ui.rememberGraph

/*
 * "Elige tus fuentes" (spec 2026-09-28 §3): the recommended catalog and the community list, with the same
 * cards, consent, install and Configurar as Ajustes ▸ Plugins ▸ Recomendados. Phone and TV share this file.
 * Mandatory since the 2026-09-28 amendment: there is no way past it but "Listo" with a source installed;
 * Back on the picker opened at start leaves the app ([onSourcePickerBack]).
 */

/** The picker's route in both roots. Not a tab: no top bar, no drawer. */
const val SOURCE_PICKER_ROUTE = "sources"

internal const val SOURCE_PICKER_TITLE = "Elige tus fuentes"
internal const val SOURCE_PICKER_LINE = "Instala las fuentes que quieras usar. Puedes cambiarlas cuando quieras en Ajustes ▸ Plugins."
internal const val SOURCE_PICKER_DONE = "Listo"
internal const val RECOMMENDED_TITLE = "Recomendados"

/**
 * "Listo" does something once at least one plugin is installed and switched on ([Onboarding.hasSource]):
 * the same rule that reopens the picker at start, so leaving it never leads to it again next time.
 */
internal fun pickerCanFinish(plugins: List<InstalledPlugin>): Boolean = Onboarding.hasSource(plugins)

/**
 * The TV picker lost focus to nothing (a card that held it left the list, a refresh replaced the rows, a
 * dialog closed): it takes it back, but only after the initial focus was placed and never while a dialog
 * (consent, Configurar) has it.
 */
internal fun pickerNeedsRefocus(initialFocusPlaced: Boolean, screenHasFocus: Boolean, dialogOpen: Boolean): Boolean =
    initialFocusPlaced && !screenHasFocus && !dialogOpen

/**
 * "Listo": pops back to what was under the picker, or goes Home when nothing was ([goHome]). Nothing is
 * written: whether the picker opens at the next start depends only on the installed plugins
 * ([Onboarding.opensPickerOnStart]).
 *
 * Navigation runs only while the picker is still the current destination ([isShowing]): during the
 * NavHost's exit fade the picker stays composed and clickable, and a second tap on "Listo" would otherwise
 * pop Home itself and leave a blank NavHost.
 */
internal fun leaveSourcePicker(isShowing: () -> Boolean, popBack: () -> Boolean, goHome: () -> Unit) {
    if (!isShowing()) return
    if (!popBack()) goHome()
}

/**
 * System Back on the picker (phone and TV). [mandatory] (the root opened it at start because there is no
 * source): leaves the app ([exitApp]), never revealing the Home under it; the next start opens it again.
 * Otherwise the person opened it from the empty Home they were allowed to keep for the rest of the session
 * (a last plugin removed mid-session), and Back returns there ([popBack]).
 */
internal fun onSourcePickerBack(mandatory: Boolean, exitApp: () -> Unit, popBack: () -> Unit) {
    if (mandatory) exitApp() else popBack()
}

/** Leaves the app from the picker: the Activity finishes, as Back on the TV's Home does. */
internal fun exitFromSourcePicker(context: Context) {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) {
            c.finish()
            return
        }
        c = c.baseContext
    }
}

/**
 * The auto-open only lands over Home, never over a player a notification deep link opened: the root waits
 * until Home is showing (back from that player) and opens it then.
 */
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
