package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.LibraryRow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
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
    /** `AppGraph.hasInternet`: coming back online re-asks (never forced), see [HomeFreshness.backOnline]. */
    online: Flow<Boolean> = emptyFlow(),
    /** Runs before that back-online re-ask (the app: drop the catalog's remembered partial pass). */
    private val onBackOnline: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
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
    /** Non-forced re-asks: resume / visible-Home timer ([refreshIfStale]) and back online. */
    private val refreshes = MutableSharedFlow<Boolean>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** When the last plugin pass settled; null while one is in flight (see [HomeFreshness.shouldReloadOnResume]). */
    @Volatile private var lastSettledAt: Long? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val pluginLoad: StateFlow<com.arkiv.player.data.plugin.PluginHomeLoad> =
        // Only "Recargar" (already debounced by AppGraph) forces; everything else goes through the TTLs.
        merge(pluginsChanged.map { false }, reload.map { true }, refreshes)
            .flatMapLatest { force -> lastSettledAt = null; pluginHome.load(force) }
            .onEach { if (it.settled) lastSettledAt = clock() }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Lazily, com.arkiv.player.data.plugin.PluginHomeLoad(emptyList(), settled = false))

    val pluginRows: StateFlow<List<com.arkiv.player.data.plugin.PluginHomeRow>> =
        pluginLoad.map { it.rows }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Whether the current plugin Home pass is over: every plugin answered, failed or timed out. False
     * from the start (and again on a reload or plugin change) until then; drives Home's loading state
     * (see [homePluginRowsLoading]), so a pass that ends with nothing never leaves skeleton rows behind.
     */
    val pluginRowsSettled: StateFlow<Boolean> =
        pluginLoad.map { it.settled }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    /**
     * Called on resume and periodically while Home is visible ([HomeFreshnessEffect]): re-asks for
     * the plugin rows, never forced, once the last settled pass is older than
     * [HomeFreshness.RESUME_STALE_AFTER_MS]. A TV left on Home for days refreshes by itself this way;
     * the portal is still only reached when the catalog's own TTL says so.
     */
    fun refreshIfStale() {
        if (HomeFreshness.shouldReloadOnResume(lastSettledAt, clock())) {
            lastSettledAt = null
            refreshes.tryEmit(false)
        }
    }

    init {
        HomeFreshness.backOnline(online)
            .onEach {
                onBackOnline()
                refreshes.tryEmit(false)
            }
            .launchIn(viewModelScope)

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
