package app.kino.demo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import app.kino.demo.data.CatalogRow
import app.kino.demo.ui.about.TvAboutScreen
import app.kino.demo.ui.player.PlayerScreen
import app.kino.demo.ui.tv.FocusRescue
import app.kino.demo.ui.tv.TvCategoriesScreen
import app.kino.demo.ui.tv.TvHomeScreen
import app.kino.demo.ui.tv.TvLibraryScreen
import app.kino.demo.ui.tv.TvLiveScreen
import app.kino.demo.ui.tv.TvPluginsScreen
import app.kino.demo.ui.tv.TvRailItem
import app.kino.demo.ui.tv.TvSearchScreen
import app.kino.demo.ui.tv.TvSettingsScreen
import app.kino.demo.ui.tv.TvSourcePickerScreen
import app.kino.demo.ui.tv.TvTitleScreen

/** The TV's shell: Home with its rail, and every other place full screen with Back to return. */
@Composable
fun TvApp(rows: List<CatalogRow>, nav: Navigator) {
    val route = nav.current
    BackHandler(enabled = nav.stack.size > 1 && route != Route.SourcePicker && route !is Route.Player) { nav.pop() }
    // Each place keeps its saved state (scroll, the card Back returns to) while another one is on top.
    val saved = rememberSaveableStateHolder()

    Box(Modifier.fillMaxSize().onFocusChanged { FocusRescue.screenHasFocus = it.hasFocus }) {
        saved.SaveableStateProvider(route.toString()) { TvRoute(rows, nav, route) }
    }
}

@Composable
private fun TvRoute(rows: List<CatalogRow>, nav: Navigator, route: Route) {
    val context = LocalContext.current
    when (route) {
        Route.Home -> TvHomeScreen(
            rows = rows,
            railItems = listOf(
                TvRailItem(Icons.Default.Search, "Buscar", { nav.push(Route.Search) }),
                TvRailItem(Icons.Default.GridView, "Categorías", { nav.push(Route.Categories) }),
                TvRailItem(Icons.Default.VideoLibrary, "Mi biblioteca", { nav.push(Route.Library) }),
                TvRailItem(Icons.Default.LiveTv, "En vivo", { nav.push(Route.Live) }),
                TvRailItem(Icons.Default.Extension, "Plugins", { nav.push(Route.Extensions) }),
                TvRailItem(Icons.Default.Settings, "Ajustes", { nav.push(Route.Settings) }),
                TvRailItem(Icons.Default.Info, "Acerca de", { nav.push(Route.About) }),
            ),
            onOpenFilm = { nav.push(Route.Title(it)) },
        )
        is Route.Title -> TvTitleScreen(route.film, onPlay = { nav.push(Route.Player(route.film)) })
        is Route.Player -> PlayerScreen(route.film, isTv = true, onBack = { nav.pop() })
        Route.Search -> TvSearchScreen(rows, onOpenFilm = { nav.push(Route.Title(it)) })
        Route.Categories -> TvCategoriesScreen(rows, onOpenFilm = { nav.push(Route.Title(it)) })
        Route.Library, Route.Downloads -> TvLibraryScreen(rows, onOpenFilm = { nav.push(Route.Title(it)) })
        Route.Live -> TvLiveScreen(rows, onPlay = { nav.push(Route.Player(it)) })
        Route.Extensions -> TvPluginsScreen()
        Route.Settings -> TvSettingsScreen()
        Route.About -> TvAboutScreen()
        Route.SourcePicker -> TvSourcePickerScreen(onFinish = { nav.finishPicker(); markPickerDone(context) })
        Route.Assistant -> TvAboutScreen()
    }
}
