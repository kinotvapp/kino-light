package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.db.SearchHistoryEntity
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private data class AnimeSort(val label: String, val value: String)

private val ANIME_SORTS = listOf(
    AnimeSort("Tendencias", "TRENDING_DESC"),
    AnimeSort("Populares", "POPULARITY_DESC"),
    AnimeSort("Mejor rating", "SCORE_DESC"),
    AnimeSort("Recientes", "START_DATE_DESC"),
)

class AnimeViewModel(private val api: AniListApi) : ViewModel() {
    private val _shows = MutableStateFlow<List<AnimeShow>>(emptyList())
    val shows: StateFlow<List<AnimeShow>> = _shows.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _sort = MutableStateFlow("TRENDING_DESC")
    val sort: StateFlow<String> = _sort.asStateFlow()
    private val _genre = MutableStateFlow<String?>(null)
    val genre: StateFlow<String?> = _genre.asStateFlow()
    private val _genres = MutableStateFlow<List<String>>(emptyList())
    val genres: StateFlow<List<String>> = _genres.asStateFlow()

    private var page = 1
    private var query: String? = null
    private var endReached = false

    init {
        reload()
        viewModelScope.launch {
            _genres.value = runCatching { api.genres() }.getOrDefault(emptyList())
        }
    }

    fun setSort(s: String) { if (s != _sort.value) { _sort.value = s; reload() } }
    fun setGenre(g: String?) { if (g != _genre.value) { _genre.value = g; reload() } }
    fun search(q: String) { query = q.trim().ifBlank { null }; reload() }
    fun loadMore() { if (!_loading.value && !endReached) load(false) }

    private fun reload() { page = 1; endReached = false; _shows.value = emptyList(); load(true) }

    private fun load(reset: Boolean) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val result = runCatching { api.browse(page, _sort.value, query, _genre.value) }.getOrDefault(emptyList())
            if (result.isEmpty()) {
                endReached = true
                if (page == 1) _error.value = "No se pudo cargar el anime. Revisa la conexión."
            } else page++
            _shows.value = if (reset) result else _shows.value + result
            _loading.value = false
        }
    }
}

@Composable
fun AnimeSection(onOpenAnime: (Long) -> Unit, contentPadding: PaddingValues) {
    val graph = rememberGraph()
    val vm: AnimeViewModel = viewModel(
        factory = viewModelFactory { initializer { AnimeViewModel(graph.aniListApi) } },
    )
    val shows by vm.shows.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val genre by vm.genre.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var recentSearches by remember { mutableStateOf(emptyList<String>()) }

    suspend fun refreshRecent() {
        recentSearches = graph.database.searchHistoryDao().recent("anime", 8)
            .map { it.query }
    }

    fun runSearch(q: String) {
        vm.search(q)
        if (q.isNotBlank()) {
            scope.launch {
                graph.database.searchHistoryDao().upsert(SearchHistoryEntity(q.trim(), "anime", System.currentTimeMillis()))
                refreshRecent()
            }
        }
    }

    LaunchedEffect(gridState, shows.size) {
        val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (shows.isNotEmpty() && last >= shows.size - 4) vm.loadMore()
    }

    LaunchedEffect(Unit) { refreshRecent() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Buscar anime…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (query.isBlank() && recentSearches.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recentSearches.forEach { q ->
                    AssistChip(
                        onClick = { query = q; runSearch(q) },
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
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ANIME_SORTS.forEach { s ->
                FilterChip(
                    selected = sort == s.value,
                    onClick = { vm.setSort(s.value) },
                    label = { Text(s.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = genre == null,
                onClick = { vm.setGenre(null) },
                label = { Text("Todos") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
            )
            genres.forEach { g ->
                FilterChip(
                    selected = genre == g,
                    onClick = { vm.setGenre(g) },
                    label = { Text(g) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                )
            }
        }

        if (error != null && shows.isEmpty()) {
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
            items(shows, key = { it.id }) { show ->
                AnimePoster(show, onClick = { onOpenAnime(show.id) })
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
private fun AnimePoster(show: AnimeShow, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
        ) {
            AsyncImage(model = show.posterUrl, contentDescription = show.title, modifier = Modifier.fillMaxSize())
            if (show.scorePct > 0) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.padding(end = 2.dp).size(12.dp))
                    Text("${show.scorePct / 10.0}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
        Text(show.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        if (show.year > 0) {
            Text(show.year.toString(), style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
        }
    }
}
