package com.arkiv.player.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
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
import com.arkiv.player.ui.player.shouldOfferPluginConfigurar
import com.arkiv.player.ui.search.SearchScreen
import com.arkiv.player.ui.settings.SettingsScreen
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.titleinfo.TITLE_ROUTE
import com.arkiv.player.ui.titleinfo.TitleInfoScreen
import com.arkiv.player.ui.titleinfo.titleItemFrom
import com.arkiv.player.ui.titleinfo.titleOriginFrom
import com.arkiv.player.ui.titleinfo.titleRouteArguments

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
    // The phone's Plugins is a drawer item of its own; the TV keeps it as a tab of Ajustes.
    Tab(com.arkiv.player.ui.plugin.PLUGINS_ROUTE, "Plugins") { Icon(Icons.Default.Extension, contentDescription = "Plugins") },
    Tab("settings", "Ajustes") { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
)

/**
 * The tabs this device shows: Caracol only in Colombia ([isColombia]), "En vivo" only while the
 * live module has at least one provider ([liveModule], see `AppGraph.liveModule.available`):
 * Xuper while its plugin is on, plus any installed plugin with `channels`. Pure for the test.
 */
internal fun visibleTabRoutes(isColombia: Boolean, liveModule: Boolean, categoriesModule: Boolean = true): List<String> =
    TABS.map { it.route }.filter { route ->
        when (route) {
            // Hidden for now (2026-09-30): Caracol is getting rebuilt as a plugin, so the built-in
            // tab is off regardless of country until that lands. Not deleted -- isColombia is kept
            // as the parameter a re-enable would restore.
            "caracol" -> false
            "live" -> liveModule
            "categorias_home" -> categoriesModule
            else -> true
        }
    }

/**
 * "Categorías" lists the Xuper catalog's genres and featured rows (`CategoriesViewModel`) and, grouped by genre, the
 * browsable Home rows of every other plugin ([hasGenreTiles]). With neither there is nothing to list, so the tab (and
 * the TV rail's entry) is there only while the Xuper plugin is usable or some plugin has a row to browse.
 */
internal fun categoriesTabAvailable(plugins: List<com.arkiv.player.data.plugin.InstalledPlugin>, hasGenreTiles: Boolean): Boolean =
    com.arkiv.player.ui.home.CategoriesViewModel.xuperPluginId(plugins) != null || hasGenreTiles

/** Whether tapping the top bar's logo goes to Inicio: on every section but Inicio itself. */
internal fun logoGoesHome(currentRoute: String?): Boolean = currentRoute != null && currentRoute != "home"

/**
 * Home mini guide (phone only, spec 2026-09-30 §mini-guide): shown once, right after the mandatory
 * source picker finishes. Three steps, in this order: the content area, the drawer's menu icon, the
 * search icon (the last two only compose on "home", same as the guide's gate).
 */
internal const val HOME_INTRO_CONTENT_TITLE = "Tu contenido"
internal const val HOME_INTRO_CONTENT_BODY = "Acá vas a ver lo que traen tus plugins instalados."
internal const val HOME_INTRO_MENU_TITLE = "El menú"
internal const val HOME_INTRO_MENU_BODY = "Categorías, Biblioteca, Plugins y Ajustes están acá."
internal const val HOME_INTRO_SEARCH_TITLE = "Buscar"
internal const val HOME_INTRO_SEARCH_BODY = "Toca la lupa para buscar algo puntual."

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
    val isColombia = remember { com.arkiv.player.ui.live.deviceCountry(context) == "CO" }
    // "En vivo" follows the live module (Xuper or any plugin with channels), without a restart.
    val liveOn by graph.liveModule.available.collectAsStateWithLifecycle()
    val installedForTabs by graph.pluginRegistry.plugins.collectAsStateWithLifecycle()
    val genreTiles by graph.genreTiles.collectAsStateWithLifecycle()
    val categoriesOn = categoriesTabAvailable(installedForTabs, genreTiles.isNotEmpty())
    val tabs = remember(isColombia, liveOn, categoriesOn) {
        val routes = visibleTabRoutes(isColombia, liveOn, categoriesOn)
        TABS.filter { it.route in routes }
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
    fun goToLiveChannel(liveCode: String) {
        goToPlayer("${com.arkiv.player.playback.PlayerSource.LIVE_PREFIX}$liveCode")
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
    // The En vivo screen's buttons live in the app bar (guide, Recargar, "+"); this is what they share with the screen.
    val liveChrome = com.arkiv.player.ui.live.rememberLiveChromeState()
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

    // True while the picker on screen is the one opened at start for want of a source: Back then leaves the
    // app. One opened from the empty Home mid-session (a last plugin removed) is not: Back returns there.
    var sourcePickerMandatory by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    // The Home mini guide (spec 2026-09-30 §mini-guide): only right after the mandatory picker, never
    // reopened mid-session one. Consumed once by IntroShowcase's onShowCaseCompleted, below.
    var showHomeIntro by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    // True only while the "home" back-stack entry itself is RESUMED -- Navigation Compose holds an
    // entry below that state for as long as its AnimatedContent enter transition is still running. Set
    // from inside composable("home") below (see there for why). IntroShowcase measures its target's
    // boundsInWindow() the moment it turns on; doing that while the target still sits in the entering
    // transition reads a detached LayoutNode and crashes (IllegalStateException: LayoutCoordinate
    // operations are only valid when isAttached is true, inside
    // com.canopas.lib.showcase.component.ShowcaseContent -- confirmed via a device crash log, not a
    // mocked scenario). Not rememberSaveable: purely a transitional handoff, never worth restoring.
    var homeEntryResumed by remember { androidx.compose.runtime.mutableStateOf(false) }

    /** "Listo" of the source picker: back to Home. */
    fun finishSourcePicker() {
        if (sourcePickerMandatory) showHomeIntro = true
        sourcePickerMandatory = false
        com.arkiv.player.ui.plugin.leaveSourcePicker(
            isShowing = { navController.currentDestination?.route == com.arkiv.player.ui.plugin.SOURCE_PICKER_ROUTE },
            popBack = { navController.popBackStack() },
            goHome = { navController.navigate("home") },
        )
    }

    fun openSourcePicker() = navController.navigate(com.arkiv.player.ui.plugin.SOURCE_PICKER_ROUTE) { launchSingleTop = true }

    // Mandatory picker (spec amendment 2026-09-28): whoever has no installed-and-enabled plugin gets it at
    // every start of this root, new or updating device -- right after activation too (this root composes
    // fresh when MainActivity's onActivated fires). Decided as soon as the kind is recorded, or for an
    // updating device right after its Xuper migration step (`AppGraph.pickerDecisionReady`), never the rest
    // of warm-up; until then SourceDecisionCover hides the app. Read on IO: the registry's first read touches disk.
    // Once per composition of this root: a plugin removed mid-session leaves the empty Home until next start.
    var startGate by remember { androidx.compose.runtime.mutableStateOf(com.arkiv.player.ui.plugin.StartGate.DECIDING) }
    LaunchedEffect(Unit) {
        val open = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.arkiv.player.data.onboarding.Onboarding.opensPickerWhenReady(
                graph.pickerDecisionReady, { graph.settings.onboardingKind }, { graph.pluginRegistry.plugins.value },
            )
        }
        if (open) {
            startGate = com.arkiv.player.ui.plugin.StartGate.OPENING
            // Over any route but a deep-linked player: the picker waits until the person leaves it.
            navController.currentBackStackEntryFlow.first { com.arkiv.player.ui.plugin.pickerMayOpenOver(it.destination.route) }
            // The decision may be old by now (a restored back stack, a source installed meanwhile).
            if (com.arkiv.player.ui.plugin.startPickerStillNeeded(graph.pluginRegistry.plugins.value)) {
                sourcePickerMandatory = true
                // Only Home stays under it, so nothing else is one Back away (and Back there leaves the app).
                navController.navigate(com.arkiv.player.ui.plugin.SOURCE_PICKER_ROUTE) {
                    popUpTo(navController.graph.findStartDestination().id)
                    launchSingleTop = true
                }
            }
        }
        startGate = com.arkiv.player.ui.plugin.StartGate.DONE
    }

    val isWide = isLandscapeTablet()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    com.canopas.lib.showcase.IntroShowcase(
        showIntroShowCase = showHomeIntro && currentRoute == "home" && homeEntryResumed,
        onShowCaseCompleted = { showHomeIntro = false },
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isTab && !isWide,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = ArkivBlack) {
                // Scrolls so every tab stays reachable when the sheet is short (phone landscape).
                androidx.compose.foundation.layout.Column(
                    Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
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
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.introShowCaseTarget(
                                    index = 1,
                                    content = { com.arkiv.player.ui.plugin.MiniGuideTooltip(HOME_INTRO_MENU_TITLE, HOME_INTRO_MENU_BODY) },
                                ),
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = "Menú", tint = Color.White)
                            }
                        }
                    },
                    title = {
                        // Off Inicio the logo is the quick way home (the tab sections have no back arrow).
                        if (logoGoesHome(currentRoute)) {
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable(onClickLabel = "Ir al inicio", role = Role.Button) {
                                        tabs.firstOrNull { it.route == "home" }?.let(::goToTab)
                                    }
                                    .semantics { contentDescription = "Ir al inicio" },
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                KinoWordmark(height = 24.dp)
                            }
                        } else {
                            KinoWordmark(height = 24.dp)
                        }
                    },
                    actions = {
                        if (currentRoute == "live") {
                            var addMenu by remember { androidx.compose.runtime.mutableStateOf(false) }
                            if (liveChrome.hasGuide) {
                                IconButton(onClick = { liveChrome.toggleGuide() }) {
                                    Icon(
                                        imageVector = if (liveChrome.guideMode) Icons.Default.GridView else Icons.Default.ViewAgenda,
                                        contentDescription = if (liveChrome.guideMode) "Ver como grilla" else "Ver guía de programación",
                                        tint = if (liveChrome.guideMode) ArkivRed else Color.White,
                                    )
                                }
                            }
                            IconButton(onClick = { liveChrome.requestReload() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Recargar canales", tint = Color.White)
                            }
                            Box {
                                IconButton(onClick = { addMenu = true }) {
                                    Icon(Icons.Default.Add, contentDescription = com.arkiv.player.ui.live.OwnSourcesCopy.ADD_MENU, tint = Color.White)
                                }
                                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(com.arkiv.player.ui.live.OwnSourcesCopy.ADD_CHANNEL) },
                                        onClick = { addMenu = false; liveChrome.request(com.arkiv.player.ui.live.LiveChromeAction.ADD_CHANNEL) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(com.arkiv.player.ui.live.OwnSourcesCopy.ADD_PLAYLIST) },
                                        onClick = { addMenu = false; liveChrome.request(com.arkiv.player.ui.live.LiveChromeAction.ADD_PLAYLIST) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(com.arkiv.player.ui.live.OwnSourcesCopy.MY_SOURCES) },
                                        onClick = { addMenu = false; liveChrome.request(com.arkiv.player.ui.live.LiveChromeAction.MANAGE) },
                                    )
                                }
                            }
                        }
                        if (currentRoute == "home") {
                            IconButton(onClick = { graph.reloadHomeCatalog() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Recargar catálogo", tint = Color.White)
                            }
                            IconButton(onClick = { navController.navigate("kinobot") }) {
                                // The sparkles are the generic "AI assistant" icon; a speech bubble did not say it was an AI.
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "Kinobot, el asistente de IA",
                                    tint = Color.White,
                                )
                            }
                            IconButton(
                                onClick = { navController.navigate("search") },
                                modifier = Modifier.introShowCaseTarget(
                                    index = 2,
                                    content = { com.arkiv.player.ui.plugin.MiniGuideTooltip(HOME_INTRO_SEARCH_TITLE, HOME_INTRO_SEARCH_BODY) },
                                ),
                            ) {
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
                // homeEntryResumed: this entry only reaches RESUMED once Navigation Compose's own enter
                // transition for it has finished (see the flag's declaration above for why that matters).
                // ON_PAUSE resets it, so leaving Home before the guide ever showed re-arms it correctly.
                if (showHomeIntro) {
                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            when (event) {
                                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> homeEntryResumed = true
                                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> homeEntryResumed = false
                                else -> Unit
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }
                }
                HomeScreen(
                    onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                    onPlayEpisode = { playEpisode(it) },
                    onOpenTitleRoute = { route -> navController.navigate(route) { launchSingleTop = true } },
                    onPlayLive = { liveCode -> goToLiveChannel(liveCode) },
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
                    contentPadding = padding,
                    onBrowsePluginRow = { navController.navigate(com.arkiv.player.ui.plugin.PluginMoreTarget.route(it)) },
                    onOpenSourcePicker = { openSourcePicker() },
                    // The guide's first step (spec 2026-09-30 §mini-guide): the hero itself, a single
                    // bounded box, not the whole screen -- a full-screen target left the spotlight with
                    // nothing to cut a hole around.
                    heroModifier = Modifier.introShowCaseTarget(
                        index = 0,
                        content = { com.arkiv.player.ui.plugin.MiniGuideTooltip(HOME_INTRO_CONTENT_TITLE, HOME_INTRO_CONTENT_BODY) },
                    ),
                )
            }
            composable("live") {
                // Guard: with no live provider left (Xuper off and no plugin with channels) the tab
                // is gone, and a route reached anyway (it was on screen when the last provider
                // went, or restored state) goes back to Inicio without composing LiveScreen.
                if (!liveOn) {
                    LaunchedEffect(Unit) { TABS.firstOrNull { it.route == "home" }?.let(::goToTab) }
                } else com.arkiv.player.ui.live.LiveScreen(
                    // Task 14: the player's live mode already exists (`enVivo` flag in
                    // PlayerViewModel/PlayerScreen). `LiveScreen.open()` already left in
                    // LiveZappingSource the list it was entered with -- this just needs to navigate
                    // with the prefix PlayerSource.kindFor() recognizes as live. Without a linked
                    // account, goToLiveChannel sends the tap to Ajustes instead (see its KDoc).
                    onOpenChannel = { liveCode -> goToLiveChannel(liveCode) },
                    contentPadding = padding,
                    chrome = liveChrome,
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
            composable(com.arkiv.player.ui.plugin.PLUGINS_ROUTE) {
                com.arkiv.player.ui.plugin.PluginsDrawerScreen(contentPadding = padding)
            }
            composable("categorias_home") {
                // Guard, like "live": a route reached while Xuper is off (it was on screen when Xuper went, or restored
                // state) goes back to Inicio instead of showing an empty screen.
                if (!categoriesOn) {
                    LaunchedEffect(Unit) { TABS.firstOrNull { it.route == "home" }?.let(::goToTab) }
                } else com.arkiv.player.ui.home.CategoriesScreen(
                    contentPadding = padding,
                    onBrowse = { navController.navigate(com.arkiv.player.ui.plugin.PluginMoreTarget.route(it)) },
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
                "search?kind={kind}&tmdbId={tmdbId}&anilistId={anilistId}&query={query}&text={text}",
                arguments = listOf(
                    navArgument("kind") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("tmdbId") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("anilistId") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("query") { nullable = true; type = NavType.StringType; defaultValue = null },
                    navArgument("text") { nullable = true; type = NavType.StringType; defaultValue = null },
                ),
            ) { entry ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    SearchScreen(
                        onOpenDetail = { route -> navController.navigate(route) { launchSingleTop = true } },
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
                        shortcutText = entry.arguments?.getString("text"),
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
            composable(TITLE_ROUTE, arguments = titleRouteArguments) { entry ->
                val arg = { name: String -> entry.arguments?.getString(name) }
                val item = titleItemFrom(arg)
                val origin = titleOriginFrom(arg)
                if (item != null && origin != null) {
                    TitleInfoScreen(
                        item = item,
                        origin = origin,
                        onBack = { navController.popBackStack() },
                        onPlay = { playEpisode(it) },
                        onConfigurePlugin = { id -> navController.navigate("plugin_config/${Uri.encode(id)}") },
                    )
                } else {
                    // A corrupt or foreign route (or one from before Xuper became a plugin): never
                    // leave a blank screen on the stack.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
            composable("player/{episodeId}") { entry ->
                val episodeId = Uri.decode(entry.arguments?.getString("episodeId").orEmpty())
                PlayerScreen(
                    episodeId = episodeId,
                    onBack = { navController.popBackStack() },
                    onNextEpisode = { goToPlayer(it) },
                    // The player leaves: after configuring, Back returns to where the title was.
                    // No destination at all for the recognized Xuper plugin -- see
                    // shouldOfferPluginConfigurar's KDoc for why "Configurar" is meaningless there.
                    onOpenPluginSettings = { id ->
                        if (shouldOfferPluginConfigurar(graph.pluginRegistry.find(id)?.record?.address)) {
                            navController.navigate("plugin_config/${Uri.encode(id)}") {
                                popUpTo("player/{episodeId}") { inclusive = true }
                            }
                        }
                    },
                    // The player leaves too: Back from the sources returns to where the title was.
                    onOpenOtherSources = { title ->
                        navController.navigate(com.arkiv.player.ui.search.otherSourcesRoute(title)) {
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
                        onOpenTitleRoute = { route -> navController.navigate(route) { launchSingleTop = true } },
                        onPlay = { playEpisode(it) },
                        onOpenPluginSettings = { id ->
                            if (shouldOfferPluginConfigurar(graph.pluginRegistry.find(id)?.record?.address)) {
                                navController.navigate("plugin_config/${Uri.encode(id)}")
                            }
                        },
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
            composable(com.arkiv.player.ui.plugin.SOURCE_PICKER_ROUTE) {
                com.arkiv.player.ui.plugin.SourcePickerScreen(
                    onFinish = { finishSourcePicker() },
                    isMandatoryOnboarding = sourcePickerMandatory,
                    onBack = {
                        com.arkiv.player.ui.plugin.onSourcePickerBack(
                            sourcePickerMandatory,
                            exitApp = { com.arkiv.player.ui.plugin.exitFromSourcePicker(context) },
                            popBack = { navController.popBackStack() },
                        )
                    },
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
    } // end IntroShowcase (Home mini guide)
    if (com.arkiv.player.ui.plugin.startCoverShows(startGate, currentRoute)) com.arkiv.player.ui.plugin.SourceDecisionCover()
    } // end Box
}
