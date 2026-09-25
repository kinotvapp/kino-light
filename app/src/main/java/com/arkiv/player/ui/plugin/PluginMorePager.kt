package com.arkiv.player.ui.plugin

import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPage
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.plugin.PluginSetupRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/**
 * "Ver más" paging over a plugin's `browse` (or its search continued with a cursor): the first
 * page, then each `next`, one load at a time. It stops at the end, at a page that brings nothing
 * new, at a cursor that repeats, and at [maxItems] — a plugin can't keep the screen loading
 * forever. Titles are unique by the plugin's item id (the grid keys by it). Pure: the ViewModel
 * only runs it.
 */
class PluginMorePager(
    private val load: suspend (cursor: String?) -> GatewayPage,
    private val maxItems: Int = MAX_ITEMS,
) {
    data class State(
        val items: List<GatewayResult> = emptyList(),
        val loading: Boolean = false,
        val ended: Boolean = false,
        /** Spanish, shown instead of (or under) the grid. */
        val error: String? = null,
        /** Set when the error is "configure this plugin": the screen offers "Configurar". */
        val setupPluginId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()
    private var cursor: String? = null
    private var started = false
    private val seenCursors = HashSet<String>()

    /** Loads the next page; does nothing while one is loading or after the end. */
    suspend fun loadMore() {
        if (!mutex.tryLock()) return
        try {
            val s = _state.value
            if (s.ended || (started && cursor == null)) return
            _state.update { it.copy(loading = true, error = null, setupPluginId = null) }
            val page = try {
                load(cursor)
            } catch (e: CancellationException) {
                _state.update { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = messageOf(e), setupPluginId = (e as? PluginSetupRequiredException)?.pluginId) }
                return
            }
            started = true
            val known = _state.value.items.map(::keyOf).toHashSet()
            val fresh = page.items.filter { known.add(keyOf(it)) }
            val items = (_state.value.items + fresh).take(maxItems)
            val next = page.next?.takeIf { it.isNotEmpty() && seenCursors.add(it) }
            cursor = next
            val ended = next == null || fresh.isEmpty() || items.size >= maxItems
            _state.update { it.copy(items = items, loading = false, ended = ended) }
        } finally {
            mutex.unlock()
        }
    }

    /** After an error: try the same page again. */
    suspend fun retry() = loadMore()

    private fun keyOf(r: GatewayResult) = r.extra["pluginItemId"] ?: r.ref

    companion object {
        const val MAX_ITEMS = 1000

        fun messageOf(e: Exception): String = when (e) {
            is PluginSetupRequiredException -> e.message ?: "Configura el plugin en Ajustes ▸ Plugins"
            is GatewayBlockedException -> e.message ?: "Este contenido no está disponible en tu región"
            is GatewayException -> e.message ?: "No se pudo cargar, vuelve a intentarlo"
            else -> "No se pudo cargar, vuelve a intentarlo"
        }
    }
}
