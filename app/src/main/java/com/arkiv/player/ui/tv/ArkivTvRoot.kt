package com.arkiv.player.ui.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.arkiv.player.playback.MagisEphemeral
import com.arkiv.player.ui.home.isMagisSeries
import com.arkiv.player.ui.home.toGatewayResult
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arkiv.player.ui.player.PlayerScreen
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack

@Composable
fun ArkivTvRoot(
    deepLinkEpisodeId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val graph = rememberGraph()
    val context = LocalContext.current

    // No upfront Magis account offer on entry: the app starts as a guest (anonymous/seeds), no
    // account prompt, straight to home. `refresh()` still runs once so `graph.magisAccount.state`
    // reflects reality before anything reads it (Settings' TvSettingsAccount, the player's
    // on-demand link prompt) -- see MagisAccount.refresh's KDoc for why this can't happen in the
    // constructor. Linking is now either voluntary (Ajustes -> Cuenta -> Vincular, see
    // TvSettingsScreen) or on-demand, prompted by the player itself when a live channel actually
    // needs it (see PlayerScreen's "Vincular cuenta" action, wired to
    // PlayerViewModel.needsMagisAccount).
    LaunchedEffect(Unit) {
        graph.magisAccount.refresh()
    }

    // Keep the screen on while the TV app is open (no wallpaper kicking in).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Unified player: every source shares this one route. PlayerSource.kindFor() resolves the
    // source from the episodeId prefix (Magis/Ditu/live); a legacy id from a source removed in
    // this branch (torrent, archive.org, web) falls into SourceKind.UNKNOWN, and PlayerScreen shows
    // a "no longer available" message for it (see PlayerViewModel.loadUnknownSource).
    fun goToPlayer(id: String) {
        navController.navigate("player/${Uri.encode(id)}") { launchSingleTop = true }
    }

    val magisScope = rememberCoroutineScope()
    val magisPlayback = remember(graph) { com.arkiv.player.ui.search.SearchPlayback(graph) }

    /** A home Magis card: a series opens its chapters, a movie plays (saved like a search result). */
    fun openMagis(item: com.arkiv.player.data.gateway.CatalogItem) {
        if (item.isMagisSeries) {
            navController.navigate(magisSeriesRoute(item))
            return
        }
        magisScope.launch {
            when (val r = magisPlayback.playMagis(item.toGatewayResult())) {
                is com.arkiv.player.ui.search.PlaybackResult.Ready -> goToPlayer(r.episodeId)
                is com.arkiv.player.ui.search.PlaybackResult.Failed ->
                    Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Most Magis live channels play on an anonymous session (verified against the portal), so we no
    // longer block on a linked account up front -- we TRY. Only if the portal refuses THIS channel
    // (a premium one) does the player show the "link your Xuper account" message
    // (PlayerViewModel.liveErrorMessage + MagisLive maps aaa100028 -> live_no_account).
    fun goToLiveChannel(code: String) {
        goToPlayer("${com.arkiv.player.playback.PlayerSource.LIVE_PREFIX}$code")
    }

    LaunchedEffect(deepLinkEpisodeId) {
        if (deepLinkEpisodeId != null) {
            goToPlayer(deepLinkEpisodeId)
            onDeepLinkConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.fillMaxSize().background(ArkivBlack),
    ) {
        composable("home") {
            // On home, back exits the app: we ask for double-back confirmation to
            // avoid accidental exits from the remote.
            var lastBackAt by remember { mutableStateOf(0L) }
            BackHandler {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < 2000) {
                    context.findActivity()?.finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(context, "Presiona atrás de nuevo para salir", Toast.LENGTH_SHORT).show()
                }
            }
            TvHomeScreen(
                onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                onPlayEpisode = { goToPlayer(it) },
                onPlayLive = { code -> goToLiveChannel(code) },
                onOpenSettings = { navController.navigate("settings") },
                onOpenSearch = { navController.navigate("search") },
                onOpenLibrary = { navController.navigate("library") },
                onOpenLive = { navController.navigate("live") },
                onOpenCaracol = { navController.navigate("caracol") },
                onOpenCategorias = { navController.navigate("categorias") },
                onOpenCategoriasHome = { navController.navigate("categorias_home") },
                onOpenMagis = { openMagis(it) },
                onBrowseMagisRow = { rowId, title ->
                    navController.navigate("magis_row/$rowId?title=${android.net.Uri.encode(title)}")
                },
            )
        }
        composable(
            "search?kind={kind}&tmdbId={tmdbId}&anilistId={anilistId}",
            arguments = listOf(
                navArgument("kind") { nullable = true; type = NavType.StringType; defaultValue = null },
                navArgument("tmdbId") { nullable = true; type = NavType.StringType; defaultValue = null },
                navArgument("anilistId") { nullable = true; type = NavType.StringType; defaultValue = null },
            ),
        ) { entry ->
            TvSearchScreen(
                onPlay = { goToPlayer(it) },
                onBack = { navController.popBackStack() },
                onBrowseRow = { rowId, title ->
                    navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
                },
                shortcutKind = entry.arguments?.getString("kind"),
                shortcutTmdbId = entry.arguments?.getString("tmdbId")?.toIntOrNull(),
                shortcutAnilistId = entry.arguments?.getString("anilistId")?.toLongOrNull(),
            )
        }
        composable("categorias") {
            // The adults sections only if THIS device has the code set (Settings).
            // `MagisLiveCatalog.arbol` filters the 18+ section client-side and blows up with
            // `require` if its root is requested without the flag, so the default is the safe
            // one even if this screen got opened some other way.
            val unlocked = graph.settings.adultsUnlocked.value
            val scope = rememberCoroutineScope()
            TvCatalogSections(
                includeAdults = unlocked,
                onPlay = { item ->
                    scope.launch {
                        if (item.adult) {
                            // Doesn't go through the library. `addMagisSource` would write a row
                            // that shows up right here on this device -- and, until cloud sync was
                            // removed with the rest of this branch's pruning, would also have
                            // synced to the phone and the other TV, which is exactly the 2026-08-14
                            // leak. The ref travels around it instead; see [MagisEphemeral].
                            val id = MagisEphemeral.idFor(item.id)
                            MagisEphemeral.leave(
                                MagisEphemeral.Pending(id, item.ref, item.title, adulto = true),
                            )
                            goToPlayer(id)
                        } else {
                            // Usual path: saving it is what gives it "continue watching" and a
                            // card in the library, same as if it had come in through search.
                            val epId = graph.repository.addMagisSource(
                                ref = item.ref,
                                contentId = item.id,
                                title = item.title,
                                posterUrl = item.poster.orEmpty(),
                            )
                            if (epId != null) goToPlayer(epId)
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            MAGIS_SERIES_ROUTE,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType; defaultValue = "teleplay" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                navArgument("backdrop") { type = NavType.StringType; defaultValue = "" },
                navArgument("count") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            val args = entry.arguments
            TvMagisSeriesScreen(
                item = magisSeriesItem(
                    id = args?.getString("id").orEmpty(),
                    type = args?.getString("type").orEmpty(),
                    title = args?.getString("title").orEmpty(),
                    poster = args?.getString("poster").orEmpty(),
                    backdrop = args?.getString("backdrop").orEmpty(),
                    count = args?.getInt("count") ?: 0,
                ),
                onPlay = { goToPlayer(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable("categorias_home") {
            TvCategoriesScreen(
                onBrowseRow = { rowId, title ->
                    navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
                },
                onOpenSearchRoute = { navController.navigate(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable("library") {
            com.arkiv.player.ui.tv.library.TvLibraryScreen(
                onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                onPlayEpisode = { goToPlayer(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable("live") {
            TvLiveGuideScreen(
                // Task 14: the player's live mode already exists (`enVivo` flag in
                // PlayerViewModel/PlayerScreen). `TvLiveGuideScreen.watchChannel()` already left
                // in LiveZappingSource the list it was entered with -- here it only needs to
                // navigate with the prefix PlayerSource.kindFor() recognizes as live. Without a
                // linked account, goToLiveChannel opens the linking screen instead (see its KDoc).
                onWatchChannel = { channel -> goToLiveChannel(channel.code) },
                onBack = { navController.popBackStack() },
            )
        }
        composable("caracol") {
            // What arrives is the episodeId to navigate with: a title's that got saved the same
            // way as from search, or a live channel's that travels via `DituLive`.
            TvCaracolScreen(onPlay = { goToPlayer(it) })
        }
        composable("detail/{itemId}") { entry ->
            val itemId = Uri.decode(entry.arguments?.getString("itemId").orEmpty())
            TvDetailScreen(
                groupKey = itemId,
                onPlayEpisode = { goToPlayer(it) },
            )
        }
        composable("settings") {
            TvSettingsScreen()
        }
        composable("player/{episodeId}") { entry ->
            val episodeId = Uri.decode(entry.arguments?.getString("episodeId").orEmpty())
            // The publisher uses this to know whether something's really playing here. Without
            // this signal it went by NowPlaying.episodeId, which never gets cleared, so the TV
            // kept announcing the last paused chapter and the phone's bar never went away.
            androidx.compose.runtime.DisposableEffect(Unit) {
                com.arkiv.player.playback.NowPlaying.playerOpen = true
                // Not cleared in onDispose: it's only meaningful while playerOpen is true, so
                // clearing it here buys nothing.
                com.arkiv.player.playback.NowPlaying.playerOpenedAtMs = System.currentTimeMillis()
                onDispose { com.arkiv.player.playback.NowPlaying.playerOpen = false }
            }
            PlayerScreen(
                episodeId = episodeId,
                onBack = { navController.popBackStack() },
                onOpenEpisodes = { navController.popBackStack() },
                onNextEpisode = { goToPlayer(it) },
                isTv = true,
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
            TvRowBrowseScreen(
                rowId = rowId,
                title = title,
                onOpenSearchRoute = { route -> navController.navigate(route) },
                onBack = { navController.popBackStack() },
                graph = graph,
            )
        }
        composable(
            "magis_row/{rowId}?title={title}",
            arguments = listOf(
                navArgument("rowId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            TvMagisRowBrowseScreen(
                rowId = entry.arguments?.getString("rowId").orEmpty(),
                title = entry.arguments?.getString("title").orEmpty(),
                onOpenMagis = { openMagis(it) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Unwraps the Context until finding the Activity (to inject key events). */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
