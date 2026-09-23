package com.arkiv.player.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.ui.components.PosterCard
import com.arkiv.player.ui.gridColumns
import com.arkiv.player.ui.isLandscapeTablet
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Every title of one Magis home row ([MagisHomeRow.all]), for "Ver todo" on phone and TV. Rebuilt
 * from the same cached catalog + classifier by the row's id, so no id is parsed back by hand and
 * no extra portal call is made. null = loading; empty = the row doesn't exist (anymore).
 */
class MagisRowBrowseViewModel(
    private val rowId: String,
    private val catalog: MagisHomeCatalog,
) : ViewModel() {
    private val _items = MutableStateFlow<List<CatalogItem>?>(null)
    val items: StateFlow<List<CatalogItem>?> = _items.asStateFlow()

    init { load() }

    fun load() {
        _items.value = null
        viewModelScope.launch {
            _items.value = catalog.rows().firstOrNull { it.id == rowId }?.all.orEmpty()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagisRowBrowseScreen(
    rowId: String,
    title: String,
    onPlay: (episodeId: String) -> Unit,
    onBack: () -> Unit,
) {
    val graph = rememberGraph()
    val vm: MagisRowBrowseViewModel = viewModel(
        key = rowId,
        factory = viewModelFactory { initializer { MagisRowBrowseViewModel(rowId, graph.magisHomeCatalog) } },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val open = rememberMagisOpener(onPlay)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ArkivBlack,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        containerColor = ArkivBlack,
    ) { padding ->
        val loaded = items
        when {
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
            loaded.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No se pudo cargar el contenido", color = Color.White)
                    TextButton(onClick = { vm.load() }) { Text("Reintentar", color = ArkivRed) }
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns(3, isLandscapeTablet())),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(loaded, key = { it.id }) { item ->
                    PosterCard(title = item.title, imageUrl = item.poster, onClick = { open(item) })
                }
            }
        }
    }
}
