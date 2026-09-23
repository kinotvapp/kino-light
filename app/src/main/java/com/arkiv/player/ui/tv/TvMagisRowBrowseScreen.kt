package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.ui.home.MagisRowBrowseViewModel
import com.arkiv.player.ui.home.homeMeta
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * TV "Ver todo" of a Magis home row: every title of the row ([MagisRowBrowseViewModel]) in a
 * 4-column grid, with the focused title's art as background -- same layout as
 * [TvRowBrowseScreen], minus paging (the whole list is already in memory).
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TvMagisRowBrowseScreen(
    rowId: String,
    title: String,
    onOpenMagis: (CatalogItem) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val graph = rememberGraph()
    val vm: MagisRowBrowseViewModel = viewModel(
        key = rowId,
        factory = viewModelFactory { initializer { MagisRowBrowseViewModel(rowId, graph.magisHomeCatalog) } },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val firstItemFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(items) {
        val loaded = items ?: return@LaunchedEffect
        runCatching { (if (loaded.isEmpty()) retryFocus else firstItemFocus).requestFocus() }
    }
    val navSound = rememberNavSound()
    var featured by remember { mutableStateOf<Featured?>(null) }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        Crossfade(targetState = featured?.imageUrl, animationSpec = tween(450), label = "bg") { url ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(0.62f).fillMaxHeight().align(Alignment.TopEnd),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(ArkivBlack, ArkivBlack, ArkivBlack.copy(alpha = 0.15f), Color.Transparent)),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.4f), ArkivBlack)),
                    ),
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp, vertical = 28.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = ArkivTextSecondary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                featured?.let { f ->
                    Text(
                        f.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.55f),
                    )
                    Text(f.subtitle, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
                }
            }

            val loaded = items
            Box(Modifier.fillMaxWidth().weight(2f)) {
                when {
                    loaded == null -> CircularProgressIndicator(
                        color = ArkivRed, strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp).align(Alignment.Center),
                    )
                    loaded.isEmpty() -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Text(
                            "No se pudo cargar el contenido",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.load() },
                            colors = arkivTvButtonColors(),
                            border = arkivTvButtonBorder(),
                            modifier = Modifier.focusRequester(retryFocus),
                        ) { Text("Reintentar") }
                    }
                    else -> CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollBringIntoView) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(loaded, key = { _, item -> item.id }) { index, item ->
                                val art = item.backdrop ?: item.poster
                                TvLandscapeCard(
                                    title = item.title,
                                    imageUrl = art,
                                    cardHeight = 110.dp,
                                    modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
                                    onFocus = {
                                        navSound()
                                        featured = Featured(title = item.title, subtitle = item.homeMeta(), imageUrl = art)
                                    },
                                    onClick = { onOpenMagis(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
