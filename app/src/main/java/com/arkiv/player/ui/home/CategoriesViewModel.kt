package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.ui.search.TitleCard
import com.arkiv.player.ui.search.toTitleCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** AniList genres (in English) → Spanish. */
val ANIME_GENRE_LABELS = mapOf(
    "Action" to "Acción",
    "Adventure" to "Aventura",
    "Comedy" to "Comedia",
    "Drama" to "Drama",
    "Fantasy" to "Fantasía",
    "Horror" to "Terror",
    "Mecha" to "Mecha",
    "Music" to "Música",
    "Mystery" to "Misterio",
    "Psychological" to "Psicológico",
    "Romance" to "Romance",
    "Sci-Fi" to "Ciencia Ficción",
    "Slice of Life" to "Vida Cotidiana",
    "Sports" to "Deportes",
    "Supernatural" to "Sobrenatural",
    "Thriller" to "Suspenso",
    "Historical" to "Histórico",
    "Military" to "Militar",
    "School" to "Escolar",
    "Space" to "Espacio",
    "Harem" to "Harem",
    "Ecchi" to "Ecchi",
    "Kids" to "Para niños",
    "Mahou Shoujo" to "Mahou Shoujo",
    "Isekai" to "Isekai",
)

class CategoriesViewModel(
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
) : ViewModel() {

    private val _rows = MutableStateFlow(buildRowSpecs(emptyList(), emptyList(), emptyList()))
    val rows: StateFlow<List<HomeRowSpec>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ── Previews (one image per category for the phone's grid) ────────────────────────────────

    /** rowId → URL of that category's first poster (null = not loaded yet). */
    private val _previews = MutableStateFlow<Map<String, String?>>(emptyMap())
    val previews: StateFlow<Map<String, String?>> = _previews.asStateFlow()

    private val fetchedPreviews = mutableSetOf<String>()
    // Global counter: each category picks a different item from the page (avoids repeating the same cover).
    private var previewSlot = 0

    // ── Full rows (for the TV layout with hero + card rows) ──────────────────────

    /** rowId → list of already-loaded cards. */
    private val _rowItems = MutableStateFlow<Map<String, List<TitleCard>>>(emptyMap())
    val rowItems: StateFlow<Map<String, List<TitleCard>>> = _rowItems.asStateFlow()

    /** rowIds of the rows that already finished loading (with or without results). */
    private val _rowsLoaded = MutableStateFlow<Set<String>>(emptySet())
    val rowsLoaded: StateFlow<Set<String>> = _rowsLoaded.asStateFlow()

    private val rowLoadGuard = LoadGuard()
    private val seenCards = mutableSetOf<String>()

    // TV screen's scroll position — survives navigating to a sub-screen and back.
    var tvScrollIndex: Int = 0
    var tvScrollOffset: Int = 0

    init {
        viewModelScope.launch {
            val movie = runCatching { tmdbApi.genres("movie") }.getOrDefault(emptyList())
            val tv = runCatching { tmdbApi.genres("tv") }.getOrDefault(emptyList())
            val anime = runCatching { aniListApi.genres() }.getOrDefault(emptyList())
            _rows.value = buildRowSpecs(movie, tv, anime).map { spec ->
                // Translate anime genres that come in English from AniList.
                if (spec.id.startsWith("g_anime_")) {
                    val parts = spec.title.split(" · ")
                    val genreEs = ANIME_GENRE_LABELS[parts.first()] ?: parts.first()
                    spec.copy(title = if (parts.size > 1) "$genreEs · ${parts.last()}" else genreEs)
                } else {
                    spec
                }
            }
            _loading.value = false
        }
    }

    /** Loads a representative image for the category (for the phone's grid). Idempotent. */
    fun fetchPreview(rowId: String) {
        if (!fetchedPreviews.add(rowId)) return
        val source = RowBrowseViewModel.sourceFor(rowId) ?: return
        val slot = previewSlot++
        viewModelScope.launch {
            fun <T> List<T>.atSlot() = getOrNull(slot % size.coerceAtLeast(1))
            val url: String? = when (source) {
                is RowSource.Curated -> {
                    val items = runCatching { tmdbApi.curated(source.type, source.category, 1) }.getOrNull()
                    items?.atSlot()?.let {
                        if (source.type == "movie") it.backdropUrl.ifBlank { it.posterUrl }
                        else it.posterUrl
                    }
                }
                is RowSource.Discover -> {
                    val items = runCatching { tmdbApi.discover(source.type, source.genreId, 1) }.getOrNull()
                    items?.atSlot()?.let {
                        if (source.type == "movie") it.backdropUrl.ifBlank { it.posterUrl }
                        else it.posterUrl
                    }
                }
                is RowSource.Anime -> {
                    val items = runCatching { aniListApi.browse(1, source.sort, null, source.genre) }.getOrNull()
                    items?.atSlot()?.let { anime ->
                        anime.bannerUrl.ifBlank { null } ?: anime.posterUrl.ifBlank { null }
                    }
                }
            }
            _previews.update { it + (rowId to url) }
        }
    }

    /** Loads a row's cards (for the TV layout). Idempotent (LoadGuard). */
    fun loadRow(id: String) {
        val spec = _rows.value.firstOrNull { it.id == id } ?: return
        if (!rowLoadGuard.shouldLoad(id)) return
        viewModelScope.launch {
            val cards: List<TitleCard> = when (val s = spec.source) {
                is RowSource.Curated ->
                    runCatching { tmdbApi.curated(s.type, s.category, 1) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Discover ->
                    runCatching { tmdbApi.discover(s.type, s.genreId, 1) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Anime ->
                    runCatching { aniListApi.browse(1, s.sort, null, s.genre) }.getOrDefault(emptyList()).map { it.toTitleCard() }
            }
            val fresh = dedupAgainst(seenCards, cards)
            seenCards += fresh.map { cardKey(it) }
            _rowItems.value = _rowItems.value + (id to fresh)
            _rowsLoaded.value = _rowsLoaded.value + id
        }
    }
}
