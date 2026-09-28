package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.LibraryRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    repo: ArkivRepository,
    private val settings: SettingsStore,
    /** Manual "recargar catálogo" pulses from the top bar (`AppGraph.homeReloads`). */
    reload: Flow<Unit>,
    /** Rows from installed plugins; see [PluginHomeRows]. */
    private val pluginHome: com.arkiv.player.data.plugin.PluginHomeRows,
    /** `AppGraph.pluginsChanged`: install/enable/disable/uninstall/update re-asks for plugin rows. */
    pluginsChanged: Flow<*>,
) : ViewModel() {

    val library: StateFlow<List<LibraryRow>> = repo.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The library ordered by what you last watched, for the "Mi biblioteca" grid.
     *
     * A separate subscription from [library] on purpose: raw [library] feeds the `init`'s
     * `onEach { ensureArtwork(rows) }` (one query per row on every emission) and the TV home's
     * hero, and shouldn't re-emit every time progress is saved.
     */
    val orderedLibrary: StateFlow<List<LibraryRow>> = repo.observeLibraryOrdered()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching: StateFlow<List<ContinueRow>> = repo.observeContinueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** itemId -> TMDB art (backdrops) to paint on the home. */
    val artwork: StateFlow<Map<String, ArtworkEntity>> = repo.observeArtwork()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Plugin rows (Xuper's among them), the discovery rows of both homes. Empty while loading and
     * when no plugin has the `home` capability; a failing plugin contributes nothing. Started by the
     * first collector, not in `init`: the library screen builds this ViewModel too and never draws
     * these rows.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val pluginLoad: StateFlow<com.arkiv.player.data.plugin.PluginHomeLoad> =
        merge(pluginsChanged.map { }, reload)
            .flatMapLatest { pluginHome.load() }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Lazily, com.arkiv.player.data.plugin.PluginHomeLoad(emptyList(), settled = false))

    val pluginRows: StateFlow<List<com.arkiv.player.data.plugin.PluginHomeRow>> =
        pluginLoad.map { it.rows }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Whether the current plugin Home pass is over: every plugin answered, failed or timed out. False
     * from the start (and again on a reload or plugin change) until then; drives Home's loading state
     * (see [homeShowsLoading]), so a pass that ends with nothing never leaves a spinner behind.
     */
    val pluginRowsSettled: StateFlow<Boolean> =
        pluginLoad.map { it.settled }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    init {
        // Every time the library changes, resolves the art of the items that don't have it yet.
        // ensureArtwork ignores the ones already resolved, so re-emissions are cheap.
        library
            .onEach { rows -> repo.ensureArtwork(rows) }
            .launchIn(viewModelScope)

        // One-time pass to repair art that ended up pointing at the wrong title before
        // pickTmdbMatch existed (the Dragon Balls with Dragon Ball Z's tmdbId). ensureArtwork
        // can't do it: it skips everything that already has a tmdbId. Only marked done if it
        // finished completely, so a start with no network retries it on the next one.
        if (!settings.artworkRematchDone.value) {
            viewModelScope.launch {
                val rows = library.first { it.isNotEmpty() }
                if (repo.repairArtworkMatches(rows)) settings.setArtworkRematchDone(true)
            }
        }
    }
}
