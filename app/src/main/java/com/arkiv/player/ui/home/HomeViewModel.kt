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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    repo: ArkivRepository,
    private val settings: SettingsStore,
    magisHome: MagisHomeCatalog,
    /** `AppGraph.hasInternet`: its false → true flips retry a home pass that left a root out. */
    online: Flow<Boolean>,
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

    private val resumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * The Magis rows for both homes; null while the first pass loads. See [MagisHomeCatalog].
     *
     * Started by the first collector, not in `init`: the library screen builds this ViewModel too
     * and never draws these rows. A pass that left a root out (a cold start before the network is
     * up) is asked again when connectivity comes back or the home resumes, see [magisHomeRows].
     */
    val magisRows: StateFlow<List<MagisHomeRow>?> =
        magisHomeRows(fetch = magisHome::load, retry = merge(online.reconnections(), resumed))
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

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

    /** The home is in front again: a chance to fill in a root the last pass left out. */
    fun onResume() {
        resumed.tryEmit(Unit)
    }
}
