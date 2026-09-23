package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.ui.gridColumns
import com.arkiv.player.ui.isLandscapeTablet
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbCategory
import com.arkiv.player.data.catalog.TmdbGenre
import com.arkiv.player.data.catalog.TmdbItem
import com.arkiv.player.data.db.SearchHistoryEntity
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Grid card: a TMDB item. */
data class GridCard(val item: TmdbItem)

class CineCatalogViewModel(
    private val api: TmdbApi,
    resetSignal: kotlinx.coroutines.flow.Flow<Unit>,
) : ViewModel() {
    private val _items = MutableStateFlow<List<GridCard>>(emptyList())
    val items: StateFlow<List<GridCard>> = _items.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _type = MutableStateFlow("tv")
    val type: StateFlow<String> = _type.asStateFlow()
    private val _category = MutableStateFlow(TmdbCategory.POPULAR)
    val category: StateFlow<TmdbCategory> = _category.asStateFlow()
    private val _genreId = MutableStateFlow<Int?>(null)
    val genreId: StateFlow<Int?> = _genreId.asStateFlow()
    private val _genres = MutableStateFlow<List<TmdbGenre>>(emptyList())
    val genres: StateFlow<List<TmdbGenre>> = _genres.asStateFlow()

    private var page = 1
    private var query: String? = null
    private var endReached = false

    init {
        reload()
        loadGenres()
        // The VM survives the tab switch (its viewModelScope stays alive even when the screen
        // isn't composed), so the signal is listened to here and not in the composable.
        viewModelScope.launch { resetSignal.collect { resetSearch() } }
    }

    fun setType(t: String) { if (t != _type.value) { _type.value = t; _genreId.value = null; loadGenres(); reload() } }
    // Choosing a category clears the genre (they're a mutually exclusive selection: genre takes priority in load()).
    fun setCategory(c: TmdbCategory) { _category.value = c; _genreId.value = null; reload() }
    fun setGenre(id: Int?) { _genreId.value = id; reload() }
    fun search(q: String) { query = q.trim().ifBlank { null }; reload() }

    private fun loadGenres() {
        viewModelScope.launch { _genres.value = runCatching { api.genres(_type.value) }.getOrDefault(emptyList()) }
    }

    /** Returns to browsing if there was an active search. If already browsing, doesn't reload. */
    fun resetSearch() { if (query != null) { query = null; reload() } }
    fun loadMore() { if (!_loading.value && !endReached) load(false) }

    private fun reload() { page = 1; endReached = false; _items.value = emptyList(); load(true) }

    private fun load(reset: Boolean) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val q = query
            val fetchedPage = page
            val result = runCatching {
                when {
                    q != null -> api.search(_type.value, q, fetchedPage)
                    _genreId.value != null -> api.discover(_type.value, _genreId.value!!, fetchedPage)
                    else -> api.curated(_type.value, _category.value, fetchedPage)
                }
            }.getOrDefault(emptyList())
            if (result.isEmpty()) {
                endReached = true
                if (fetchedPage == 1) _error.value = "Sin resultados. Prueba otra búsqueda."
            } else page++
            val tmdbCards = result.map { GridCard(it) }
            _items.value = if (reset) tmdbCards else _items.value + tmdbCards
            _loading.value = false
        }
    }
}

@Composable
fun CineCatalogScreen(
    onOpen: (TmdbItem) -> Unit,
    onOpenAnime: (Long) -> Unit,
    contentPadding: PaddingValues,
) {
    val graph = rememberGraph()
    val vm: CineCatalogViewModel = viewModel(
        factory = viewModelFactory {
            initializer { CineCatalogViewModel(graph.tmdbApi, graph.catalogResetSignal) }
        },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val genreId by vm.genreId.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    var queryText by remember { mutableStateOf("") }
    var animeMode by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var recentSearches by remember { mutableStateOf(emptyList<String>()) }

    suspend fun refreshRecent() {
        recentSearches = graph.database.searchHistoryDao().recent(type, 8)
            .map { it.query }
    }

    fun runSearch(q: String) {
        vm.search(q)
        if (q.isNotBlank()) {
            scope.launch {
                graph.database.searchHistoryDao().upsert(SearchHistoryEntity(q.trim(), type, System.currentTimeMillis()))
                refreshRecent()
            }
        }
    }

    LaunchedEffect(gridState, items.size) {
        val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (items.isNotEmpty() && last >= items.size - 4) vm.loadMore()
    }

    LaunchedEffect(type) { refreshRecent() }

    // If the search resets while already in the catalog (re-tapping the tab), clear the field so
    // it doesn't show old text over browse results.
    LaunchedEffect(Unit) { graph.catalogResetSignal.collect { queryText = "" } }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val chip: @Composable (String, String) -> Unit = { k, label ->
                FilterChip(
                    selected = !animeMode && type == k,
                    onClick = { animeMode = false; vm.setType(k) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                )
            }
            chip("movie", "Películas")
            chip("tv", "Series y anime")
            FilterChip(
                selected = animeMode,
                onClick = { animeMode = true },
                label = { Text("Anime") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
            )
        }

        if (animeMode) {
            AnimeSection(onOpenAnime = onOpenAnime, contentPadding = contentPadding)
            return@Column
        }

        OutlinedTextField(
            value = queryText,
            onValueChange = { queryText = it },
            placeholder = { Text(if (type == "movie") "Buscar películas…" else "Buscar series o anime…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch(queryText) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (queryText.isBlank() && recentSearches.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recentSearches.forEach { q ->
                    AssistChip(
                        onClick = { queryText = q; runSearch(q) },
                        label = { Text(q) },
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        graph.database.searchHistoryDao().clear()
                        recentSearches = emptyList()
                    }
                }) { Text("Limpiar") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val categoryChip: @Composable (TmdbCategory, String) -> Unit = { c, label ->
                FilterChip(
                    selected = category == c,
                    onClick = { vm.setCategory(c) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                )
            }
            categoryChip(TmdbCategory.TRENDING, "Tendencias")
            categoryChip(TmdbCategory.POPULAR, "Populares")
            categoryChip(TmdbCategory.TOP_RATED, "Top rated")
            categoryChip(TmdbCategory.NOW_PLAYING, "En cartelera")
            categoryChip(TmdbCategory.UPCOMING, "Próximamente")
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = genreId == null,
                onClick = { vm.setGenre(null) },
                label = { Text("Todos") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
            )
            genres.forEach { g ->
                FilterChip(
                    selected = genreId == g.id,
                    onClick = { vm.setGenre(g.id) },
                    label = { Text(g.name) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                )
            }
        }

        if (error != null && items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = ArkivTextSecondary, modifier = Modifier.padding(32.dp))
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns(3, isLandscapeTablet())),
            state = gridState,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { "${it.item.type}${it.item.id}" }) { card ->
                CinePoster(card, onClick = { onOpen(card.item) })
            }
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ArkivRed)
                    }
                }
            }
        }
    }
}

@Composable
private fun CinePoster(card: GridCard, onClick: () -> Unit) {
    val item = card.item
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
        ) {
            AsyncImage(model = item.posterUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize())
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (item.year.isNotBlank()) {
            Text(item.year, style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
        }
    }
}
