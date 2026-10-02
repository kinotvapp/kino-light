package com.arkiv.player.ui.home

import com.arkiv.player.data.plugin.InstalledPlugin

/* The empty Home (spec 2026-09-28 §5), shared by the phone and the TV. */

internal const val EMPTY_HOME_TITLE = "Aún no tienes fuentes de contenido"
internal const val EMPTY_HOME_LINE = "Agrega un plugin para ver películas, series o canales en vivo."
internal const val EMPTY_HOME_ACTION = "Agregar plugin"
internal const val BROKEN_HOME_TITLE = "Tus fuentes no están funcionando"
internal const val BROKEN_HOME_LINE_PHONE = "Revisa tus plugins en Menú ▸ Plugins o agrega otro."
internal const val BROKEN_HOME_LINE_TV = "Revisa tus plugins en Ajustes ▸ Plugins o agrega otro."

data class HomeEmptyCopy(val title: String, val line: String, val action: String)

/**
 * What the empty state says. With plugins installed (all disabled, damaged or unresponsive) "you have no
 * sources yet" is false: it points the person at the plugins they already have instead: the drawer's Plugins
 * item on the phone, Ajustes ▸ Plugins on the TV ([isTv]).
 */
fun homeEmptyCopy(plugins: List<InstalledPlugin>, isTv: Boolean): HomeEmptyCopy =
    if (plugins.isEmpty()) HomeEmptyCopy(EMPTY_HOME_TITLE, EMPTY_HOME_LINE, EMPTY_HOME_ACTION)
    else HomeEmptyCopy(BROKEN_HOME_TITLE, if (isTv) BROKEN_HOME_LINE_TV else BROKEN_HOME_LINE_PHONE, EMPTY_HOME_ACTION)

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

/**
 * Whether a landing attempt actually put focus where it aimed. `requestFocus()` not throwing does not
 * mean the "Agregar plugin" button took focus (it may not be composed or attached yet), so that target
 * counts only once the button reports focus itself; the others keep trusting the request.
 */
fun tvHomeLandingHeld(landing: TvHomeLanding, requestSucceeded: Boolean, addSourcesFocused: Boolean): Boolean =
    if (landing == TvHomeLanding.ADD_SOURCES) addSourcesFocused else requestSucceeded

/** The empty state's button left (a source became usable) and took focus with it: the top bar takes it. */
fun emptyStateNeedsRefocus(wasEmpty: Boolean, isEmpty: Boolean, screenHasFocus: Boolean): Boolean =
    wasEmpty && !isEmpty && !screenHasFocus

internal const val XUPER_HOME_FAILED_PHONE = "No pudimos cargar el catálogo de Xuper, toca para reintentar"
internal const val XUPER_HOME_FAILED_TV = "No pudimos cargar el catálogo de Xuper."
internal const val XUPER_HOME_RETRY = "Reintentar"

/**
 * The notices Home shows for OTHER plugins whose `home()` failed with nothing to show (Xuper has its own, see
 * [xuperHomeFailed]): their sentence, for plugins still installed and usable, in a stable order. 0.9.46: Caracol
 * threw on every device outside Colombia and its rows just never appeared.
 */
fun pluginHomeFailureLines(plugins: List<InstalledPlugin>, failed: Map<String, String>): List<String> =
    plugins.filter { it.id in failed && it.isUsable && !it.needsSetup && !com.arkiv.player.data.plugin.XuperPrivilege.grants(it.record) }
        .sortedBy { it.id }
        .map { failed.getValue(it.id) }

/**
 * Whether Home says the Xuper catalog failed (and offers to retry) instead of just leaving its rows out: the plugin
 * pass is over, the recognized Xuper plugin is installed, usable and has `home`, and not one of its rows came back
 * (not even the last good snapshot). Xuper's Home is never legitimately empty, unlike another plugin's, which may
 * simply have no rows to offer. 0.9.45 (ERRORES-AKR): every VOD root came back empty and Home showed nothing at all.
 */
fun xuperHomeFailed(
    plugins: List<InstalledPlugin>,
    pluginRows: List<com.arkiv.player.data.plugin.PluginHomeRow>,
    settled: Boolean,
): Boolean {
    if (!settled) return false
    val xuper = plugins.firstOrNull {
        it.isUsable && !it.needsSetup && "home" in it.manifest.capabilities &&
            com.arkiv.player.data.plugin.XuperPrivilege.grants(it.record)
    } ?: return false
    return pluginRows.none { it.pluginId == xuper.id }
}
