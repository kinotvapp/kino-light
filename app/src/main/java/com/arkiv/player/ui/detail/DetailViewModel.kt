package com.arkiv.player.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.db.SkipMarkerEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(
    private val repo: ArkivRepository,
    /** Group key (`tv:46260`) or a raw identifier: `item:<id>` and loose identifiers resolve to a single member. */
    private val groupKey: String,
) : ViewModel() {

    /** Every acquisition of this series; feeds the source selector. */
    val sources: StateFlow<List<LibraryRow>> = repo.observeGroupMembers(groupKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The manually chosen source; if null, the first of [sources] is used (the most complete). */
    private val _selected = MutableStateFlow<String?>(null)

    /** Identifier of the item being shown. */
    val selectedId: StateFlow<String?> = combine(sources, _selected) { list, manual ->
        manual?.takeIf { id -> list.any { it.identifier == id } } ?: list.firstOrNull()?.identifier
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val detail: StateFlow<ItemDetail?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeItemDetail(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val skipMarker: StateFlow<SkipMarkerEntity?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeSkipMarker(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Marking chapters as seen needs to know WHICH item to mark, and that arrives with the
        // first value of selectedId (it comes from the DB, not immediate). filterNotNull().first()
        // skips a null id and avoids marking nothing if the group takes a moment to resolve.
        viewModelScope.launch {
            val id = selectedId.filterNotNull().first()
            // Opening the detail IS seeing the list: this is where the "new chapters" badge turns
            // off. See NewEpisodeCounter.
            repo.markChaptersSeen(id)
        }
    }

    fun selectSource(identifier: String) { _selected.value = identifier }

    fun saveSkipMarker(openingStartMs: Long?, openingEndMs: Long?, endingStartMs: Long?) {
        val id = selectedId.value ?: return
        viewModelScope.launch { repo.saveSkipMarker(id, openingStartMs, openingEndMs, endingStartMs) }
    }

    fun toggleWatched(episodeId: String, watched: Boolean) {
        viewModelScope.launch { repo.setWatched(episodeId, watched) }
    }

    /** Deletes ONLY the source being watched. If it was the group's last one, the card disappears. */
    fun removeFromLibrary(onDone: () -> Unit) {
        val id = selectedId.value ?: return onDone()
        viewModelScope.launch {
            repo.removeItem(id)
            onDone()
        }
    }

    fun rename(title: String) {
        val id = selectedId.value ?: return
        viewModelScope.launch { repo.renameItem(id, title) }
    }
}
