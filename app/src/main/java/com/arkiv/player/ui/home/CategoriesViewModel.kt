package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One category tile: a Magis home row's id (also its "Ver todo" browse key), its label, and a
 *  preview image taken from the row's first title. */
data class CategorySpec(val id: String, val title: String, val previewUrl: String?)

/**
 * The Categories screen, built from the REAL Magis catalog -- the SAME source as the Home (see
 * [MagisHomeCatalog]/[MagisHomeClassifier]). It shows one tile per genre/featured row the classifier
 * actually produced (a genre row only exists when the catalog has ≥ [MagisHomeClassifier.MIN_GENRE_SIZE]
 * titles in it), so only categories we truly have are painted -- instead of the old behavior that
 * listed every TMDB/AniList genre whether or not the catalog had it.
 *
 * Tapping a tile reuses the Magis "Ver todo" browse ([MagisRowBrowseScreen]) keyed by the row id,
 * so it lands on that category's real titles ([MagisHomeRow.all]).
 */
class CategoriesViewModel(
    private val magisHome: MagisHomeCatalog,
) : ViewModel() {

    private val _rows = MutableStateFlow<List<CategorySpec>>(emptyList())
    val rows: StateFlow<List<CategorySpec>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // TV screen's scroll position — survives navigating to a sub-screen and back.
    var tvScrollIndex: Int = 0
    var tvScrollOffset: Int = 0

    init {
        viewModelScope.launch {
            val rows = runCatching { magisHome.rows() }.getOrDefault(emptyList())
            _rows.value = rows
                // Genre rows (magis_g_*) plus the featured "Estrenos"/"mejor valoradas" rows.
                .filter { it.id.startsWith("magis_g_") || it.id.startsWith("magis_new_") || it.id.startsWith("magis_top_") }
                .map { row ->
                    val item = row.shown.firstOrNull()
                    val preview = item?.let { it.backdrop?.ifBlank { null } ?: it.poster?.ifBlank { null } }
                    CategorySpec(id = row.id, title = row.title, previewUrl = preview)
                }
            _loading.value = false
        }
    }
}
