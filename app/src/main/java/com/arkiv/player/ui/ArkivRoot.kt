package com.arkiv.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arkiv.player.ui.catalog.AnimeShowDetailScreen
import com.arkiv.player.ui.catalog.CaracolScreen
import com.arkiv.player.ui.catalog.CineCatalogScreen
import com.arkiv.player.ui.catalog.CineDetailScreen
import com.arkiv.player.ui.detail.DetailScreen
import com.arkiv.player.ui.downloads.DownloadsScreen
import com.arkiv.player.ui.home.HomeScreen
import com.arkiv.player.ui.home.RowBrowseScreen
import com.arkiv.player.ui.library.LibraryScreen
import com.arkiv.player.ui.player.PlayerScreen
import com.arkiv.player.ui.search.SearchScreen
import com.arkiv.player.ui.settings.SettingsScreen
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

// The "Magis" tab (route "catalog" → CineCatalogScreen/CineDetailScreen) was pulled from the bar:
// it was a TMDB catalog whose only CTA ("Buscar fuentes") opened a panel that only listed
// archive.org (deleted in this branch's pruning) — Magis never hooked into it, so the panel was
// always empty (see the finding from this branch's spec final review). The real path to play
// Magis from TMDB already exists and isn't touched here: "Categorías" → a row → a card → the
// search screen (SearchScreen/SearchViewModel.runSourceSearch), which does search Magis. The
// "catalog" route and its screens stay alive in the NavHost in case a future sub-project hooks a
// real Magis search there; to show the tab again it's enough to add it back to this list.
private val TABS = listOf(
    Tab("home", "Inicio") { Icon(Icons.Default.Home, contentDescription = "Inicio") },
    Tab("categorias_home", "Categorías") { Icon(Icons.Default.GridView, contentDescription = "Categorías") },
    Tab("library", "Biblioteca") { Icon(Icons.Default.VideoLibrary, contentDescription = "Biblioteca") },
    Tab("downloads", "Descargas") { Icon(Icons.Default.Download, contentDescription = "Descargas") },
    Tab("live", "En vivo") { Icon(Icons.Default.LiveTv, contentDescription = "En vivo") },
    Tab("caracol", "Caracol") { Icon(Icons.Default.Theaters, contentDescription = "Caracol") },
    Tab("settings", "Ajustes") { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkivRoot(
    deepLinkEpisodeId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    // Caracol Streaming (Ditu) only carries Colombian content, and its tab led plenty of people
    // outside Colombia into a catalog with nothing for them. `deviceCountry` is the same free,
    // no-permission, no-network signal `countryChannelsForHome` already uses for the live channels
    // row -- SIM, then time zone, then locale.
    val context = androidx.compose.ui.platform.LocalContext.current
    val tabs = remember {
        if (com.arkiv.player.ui.live.deviceCountry(context) == "CO") TABS else TABS.filterNot { it.route == "caracol" }
    }

    // "Ver en el TV / Ver en el celular" chooser: only when linked to a TV; otherwise plays local.
    // The dialog renders in its own window, so composing the host here (before the layout) is fine.
    val cast = com.arkiv.player.ui.companion.rememberCompanionCast()
    com.arkiv.player.ui.companion.CompanionCastHost(cast)

    // No upfront Magis account offer on entry: the app starts as a guest (anonymous/seeds), no
    // account prompt. `refresh()` still runs once so `graph.magisAccount.state` reflects reality
    // before anything reads it (Settings' AccountSection, the player's on-demand link prompt) --
    // see MagisAccount.refresh's KDoc for why this can't happen in the constructor. Linking is now
    // either voluntary (Ajustes -> Cuenta -> Vincular, see SettingsScreen) or on-demand, prompted
    // by the player itself when a live channel actually needs it (see PlayerScreen's
    // "Vincular cuenta" action, wired to PlayerViewModel.needsMagisAccount).
    LaunchedEffect(Unit) {
        graph.magisAccount.refresh()
    }

    // Unified player: every source shares this one route. PlayerSource.kindFor() reads the id's
    // prefix to route to Magis/Ditu/live; a legacy id from a source removed in this branch
    // (torrent, archive.org, web) falls into SourceKind.UNKNOWN and PlayerViewModel.loadUnknownSource
    // shows a "no longer available" message instead.
    fun navigateToPlayer(id: String) {
        navController.navigate("player/${Uri.encode(id)}") { launchSingleTop = true }
    }
    // Every user-initiated playback funnels through here, so this is where the "Ver en el TV /
    // Ver en el celular" chooser sits: linked to a TV -> chooser; otherwise plays locally.
    fun goToPlayer(id: String) = cast.onPlay(id, ::navigateToPlayer)
    fun playEpisode(id: String) = goToPlayer(id)

    // Most Magis live channels play on an anonymous session (verified against the portal), so we no
    // longer block on a linked account up front -- we TRY. Only if the portal refuses THIS channel
    // (a premium one) does the player show the "link your Xuper account" message
    // (PlayerViewModel.liveErrorMessage + MagisLive maps aaa100028 -> live_no_account).
    fun goToLiveChannel(code: String) {
        goToPlayer("${com.arkiv.player.playback.PlayerSource.LIVE_PREFIX}$code")
    }

    // Deep link from the notification: open the player on that chapter.
    androidx.compose.runtime.LaunchedEffect(deepLinkEpisodeId) {
        if (deepLinkEpisodeId != null) {
            navController.navigate("player/${Uri.encode(deepLinkEpisodeId)}") {
                launchSingleTop = true
            }
            onDeepLinkConsumed()
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTab = currentRoute in TABS.map { it.route }

    // A single definition of "go to a tab", so the rail and the bar can't diverge in behavior
    // (catalog reset, popUpTo, restoreState).
    fun goToTab(tab: Tab) {
        if (tab.route == "catalog") graph.catalogResetSignal.tryEmit(Unit)
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val isWide = isLandscapeTablet()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isTab && !isWide,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = ArkivBlack) {
                Spacer(Modifier.height(24.dp))
                KinoWordmark(
                    height = 30.dp,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
                tabs.forEach { tab ->
                    val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationDrawerItem(
                        icon = tab.icon,
                        label = { Text(tab.label) },
                        selected = selected,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = ArkivRed.copy(alpha = 0.15f),
                            selectedIconColor = ArkivRed,
                            selectedTextColor = ArkivRed,
                            unselectedIconColor = Color.White,
                            unselectedTextColor = Color.White,
                        ),
                        onClick = {
                            scope.launch { drawerState.close() }
                            goToTab(tab)
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        },
    ) {

    Row(Modifier.fillMaxSize()) {
    if (isWide && isTab) {
        NavigationRail(containerColor = ArkivBlack) {
            tabs.forEach { tab ->
                val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                NavigationRailItem(
                    selected = selected,
                    onClick = { goToTab(tab) },
                    icon = tab.icon,
                    label = { Text(tab.label) },
                )
            }
        }
    }
    Scaffold(
        modifier = Modifier.weight(1f),
        containerColor = ArkivBlack,
        topBar = {
            if (isTab) {
                TopAppBar(
                    navigationIcon = {
                        if (!isWide) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menú", tint = Color.White)
                            }
                        }
                    },
                    title = {
                        KinoWordmark(height = 24.dp)
                    },
                    actions = {
                        if (currentRoute == "home") {
                            IconButton(onClick = { graph.reloadHomeCatalog() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Recargar catálogo", tint = Color.White)
                            }
                            IconButton(onClick = { navController.navigate("kinobot") }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = "Kinobot",
                                    tint = Color.White,
                                )
                            }
                            IconButton(onClick = { navController.navigate("search") }) {
                                Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = ArkivBlack),
                )
            }
        },
        // No FAB. The "+" used to open "add by archive.org identifier", deleted along with the
        // rest of archive.org: content now comes in through search. It covered home content
        // floating on top, which is expensive for a button nobody taps.
        //
        // No bottomBar: the "playing on TV/Chromecast" bar depended on
        // NowPlayingCoordinator/RemoteController, deleted in Task 5 along with the rest of pairing.
        // Chromecast is still available FROM INSIDE the player (cast button in PlayerScreen).
    ) { padding ->
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                    onPlayEpisode = { playEpisode(it) },
                    onPlayLive = { code -> goToLiveChannel(code) },
                    // "Ver más canales": the same options as tapping the "En vivo" tab below, so it
                    // shows marked as selected and the back stack doesn't grow from entering here.
                    onOpenLive = {
                        navController.navigate("live") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenLibrary = { navController.navigate("library") },
                    onBrowseMagisRow = { rowId, title ->
                        navController.navigate("magis_row/$rowId?title=${android.net.Uri.encode(title)}")
                    },
                    contentPadding = padding,
                    onBrowsePluginRow = { navController.navigate(com.arkiv.player.ui.plugin.PluginMoreTarget.route(it)) },
                )
            }
            composable("live") {
                com.arkiv.player.ui.live.LiveScreen(
                    // Task 14: the player's live mode already exists (`enVivo` flag in
                    // PlayerViewModel/PlayerScreen). `LiveScreen.open()` already left in
                    // LiveZappingSource the list it was entered with -- this just needs to navigate
                    // with the prefix PlayerSource.kindFor() recognizes as live. Without a linked
                    // account, goToLiveChannel sends the tap to Ajustes instead (see its KDoc).
                    onOpenChannel = { code -> goToLiveChannel(code) },
                    contentPadding = padding,
                )
            }
            composable("caracol") {
                CaracolScreen(
                    onPlay = { id -> playEpisode(id) },
                    contentPadding = padding,
                )
            }
            composable("library") {
                LibraryScreen(
                    onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                    onPlayEpisode = { playEpisode(it) },
                    contentPadding = padding,
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    contentPadding = padding,
                    onPlayEpisode = { playEpisode(it) },
                )
            }
            composable("settings") {
    SettingsScreen(
        contentPadding = padding,
        // "downloads" is now a tab (see TABS above): go through the same goToTab() the drawer/rail
        // use, so the tab shows selected and the back stack behaves like any other tab switch.
        onOpenDownloads = { goToTab(TABS.first { it.route == "downloads" }) },
    )
}
            composable("categorias_home") {
                com.arkiv.player.ui.home.CategoriesScreen(
                    contentPadding = padding,
                    onBrowseRow = { rowId, title ->
                        navController.navigate("magis_row/$rowId?title=${android.net.Uri.encode(title)}")
                    },
                )
            }
            composable("catalog") {
                CineCatalogScreen(
                    onOpen = { navController.navigate("cine/${it.type}/${it.id}") },
                    onOpenAnime = { navController.navigate("catalog_anime/$it") },
                    contentPadding = padding,
                )
            }
            composable("kinobot") {
                // Takes the Scaffold's padding for the status/nav bars (no topBar on this route);
                // consumeWindowInsets so KinobotScreen's own imePadding lifts by the KEYBOARD only,
                // without double-counting the nav bar (KinobotScreen forces adjustNothing so the
                // window doesn't ALSO resize -- that combo is what put the input mid-screen before).
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    com.arkiv.player.ui.kinobot.KinobotScreen(
                        onBack = { navController.popBackStack() },
                        onSearch = { title -> navController.navigate("search?query=${android.net.Uri.encode(title)}") },
                    )
                }
            }
            composable(
                "search?kind={kind}&tmdbId={tmdbId}&anilistId={anilistId}&query={query}",
                arguments = listOf(
                    navArgument("kind") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("tmdbId") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("anilistId") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("query") { nullable = true; type = NavType.StringType; defaultValue = null },
                ),
            ) { entry ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    SearchScreen(
                        onOpenDetail = { route -> navController.navigate(route) },
                        onPlay = { id -> playEpisode(id) },
                        onBack = { navController.popBackStack() },
                        onBrowseRow = { rowId, title ->
                            navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
                        },
                        shortcutKind = entry.arguments?.getString("kind"),
                        shortcutTmdbId = entry.arguments?.getString("tmdbId")?.toIntOrNull(),
                        shortcutAnilistId = entry.arguments?.getString("anilistId")?.toLongOrNull(),
                        shortcutQuery = entry.arguments?.getString("query"),
                        onBrowsePlugin = { navController.navigate(com.arkiv.player.ui.plugin.PluginMoreTarget.route(it)) },
                    )
                }
            }
            composable(
                "cine/{type}/{tmdbId}?season={season}&episode={episode}",
                arguments = listOf(
                    navArgument("season") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("episode") { nullable = true; type = NavType.StringType; defaultValue = null },
                ),
            ) { entry ->
                val type = entry.arguments?.getString("type") ?: "tv"
                val tmdbId = entry.arguments?.getString("tmdbId")?.toIntOrNull() ?: 0
                val season = entry.arguments?.getString("season")?.toIntOrNull()
                val episode = entry.arguments?.getString("episode")?.toIntOrNull()
                Box(Modifier.fillMaxSize().padding(padding)) {
                    CineDetailScreen(
                        tmdbId = tmdbId,
                        type = type,
                        onPlay = { playEpisode(it) },
                        onBack = { navController.popBackStack() },
                        onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                        deepLinkSeason = season,
                        deepLinkEpisode = episode,
                    )
                }
            }
            composable(
                "catalog_anime/{anilistId}?episode={episode}",
                arguments = listOf(
                    navArgument("episode") { nullable = true; type = NavType.StringType; defaultValue = null },
                ),
            ) { entry ->
                val anilistId = entry.arguments?.getString("anilistId")?.toLongOrNull() ?: 0L
                val episode = entry.arguments?.getString("episode")?.toIntOrNull()
                Box(Modifier.fillMaxSize().padding(padding)) {
                    AnimeShowDetailScreen(
                        anilistId = anilistId,
                        onPlay = { playEpisode(it) },
                        onBack = { navController.popBackStack() },
                        onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                        deepLinkEpisode = episode,
                    )
                }
            }
            composable("detail/{itemId}") { entry ->
                val itemId = Uri.decode(entry.arguments?.getString("itemId").orEmpty())
                DetailScreen(
                    identifier = itemId,
                    onBack = { navController.popBackStack() },
                    onPlayEpisode = { playEpisode(it) },
                )
            }
            composable("player/{episodeId}") { entry ->
                val episodeId = Uri.decode(entry.arguments?.getString("episodeId").orEmpty())
                val itemId = episodeId.substringBefore("::")
                PlayerScreen(
                    episodeId = episodeId,
                    onBack = { navController.popBackStack() },
                    onOpenEpisodes = {
                        navController.navigate("detail/${Uri.encode(itemId)}") {
                            popUpTo("player/{episodeId}") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onNextEpisode = { goToPlayer(it) },
                    // The player leaves: after configuring, Back returns to where the title was.
                    onOpenPluginSettings = { id ->
                        navController.navigate("plugin_config/${Uri.encode(id)}") {
                            popUpTo("player/{episodeId}") { inclusive = true }
                        }
                    },
                )
            }
            composable(
                com.arkiv.player.ui.plugin.PluginMoreTarget.PATTERN,
                arguments = listOf(
                    navArgument("pluginId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("ref") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("query") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("cursor") { nullable = true; type = NavType.StringType; defaultValue = null },
                ),
            ) { entry ->
                val a = entry.arguments
                val target = com.arkiv.player.ui.plugin.PluginMoreTarget.fromRoute(
                    a?.getString("pluginId").orEmpty(), a?.getString("title").orEmpty(),
                    a?.getString("ref"), a?.getString("query"), a?.getString("cursor"),
                )
                if (target != null) {
                    com.arkiv.player.ui.plugin.PluginMoreScreen(
                        target = target,
                        onPlayEpisode = { playEpisode(it) },
                        onOpenPluginSettings = { id -> navController.navigate("plugin_config/${Uri.encode(id)}") },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable("plugin_config/{pluginId}") { entry ->
                com.arkiv.player.ui.plugin.PluginConfigRoute(
                    pluginId = Uri.decode(entry.arguments?.getString("pluginId").orEmpty()),
                    isTv = false,
                    onDone = { navController.popBackStack() },
                )
            }
            composable(
                "magis_row/{rowId}?title={title}",
                arguments = listOf(
                    navArgument("rowId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                com.arkiv.player.ui.home.MagisRowBrowseScreen(
                    rowId = entry.arguments?.getString("rowId").orEmpty(),
                    title = entry.arguments?.getString("title").orEmpty(),
                    onPlay = { playEpisode(it) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                "row_browse/{rowId}?title={title}",
                arguments = listOf(
                    navArgument("rowId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val rowId = entry.arguments?.getString("rowId").orEmpty()
                val title = entry.arguments?.getString("title").orEmpty()
                RowBrowseScreen(
                    rowId = rowId,
                    title = title,
                    onOpenSearchRoute = { route -> navController.navigate(route) },
                    onBack = { navController.popBackStack() },
                    graph = graph,
                )
            }
        }

    }
    } // end Row
    } // end ModalNavigationDrawer
}
