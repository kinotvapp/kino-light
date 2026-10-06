package app.kino.tv.ui

import androidx.compose.runtime.mutableStateListOf
import app.kino.tv.data.Film

/** Every place the app can show. */
sealed interface Route {
    data object Home : Route
    data object Categories : Route
    data object Library : Route
    data object Downloads : Route
    data object Live : Route
    data object Extensions : Route
    data object Settings : Route
    data object About : Route
    data object Search : Route
    data object Assistant : Route
    data object SourcePicker : Route
    data class Title(val film: Film) : Route
    data class Player(val film: Film) : Route
}

/** A tiny back stack: the app has no deep links or process restore to care about. */
class Navigator(start: List<Route>) {
    val stack = mutableStateListOf<Route>().apply { addAll(start) }

    val current: Route get() = stack.last()

    fun push(route: Route) {
        if (stack.lastOrNull() != route) stack += route
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** A top-level section: Home underneath, the section on top (Home alone for Home). */
    fun goTab(route: Route) {
        stack.clear()
        stack += Route.Home
        if (route != Route.Home) stack += route
    }

    /** Leaves the first-run picker for Home. */
    fun finishPicker() {
        stack.remove(Route.SourcePicker)
        if (stack.isEmpty()) stack += Route.Home
    }
}
