package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.ui.plugin.PluginMoreTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One category tile: a Magis home row's id (also the Xuper plugin's `browse` ref for it), its
 *  label, and a preview image taken from the row's first title. */
data class CategorySpec(val id: String, val title: String, val previewUrl: String?)

/**
 * The Categories screen, built from the REAL Magis catalog -- the SAME source as the Home (see
 * [MagisHomeCatalog]/[MagisHomeClassifier]). It shows one tile per genre/featured row the classifier
 * actually produced (a genre row only exists when the catalog has ≥ [MagisHomeClassifier.MIN_GENRE_SIZE]
 * titles in it), so only categories we truly have are painted -- instead of the old behavior that
 * listed every TMDB/AniList genre whether or not the catalog had it.
 *
 * Tapping a tile opens the installed Xuper plugin's generic "Ver más" ([browseTarget]): the plugin's
 * `browse` takes the row id as its ref (see `MagisPluginBridge.home`/`browse`), so it lands on that
 * category's real titles ([MagisHomeRow.all]), paged like any plugin row's "Ver más".
 *
 * The tiles only show while that plugin is usable ([xuperPluginId]): a disabled or uninstalled
 * plugin contributes no Home rows, and these tiles are those same rows, so they go too.
 */
class CategoriesViewModel(
    private val magisHome: MagisHomeCatalog,
    /** The installed plugins (`PluginRegistry.plugins`): where the Xuper plugin is looked up. */
    plugins: Flow<List<InstalledPlugin>>,
    /** Manual "recargar catálogo" pulses from the home's top bar (`AppGraph.homeReloads`). */
    reload: Flow<Unit> = emptyFlow(),
) : ViewModel() {

    private val _rows = MutableStateFlow<List<CategorySpec>>(emptyList())

    /** The usable Xuper plugin's manifest id, or null: the tiles' "Ver más" target. */
    private val pluginId: StateFlow<String?> = plugins
        .map { xuperPluginId(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The tiles to paint: none while there's no usable Xuper plugin to open them with. */
    val rows: StateFlow<List<CategorySpec>> = combine(_rows, pluginId) { specs, id -> if (id == null) emptyList() else specs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // TV screen's scroll position — survives navigating to a sub-screen and back.
    var tvScrollIndex: Int = 0
    var tvScrollOffset: Int = 0

    init {
        refresh(force = false)
        // The top-bar reload refetches the shared catalog, bypassing the cache.
        reload.onEach { refresh(force = true) }.launchIn(viewModelScope)
    }

    /**
     * (Re)builds the category tiles. [force] bypasses the 6 h cache and asks the portal (the reload
     * button); otherwise it's cache-first. A refetch that comes back empty keeps the current tiles
     * instead of blanking them -- unless there were none to begin with.
     */
    private fun refresh(force: Boolean) {
        viewModelScope.launch {
            _loading.value = true
            // Fetch + classification + tile building run off the main thread (weak-device jank).
            val specs = withContext(Dispatchers.Default) {
                runCatching { if (force) magisHome.load().rows else magisHome.rows() }
                    .getOrDefault(emptyList())
                    // Genre rows (magis_g_*) plus the featured "Estrenos"/"mejor valoradas" rows.
                    .filter { it.id.startsWith("magis_g_") || it.id.startsWith("magis_new_") || it.id.startsWith("magis_top_") }
                    .map { row ->
                        val item = row.shown.firstOrNull()
                        val preview = item?.let { it.backdrop?.ifBlank { null } ?: it.poster?.ifBlank { null } }
                        CategorySpec(id = row.id, title = row.title, previewUrl = preview)
                    }
            }
            if (specs.isNotEmpty() || _rows.value.isEmpty()) _rows.value = specs
            _loading.value = false
        }
    }

    /**
     * Where tapping [spec] goes: the Xuper plugin's "Ver más" over that row. Null only if the
     * plugin stopped being usable between painting the tile and the tap (the tiles go away then).
     */
    fun browseTarget(spec: CategorySpec): PluginMoreTarget.Browse? =
        pluginId.value?.let { PluginMoreTarget.Browse(it, spec.title, spec.id) }

    companion object {
        /**
         * The manifest id of the installed, usable plugin [XuperPrivilege.grants] (its install
         * address, never a manifest's self-declared id), or null when there's none.
         */
        fun xuperPluginId(plugins: List<InstalledPlugin>): String? =
            plugins.firstOrNull { XuperPrivilege.grants(it.record) }?.takeIf { it.isUsable }?.manifest?.id
    }
}
