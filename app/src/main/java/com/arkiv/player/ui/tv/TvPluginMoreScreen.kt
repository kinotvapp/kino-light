package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.toPlaySource
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.isSeries
import com.arkiv.player.ui.plugin.PluginMoreTarget
import com.arkiv.player.ui.plugin.PluginMoreViewModel
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/**
 * TV "Ver más" of a plugin row (or a plugin's search): a 4-column grid that asks for the next page
 * when focus reaches its last row. A movie plays; a series opens its chapters over the grid.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TvPluginMoreScreen(
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
    // Back from Configurar: ask again, with the new settings.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (vm.state.value.setupPluginId != null) vm.loadMore()
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback = remember(graph) { SearchPlayback(graph) }
    val navSound = rememberNavSound()
    var series by remember { mutableStateOf<PlaySource.Plugin?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val hasItems = state.items.isNotEmpty()
    LaunchedEffect(hasItems, state.error) {
        runCatching { if (hasItems) firstFocus.requestFocus() else if (state.error != null) actionFocus.requestFocus() }
    }

    fun done(result: PlaybackResult) {
        preparing = false
        when (result) {
            is PlaybackResult.Ready -> onPlayEpisode(result.episodeId)
            is PlaybackResult.Failed -> android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun open(result: GatewayResult) {
        if (preparing) return
        val source = result.toPlaySource() as? PlaySource.Plugin ?: return
        if (source.isSeries()) { series = source; return }
        preparing = true
        scope.launch { done(playback.playPlugin(result)) }
    }

    BackHandler(enabled = series == null) { onBack() }
    BackHandler(enabled = series != null) { series = null }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Text(target.title, style = MaterialTheme.typography.headlineMedium, color = ArkivTextSecondary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                // Fix round 1, finding 2: this must stay reachable whether or not the grid already
                // has items -- an error (e.g. auth_required mid-paging) is never hidden behind
                // `hasItems`. [tvMoreFooter] is the pure, unit-tested decision (`TvPluginMoreScreenTest`).
                val footer = tvMoreFooter(state)
                when {
                    hasItems -> CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollBringIntoView) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(state.items, key = { _, item -> item.extra["pluginItemId"] ?: item.ref }) { index, item ->
                                val art = item.extra["backdrop"].orEmpty().ifBlank { item.extra["poster"].orEmpty() }.ifBlank { null }
                                TvLandscapeCard(
                                    title = item.title,
                                    imageUrl = art,
                                    cardHeight = 110.dp,
                                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                    onFocus = {
                                        navSound()
                                        // Focus in the last row asks for the next page.
                                        if (index >= state.items.size - 4) vm.loadMore()
                                    },
                                    onClick = { open(item) },
                                )
                            }
                            // The grid's trailing row: an error and its action (mirrors the phone's
                            // footer item in PluginMore.kt), a quiet spinner while paging continues,
                            // or nothing once it's over.
                            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                    when (footer) {
                                        TvMoreFooter.ERROR -> TvMoreFooterError(
                                            message = state.error.orEmpty(),
                                            setupPluginId = state.setupPluginId,
                                            onRetry = vm::loadMore,
                                            onOpenPluginSettings = onOpenPluginSettings,
                                            focusRequester = actionFocus,
                                        )
                                        TvMoreFooter.LOADING -> CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                                        TvMoreFooter.NONE -> Unit
                                    }
                                }
                            }
                        }
                    }
                    footer == TvMoreFooter.ERROR -> TvMoreFooterError(
                        message = state.error.orEmpty(),
                        setupPluginId = state.setupPluginId,
                        onRetry = vm::loadMore,
                        onOpenPluginSettings = onOpenPluginSettings,
                        focusRequester = actionFocus,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    footer == TvMoreFooter.LOADING -> CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(24.dp).align(Alignment.Center))
                    else -> Text("No hay nada más aquí", color = ArkivTextSecondary, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        series?.let { open ->
            Box(Modifier.fillMaxSize().background(ArkivBlack)) {
                TvPluginChapters(
                    source = open,
                    posterUrl = open.result.extra["poster"].orEmpty(),
                    preparing = preparing,
                    onChoose = { save ->
                        series = null
                        preparing = true
                        scope.launch { done(save()) }
                    },
                )
            }
        }
    }
}

/** An error and its action ("Configurar" for a setup-required one, "Reintentar" otherwise), shown
 *  either full-screen (no items yet) or as the grid's trailing row (items already on screen). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvMoreFooterError(
    message: String,
    setupPluginId: String?,
    onRetry: () -> Unit,
    onOpenPluginSettings: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { if (setupPluginId != null) onOpenPluginSettings(setupPluginId) else onRetry() },
            colors = arkivTvButtonColors(),
            border = arkivTvButtonBorder(),
            modifier = Modifier.focusRequester(focusRequester),
        ) { Text(if (setupPluginId != null) "Configurar" else "Reintentar") }
    }
}

/** What the grid's trailing row shows, from the pager's state alone. */
internal enum class TvMoreFooter { ERROR, LOADING, NONE }

/**
 * Pure so it's testable in a plain JVM (`TvPluginMoreScreenTest`) -- Compose for TV has no UI test
 * infrastructure in this project. This is the exact decision fix round 1's finding 2 broke: an
 * error must win regardless of whether the grid already has items.
 */
internal fun tvMoreFooter(state: com.arkiv.player.ui.plugin.PluginMorePager.State): TvMoreFooter = when {
    state.error != null -> TvMoreFooter.ERROR
    !state.ended -> TvMoreFooter.LOADING
    else -> TvMoreFooter.NONE
}
