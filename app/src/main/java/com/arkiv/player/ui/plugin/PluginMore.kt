package com.arkiv.player.ui.plugin

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import com.arkiv.player.AppGraph
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPage
import com.arkiv.player.ui.components.PosterCard
import com.arkiv.player.ui.gridColumns
import com.arkiv.player.ui.isLandscapeTablet
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a "Ver más" screen pages through: a Home row's `browse(ref)`, or a plugin's search
 * continued from the cursor its first page gave. Travels in the route (see [route] / [fromRoute]).
 */
sealed interface PluginMoreTarget {
    val pluginId: String
    val title: String

    data class Browse(override val pluginId: String, override val title: String, val ref: String) : PluginMoreTarget
    data class Search(override val pluginId: String, override val title: String, val queryJson: String, val cursor: String) : PluginMoreTarget

    companion object {
        /** The nav pattern both roots register (phone ArkivRoot and TV ArkivTvRoot). */
        const val PATTERN = "plugin_more/{pluginId}?title={title}&ref={ref}&query={query}&cursor={cursor}"

        fun route(t: PluginMoreTarget): String {
            val base = "plugin_more/${Uri.encode(t.pluginId)}?title=${Uri.encode(t.title)}"
            return when (t) {
                is Browse -> "$base&ref=${Uri.encode(t.ref)}"
                is Search -> "$base&query=${Uri.encode(t.queryJson)}&cursor=${Uri.encode(t.cursor)}"
            }
        }

        fun fromRoute(pluginId: String, title: String, ref: String?, query: String?, cursor: String?): PluginMoreTarget? = when {
            pluginId.isEmpty() -> null
            ref != null -> Browse(pluginId, title, ref)
            query != null && cursor != null -> Search(pluginId, title, query, cursor)
            else -> null
        }
    }
}

/**
 * Runs a [PluginMorePager] for one target. The plugin call and the parsing of its answer (up to
 * 2 million characters) run on [io], never on Main.
 */
class PluginMoreViewModel(
    target: PluginMoreTarget,
    graph: AppGraph,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val pager = PluginMorePager(load = { cursor -> withContext(io) { page(graph, target, cursor) } })
    val state = pager.state

    init {
        loadMore()
    }

    fun loadMore() {
        viewModelScope.launch { pager.loadMore() }
    }

    private suspend fun page(graph: AppGraph, target: PluginMoreTarget, cursor: String?): GatewayPage {
        // A SINGLE plugin's own source, never `graph.contentSource` (CompositeSource inherits
        // ContentSource.browse's throwing default; it never forwards to the plugin that has it).
        val source = graph.pluginSource(target.pluginId) ?: throw GatewayException("El plugin ya no está disponible")
        return when (target) {
            is PluginMoreTarget.Browse -> source.browse(target.ref, cursor)
            // The first search page is already on screen: "Ver más" starts at its cursor.
            is PluginMoreTarget.Search -> source.searchPage(target.queryJson, cursor ?: target.cursor)
        }
    }
}

/** "Ver más" on the phone: a grid that loads the next page when its end comes into view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginMoreScreen(
    target: PluginMoreTarget,
    onPlayEpisode: (episodeId: String) -> Unit,
    onOpenPluginSettings: (pluginId: String) -> Unit,
    onBack: () -> Unit,
) {
    val graph = rememberGraph()
    val vm: PluginMoreViewModel = viewModel(
        key = PluginMoreTarget.route(target),
        factory = viewModelFactory { initializer { PluginMoreViewModel(target, graph) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val open = rememberPluginOpener(onPlay = onPlayEpisode)
    // Back from Configurar: ask again, with the new settings.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (vm.state.value.setupPluginId != null) vm.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(target.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ArkivBlack, titleContentColor = Color.White, navigationIconContentColor = Color.White,
                ),
            )
        },
        containerColor = ArkivBlack,
    ) { padding ->
        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                when {
                    state.error != null -> PluginMoreError(state, onRetry = vm::loadMore, onOpenPluginSettings = onOpenPluginSettings)
                    state.loading || !state.ended -> CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    else -> Text("No hay nada más aquí", color = ArkivTextSecondary)
                }
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns(3, isLandscapeTablet())),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.items, key = { it.extra["pluginItemId"] ?: it.ref }) { item ->
                PosterCard(title = item.title, imageUrl = item.extra["poster"]?.ifBlank { null }, onClick = { open(item) })
            }
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.error != null -> PluginMoreError(state, onRetry = vm::loadMore, onOpenPluginSettings = onOpenPluginSettings)
                        !state.ended -> {
                            // Reaching the end of the grid asks for the next page.
                            androidx.compose.runtime.LaunchedEffect(state.items.size) { vm.loadMore() }
                            CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginMoreError(state: PluginMorePager.State, onRetry: () -> Unit, onOpenPluginSettings: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state.error.orEmpty(), color = Color.White)
        val setup = state.setupPluginId
        if (setup != null) {
            TextButton(onClick = { onOpenPluginSettings(setup) }) { Text("Configurar", color = ArkivRed) }
        } else {
            TextButton(onClick = onRetry) { Text("Reintentar", color = ArkivRed) }
        }
    }
}
