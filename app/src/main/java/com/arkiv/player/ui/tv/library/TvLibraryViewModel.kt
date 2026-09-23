package com.arkiv.player.ui.tv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.library.WatchedGroup
import com.arkiv.player.data.library.LibraryWatched
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State for the TV's "My library" screen.
 *
 * Deliberately does NOT reuse `HomeViewModel`: that one drags along the whole discovery engine
 * (requests genres from TMDB and AniList in its `init` and caches ~40 rows of titles). Opening
 * the library would create a second full instance of that for a screen that shows no discovery.
 *
 * It also doesn't expose `artwork`: the grid uses covers (`LibraryRow.thumbnailUrl`), not the
 * landscape backdrops the home needs for the hero. The artwork still gets resolved either way —
 * `observeLibraryGroups` reads it from the database to group, and `ensureArtwork` already runs
 * from the home, which is the TV's startup destination.
 */
class TvLibraryViewModel(private val repo: ArkivRepository) : ViewModel() {

    val groups: StateFlow<List<LibraryGroup>> = repo.observeLibraryGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val watched: StateFlow<List<WatchedGroup>> =
        combine(groups, repo.observeWatchedItems()) { groups, watched ->
            LibraryWatched.cross(groups, watched)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Removes ALL of the group's members from the library (soft-delete, see
     * `ArkivRepository.removeItem`), not just `primary`.
     *
     * The grid draws groups (`LibraryGroup`), but `removeItem` operates row by row. If a series
     * is saved from two sources (e.g. the 4 Narutos grouped under `tv:46260`), removing only
     * `primary` leaves the worse copy alive and the card stays on screen: the confirmation text
     * promises "it's removed on all your devices" and with a single member it doesn't deliver.
     *
     * No test: it only iterates `repo.removeItem`, and this repo runs on Room, which in this
     * project isn't tested without Robolectric (there isn't any -- see the project's
     * constraints). A test for this method would either be against real Room (out of scope here)
     * or an artificial wrapper that would only test the wrapper, not this code.
     */
    fun removeGroup(group: LibraryGroup) {
        viewModelScope.launch {
            group.members.forEach { repo.removeItem(it.identifier) }
        }
    }

    /** Movie <-> series by hand, when automatic detection gets it wrong. Null = automatic. */
    fun setCategory(itemId: String, isMovie: Boolean?) {
        viewModelScope.launch { repo.setCategory(itemId, isMovie) }
    }
}
