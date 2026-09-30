package com.arkiv.player.ui.plugin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.onboarding.Onboarding
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.NuvioPluginInstaller
import com.arkiv.player.data.plugin.PluginAdmin
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.data.plugin.catalog.CatalogArtProvider
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import com.arkiv.player.data.plugin.catalog.CatalogProvider
import com.arkiv.player.data.plugin.discovery.PluginDiscoveryProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.rememberGraph

/*
 * "Elige tus fuentes" (spec 2026-09-28 §3): the recommended catalog and the community list, with the same
 * cards, consent, install and Configurar as the Plugins screen's Recomendados. Phone and TV share this file.
 * Mandatory since the 2026-09-28 amendment: there is no way past it but "Continuar" with a source installed;
 * Back on the picker opened at start leaves the app ([onSourcePickerBack]).
 */

/** The picker's route in both roots. Not a tab: no top bar, no drawer. */
const val SOURCE_PICKER_ROUTE = "sources"

internal const val SOURCE_PICKER_TITLE = "Elige tus fuentes"
/** The line under the title: where the sources can be changed later, the drawer's Plugins on the phone, Ajustes ▸ Plugins on the TV. */
internal fun sourcePickerLine(isTv: Boolean): String =
    "Instala las fuentes que quieras usar. Puedes cambiarlas cuando quieras en ${com.arkiv.player.data.plugin.PluginsPlace.of(isTv)}."
internal const val SOURCE_PICKER_DONE = "Continuar"
internal const val RECOMMENDED_TITLE = "Recomendados"

/** First-run mini guide (phone only, spec 2026-09-30 §mini-guide): what a plugin is. */
internal const val SOURCE_PICKER_INTRO_WHAT_TITLE = "¿Qué son los plugins?"
internal const val SOURCE_PICKER_INTRO_WHAT_BODY = "Cada plugin conecta Kino con una fuente de contenido distinta."
/** Same guide: how to install one. */
internal const val SOURCE_PICKER_INTRO_HOW_TITLE = "Así los agregás"
internal const val SOURCE_PICKER_INTRO_HOW_BODY = "Tocá una tarjeta para instalarla. Podés agregar o quitar plugins cuando quieras."

/**
 * "Continuar" does something once at least one plugin is installed and switched on ([Onboarding.hasSource]):
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
 * "Continuar": pops back to what was under the picker, or goes Home when nothing was ([goHome]). Nothing is
 * written: whether the picker opens at the next start depends only on the installed plugins
 * ([Onboarding.opensPickerOnStart]).
 *
 * Navigation runs only while the picker is still the current destination ([isShowing]): during the
 * NavHost's exit fade the picker stays composed and clickable, and a second tap on "Continuar" would otherwise
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

/**
 * Leaves the app from the picker: the task goes to the back, exactly what system Back does on a phone's
 * root screen since Android 12. Never `finish()`: a finished MainActivity relaunched in the same process
 * stayed on the splash forever (fix round 1, seen on a Redmi), and the task kept in the back returns
 * straight to this picker, which is still the only thing to use.
 */
internal fun exitFromSourcePicker(context: Context) {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) {
            c.moveTaskToBack(true)
            return
        }
        c = c.baseContext
    }
}

/**
 * Where the start picker may open: over any route but a player a notification deep link opened (the root
 * waits until the person leaves it). Nothing else is reachable before then: [SourceDecisionCover] hides it.
 */
internal fun pickerMayOpenOver(currentRoute: String?): Boolean = currentRoute != null && !isPlayerRoute(currentRoute)

private fun isPlayerRoute(route: String): Boolean = route.startsWith("player/")

/** Where the root's start decision is: still deciding, deciding to open the picker, or settled. */
internal enum class StartGate { DECIDING, OPENING, DONE }

/**
 * Whether [SourceDecisionCover] hides the app: until the start decision is settled ([StartGate.DONE]), over
 * everything but a deep-linked player. The picker itself is navigated to before the gate becomes DONE.
 */
internal fun startCoverShows(gate: StartGate, currentRoute: String?): Boolean =
    gate != StartGate.DONE && !(currentRoute != null && isPlayerRoute(currentRoute))

/**
 * Re-checked right before the start picker is navigated to: the decision may be old (it waited for a
 * deep-linked player, or a restored back stack already went through "Continuar"), and a source installed
 * meanwhile means it must not open.
 */
internal fun startPickerStillNeeded(plugins: List<InstalledPlugin>): Boolean = !Onboarding.hasSource(plugins)

internal const val PICKER_INSTALLED_TITLE = "Tus plugins"

/**
 * The person's own plugins that are switched off or damaged and that no recommended or community card
 * ([shown]) already carries: without them, someone whose only plugin is off (a custom address, or offline
 * with only the seed catalog) would have nothing to press in a mandatory picker. Each row keeps its installed
 * address as its repo, so its action ([catalogActionOf]: "Activar", or "Instalar" to reinstall damaged files)
 * works on exactly that plugin.
 */
internal fun pickerInstalledRows(plugins: List<InstalledPlugin>, shown: List<CatalogRow>): List<CatalogRow> {
    val onCards = shown.mapNotNull { it.installed?.id }.toSet()
    return plugins
        .filter { (!it.record.enabled || it.record.damaged) && it.id !in onCards }
        .map { p ->
            CatalogRow(
                CatalogEntry(id = p.id, repo = p.record.address, name = p.manifest.name, description = p.manifest.description),
                installed = p,
            )
        }
}

/** A card's art for an installed plugin: its own color and icon. */
internal fun installedCardArt(plugin: InstalledPlugin?): CatalogArt? =
    plugin?.let { CatalogArt(it.manifest.color, it.iconFile) }

/**
 * What covers the app while the start decision is not settled ([startCoverShows]): black, a spinner, every
 * tap and key swallowed, focus held (TV), and Back leaving the app like the picker's own Back. Never Home.
 */
@Composable
internal fun SourceDecisionCover() {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    BackHandler { exitFromSourcePicker(context) }
    Box(
        Modifier
            .fillMaxSize()
            .background(ArkivBlack)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = ArkivRed)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * The picker's view model as [sourcePickerViewModel] builds it. [nuvioPluginInstaller] is required, not
 * optional: the picker's "Tus plugins" rows offer "Instalar" on a damaged plugin ([pickerInstalledRows]),
 * and a Nuvio-converted one can only be reinstalled by re-converting its scraper
 * ([PluginsViewModel.reinstall]). Without it the person, stuck in a mandatory picker, would be told to go
 * to Ajustes ▸ Plugins, which they cannot reach from here.
 */
internal fun newSourcePickerViewModel(
    admin: PluginAdmin,
    catalogProvider: CatalogProvider,
    artProvider: CatalogArtProvider,
    discovery: PluginDiscoveryProvider,
    nuvioPluginInstaller: NuvioPluginInstaller,
    io: CoroutineDispatcher = Dispatchers.IO,
): PluginsViewModel = PluginsViewModel(
    admin,
    io = io,
    catalogProvider = catalogProvider,
    artProvider = artProvider,
    discovery = discovery,
    nuvioPluginInstaller = nuvioPluginInstaller,
)

/** The picker's own [PluginsViewModel], scoped to its route: catalog, art and community discovery. */
@Composable
internal fun sourcePickerViewModel(): PluginsViewModel {
    val graph = rememberGraph()
    return viewModel(
        key = "source-picker",
        factory = viewModelFactory {
            initializer {
                newSourcePickerViewModel(graph.pluginAdmin, graph.pluginCatalog, graph.catalogArt, graph.pluginDiscovery, graph.nuvioPluginInstaller)
            }
        },
    )
}

