package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbCategory
import com.arkiv.player.ui.search.TitleCard
import com.arkiv.player.ui.search.toTitleCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RowBrowseViewModel(
    private val rowId: String,
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
) : ViewModel() {

    private val _items = MutableStateFlow<List<TitleCard>>(emptyList())
    val items: StateFlow<List<TitleCard>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canLoadMore = MutableStateFlow(true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()

    private val _hasError = MutableStateFlow(false)
    val hasError: StateFlow<Boolean> = _hasError.asStateFlow()

    private var page = 1
    private var loading = false

    /** Loads the next page. Idempotent while the previous one hasn't finished. */
    fun loadMore() {
        if (loading || !_canLoadMore.value) return
        loading = true
        _isLoading.value = true
        viewModelScope.launch {
            val source = sourceFor(rowId)
            if (source == null) {
                _hasError.value = true
                _canLoadMore.value = false
                _isLoading.value = false
                loading = false
                return@launch
            }
            val result: List<TitleCard> = when (source) {
                is RowSource.Curated ->
                    runCatching { tmdbApi.curated(source.type, source.category, page) }
                        .getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Discover ->
                    runCatching { tmdbApi.discover(source.type, source.genreId, page) }
                        .getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Anime ->
                    runCatching { aniListApi.browse(page, source.sort, null, source.genre) }
                        .getOrDefault(emptyList()).map { it.toTitleCard() }
            }
            // Dedup by the SAME key the LazyColumn/Row uses ("kind-tmdbId-anilistId"): TMDB's
            // paginated discover/curated repeats titles across pages, and a repeated key crashes the
            // list with "Key … was already used" (IllegalArgumentException). distinctBy keeps the
            // first occurrence, so order is stable. The end-of-pages heuristic below still reads the
            // RAW page size, not the deduped total.
            _items.value = (_items.value + result).distinctBy { "${it.kind}-${it.tmdbId}-${it.anilistId}" }
            // If fewer than 2 items arrived, the source ran out (heuristic: TMDB gives 20/page,
            // AniList gives ~50; any empty or near-empty result signals the end of pages).
            _canLoadMore.value = result.size >= 2
            if (result.isNotEmpty()) page++
            _isLoading.value = false
            loading = false
        }
    }

    /** Clears the state and relaunches the first load. Useful for the "Reintentar" button. */
    fun resetAndLoad() {
        _hasError.value = false
        _canLoadMore.value = true
        _items.value = emptyList()
        page = 1
        loading = false
        loadMore()
    }

    companion object {
        /** Decodes the rowId into the data source, without making any network call. */
        fun sourceFor(rowId: String): RowSource? = when (rowId) {
            "series_populares"   -> RowSource.Curated("tv", TmdbCategory.POPULAR)
            "series_top"         -> RowSource.Curated("tv", TmdbCategory.TOP_RATED)
            "anime"              -> RowSource.Anime("TRENDING_DESC")
            "anime_populares"    -> RowSource.Anime("POPULARITY_DESC")
            "anime_top"          -> RowSource.Anime("SCORE_DESC")
            else -> when {
                rowId.startsWith("g_movie_") ->
                    rowId.removePrefix("g_movie_").toIntOrNull()
                        ?.let { RowSource.Discover("movie", it) }
                rowId.startsWith("g_tv_") ->
                    rowId.removePrefix("g_tv_").toIntOrNull()
                        ?.let { RowSource.Discover("tv", it) }
                rowId.startsWith("g_anime_") -> {
                    // The slug was created with g.lowercase().replace(" ", "_").
                    // The name is rebuilt in title-case so AniList recognizes it.
                    val genre = rowId.removePrefix("g_anime_")
                        .replace("_", " ")
                        .split(" ")
                        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
                    RowSource.Anime(sort = "POPULARITY_DESC", genre = genre)
                }
                else -> null
            }
        }
    }
}
