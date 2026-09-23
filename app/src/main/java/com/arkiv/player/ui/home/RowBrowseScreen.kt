package com.arkiv.player.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.AppGraph
import com.arkiv.player.ui.gridColumns
import com.arkiv.player.ui.components.PosterCard
import com.arkiv.player.ui.isLandscapeTablet
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowBrowseScreen(
    rowId: String,
    title: String,
    onOpenSearchRoute: (String) -> Unit,
    onBack: () -> Unit,
    graph: AppGraph,
) {
    val vm: RowBrowseViewModel = viewModel(
        key = rowId,
        factory = viewModelFactory { initializer { RowBrowseViewModel(rowId, graph.tmdbApi, graph.aniListApi) } },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val canLoadMore by vm.canLoadMore.collectAsStateWithLifecycle()
    val hasError by vm.hasError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadMore() }

    val state = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= items.size - 6 && canLoadMore && !isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) vm.loadMore() }

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
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns(3, isLandscapeTablet())),
                state = state,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = { "${it.kind}-${it.tmdbId}-${it.anilistId}" }) { card ->
                    PosterCard(
                        title = card.title,
                        imageUrl = card.posterUrl,
                        onClick = { onOpenSearchRoute(searchShortcutRoute(card)) },
                    )
                }
                if (isLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = ArkivRed,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }

            if (items.isEmpty() && !isLoading && !canLoadMore) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No se pudo cargar el contenido",
                            color = Color.White,
                        )
                        TextButton(onClick = { vm.resetAndLoad() }) {
                            Text("Reintentar", color = ArkivRed)
                        }
                    }
                }
            }
        }
    }
}
