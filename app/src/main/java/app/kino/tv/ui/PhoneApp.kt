package app.kino.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kino.tv.data.CatalogRow
import app.kino.tv.data.KinoPluginUpdates
import app.kino.tv.ui.about.AboutScreen
import app.kino.tv.ui.brand.KinoWordmark
import app.kino.tv.ui.home.HomeScreen
import app.kino.tv.ui.phone.AssistantScreen
import app.kino.tv.ui.phone.CategoriesScreen
import app.kino.tv.ui.phone.DownloadsScreen
import app.kino.tv.ui.phone.LibraryScreen
import app.kino.tv.ui.phone.LiveScreen
import app.kino.tv.ui.phone.ExtensionsScreen
import app.kino.tv.ui.phone.SearchScreen
import app.kino.tv.ui.phone.SettingsScreen
import app.kino.tv.ui.phone.SourcePickerScreen
import app.kino.tv.ui.player.PlayerScreen
import app.kino.tv.ui.plugins.CountBadgedIcon
import app.kino.tv.ui.plugins.PluginUpdatesBellIcon
import app.kino.tv.ui.plugins.PluginUpdatesDialog
import app.kino.tv.ui.remote.RemoteMiniPlayer
import app.kino.tv.ui.theme.KinoBlack
import app.kino.tv.ui.theme.KinoRed
import app.kino.tv.ui.title.TitleScreen
import kotlinx.coroutines.launch

private data class Tab(val route: Route, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Route.Home, "Inicio", Icons.Default.Home),
    Tab(Route.Categories, "Categorías", Icons.Default.GridView),
    Tab(Route.Library, "Biblioteca", Icons.Default.VideoLibrary),
    Tab(Route.Downloads, "Descargas", Icons.Default.Download),
    Tab(Route.Live, "En vivo", Icons.Default.LiveTv),
    Tab(Route.Extensions, "Plugins", Icons.Default.Extension),
    Tab(Route.Settings, "Ajustes", Icons.Default.Settings),
    Tab(Route.About, "Acerca de", Icons.Default.Info),
)

/**
 * The phone's shell: a drawer with the sections (a rail on a landscape tablet), a top bar with the
 * logo on section screens (on Inicio also the plugin-updates bell), and full-screen pages for a film, the player, search and the assistant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneApp(rows: List<CatalogRow>, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val route = nav.current
    val isTab = TABS.any { it.route == route }
    val isWide = isLandscapeTablet()
    var showPluginUpdates by rememberSaveable { mutableStateOf(false) }
    if (showPluginUpdates) {
        PluginUpdatesDialog(
            waiting = KinoPluginUpdates.waiting,
            updated = KinoPluginUpdates.updated,
            onReview = { fullAppOnly(context) },
            onDismiss = { showPluginUpdates = false },
        )
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = !drawerState.isOpen && nav.stack.size > 1 && route != Route.SourcePicker) { nav.pop() }

    when (route) {
        is Route.Player -> { PlayerScreen(route.film, isTv = false, onBack = { nav.pop() }); return }
        is Route.Title -> {
            TitleScreen(route.film, onBack = { nav.pop() }, onPlay = { nav.push(Route.Player(route.film)) })
            return
        }
        Route.Search -> { SearchScreen(rows, onBack = { nav.pop() }, onOpenFilm = { nav.push(Route.Title(it)) }); return }
        Route.Assistant -> { AssistantScreen(onBack = { nav.pop() }); return }
        Route.SourcePicker -> { SourcePickerScreen(onFinish = { nav.finishPicker(); markPickerDone(context) }); return }
        else -> Unit
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isTab && !isWide,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = KinoBlack) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(24.dp))
                    KinoWordmark(height = 30.dp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                    Spacer(Modifier.height(8.dp))
                    TABS.forEach { tab ->
                        NavigationDrawerItem(
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            badge = if (tab.route == Route.Extensions && KinoPluginUpdates.count > 0) {
                                { Badge(containerColor = KinoRed, contentColor = Color.White) { Text("${KinoPluginUpdates.count}") } }
                            } else {
                                null
                            },
                            selected = tab.route == route,
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = KinoRed.copy(alpha = 0.15f),
                                selectedIconColor = KinoRed,
                                selectedTextColor = KinoRed,
                                unselectedIconColor = Color.White,
                                unselectedTextColor = Color.White,
                            ),
                            onClick = {
                                scope.launch { drawerState.close() }
                                nav.goTab(tab.route)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            }
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (isWide && isTab) {
                NavigationRail(containerColor = KinoBlack) {
                    TABS.forEach { tab ->
                        NavigationRailItem(
                            selected = tab.route == route,
                            onClick = { nav.goTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = KinoBlack,
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            if (!isWide) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    // The plugin updates waiting for approval show on the menu too, where Plugins is.
                                    CountBadgedIcon(Icons.Default.Menu, "Menú", KinoPluginUpdates.count)
                                }
                            }
                        },
                        title = {
                            // Off Inicio the logo is the quick way home.
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable(enabled = route != Route.Home, onClickLabel = "Ir al inicio") { nav.goTab(Route.Home) },
                                contentAlignment = Alignment.CenterStart,
                            ) { KinoWordmark(height = 24.dp) }
                        },
                        actions = {
                            if (route == Route.Home) {
                                IconButton(onClick = { showPluginUpdates = true }) { PluginUpdatesBellIcon(KinoPluginUpdates.count) }
                                IconButton(onClick = { fullAppOnly(context) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Recargar catálogo", tint = Color.White)
                                }
                                IconButton(onClick = { nav.push(Route.Assistant) }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Kinobot, el asistente de IA", tint = Color.White)
                                }
                                IconButton(onClick = { nav.push(Route.Search) }) {
                                    Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color.White)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = KinoBlack),
                    )
                },
                // The linked TV's remote, floating over the section screens while it plays something.
                bottomBar = { RemoteMiniPlayer(rows) },
            ) { padding ->
                when (route) {
                    Route.Home -> HomeScreen(
                        rows = rows,
                        contentPadding = padding,
                        onOpenFilm = { nav.push(Route.Title(it)) },
                        onPlayFilm = { nav.push(Route.Player(it)) },
                    )
                    Route.Categories -> CategoriesScreen(rows, padding, onOpenFilm = { nav.push(Route.Title(it)) })
                    Route.Library -> LibraryScreen(rows, padding, onOpenFilm = { nav.push(Route.Title(it)) }, onPlayFilm = { nav.push(Route.Player(it)) })
                    Route.Downloads -> DownloadsScreen(rows, padding)
                    Route.Live -> LiveScreen(rows, padding, onPlay = { nav.push(Route.Player(it)) })
                    Route.Extensions -> ExtensionsScreen(padding)
                    Route.Settings -> SettingsScreen(padding, onOpenDownloads = { nav.goTab(Route.Downloads) })
                    Route.About -> AboutScreen(padding)
                    else -> Unit
                }
            }
        }
    }
}
