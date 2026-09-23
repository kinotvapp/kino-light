package com.arkiv.player.ui.search

import com.arkiv.player.data.ditu.CaracolFailure

/**
 * What happened with each source in the last source search: which ones responded and which ones
 * went down, with their error.
 *
 * Exists because the spec asks for a source's error to show without covering up what the other
 * ones did bring. Before, `SourceError` only went to the log: with Caracol down, its tab said "Sin
 * resultados en Caracol." as if there were nothing, and with everything down the screen suggested
 * trying another season.
 *
 * Sources go by the name they travel under in search events (`"magis"`, `"ditu"`), the same one
 * `GatewayResult.toPlaySource` sorts them by.
 */
data class SourcesState(
    val responded: Set<String> = emptySet(),
    /** Source → its error message, in the order they failed. */
    val failed: Map<String, String> = emptyMap(),
    /** Source → its error's exception, for the ones that sent one (see `SearchEvent.SourceError.cause`). */
    val causes: Map<String, Throwable> = emptyMap(),
) {
    fun withResponse(source: String) = copy(responded = responded + source)
    fun withFailure(source: String, error: String, cause: Throwable? = null) = copy(
        failed = failed + (source to error),
        causes = if (cause == null) causes else causes + (source to cause),
    )
}

/** A source's tab by its name in the events, or null if it isn't known which one it is. */
internal fun tabForSource(source: String): SourceTab? = when (source) {
    "magis" -> SourceTab.MAGIS
    "ditu" -> SourceTab.CARACOL
    else -> null
}

/**
 * How a source is named in notices. "Una fuente" covers the name `CompositeSource` uses when a
 * source goes down before announcing itself.
 */
private fun sourceName(source: String): String = tabForSource(source)?.label ?: "Una fuente"

private fun isDown(tab: SourceTab, state: SourcesState): Boolean =
    state.failed.keys.any { tabForSource(it) == tab }

/**
 * One line per down source matching [tab] ("Todo" shows them all). They go above the list, with
 * or without results: if Caracol goes down and Magis responds, Magis's results show along with
 * Caracol's line. With no errors the list is empty and the screen stays as it was before.
 *
 * Caracol's line is written by [CaracolFailure], in plain human words. Magis's and an unnamed
 * source's stay as before: the name and the error text.
 */
fun downSourceNotices(state: SourcesState, tab: SourceTab): List<String> =
    state.failed
        .filter { (source, _) -> tab == SourceTab.ALL || tabForSource(source) == tab }
        .map { (source, error) ->
            if (tabForSource(source) == SourceTab.CARACOL) {
                CaracolFailure.inSearch(state.causes[source], error)
            } else {
                "${sourceName(source)} no respondió: $error"
            }
        }

/** What the screen says when the search finished with no results at all. */
fun noSourcesText(state: SourcesState): String =
    if (state.failed.isNotEmpty() && state.responded.isEmpty()) NO_RESPONSE_TEXT else NO_SOURCES_TEXT

/**
 * What an empty source tab says, or null if its source went down: that's already explained by
 * [downSourceNotices]'s line, and "Buscando…" or "Sin resultados" would contradict it.
 */
fun emptyTabText(tab: SourceTab, searching: Boolean, state: SourcesState): String? = when {
    isDown(tab, state) -> null
    searching -> "Buscando en ${tab.label}…"
    else -> "Sin resultados en ${tab.label}."
}

/** What a source's section says, in the phone's "Todo", when it brought back nothing. */
fun emptySectionText(source: SourceTab, state: SourcesState): String =
    if (isDown(source, state)) "No respondió" else "Sin resultados"

/** The usual text: the search reached the sources and none had anything. */
internal const val NO_SOURCES_TEXT =
    "No se encontraron fuentes. Vuelve atrás y prueba con otra temporada/capítulo, o sin especificar ninguno."

/** No source answered at all: the "try another season" advice doesn't help when the problem is the connection. */
internal const val NO_RESPONSE_TEXT =
    "Ninguna fuente respondió. Revisa tu conexión a internet y vuelve a intentar."
