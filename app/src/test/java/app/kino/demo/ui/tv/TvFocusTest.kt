package app.kino.demo.ui.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kino.demo.data.parseCatalog
import app.kino.demo.ui.Navigator
import app.kino.demo.ui.Route
import app.kino.demo.ui.TvApp
import app.kino.demo.ui.theme.KinoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every TV screen lands the D-pad focus on something, and the D-pad moves it. Runs the real TV
 * shell on Robolectric with the bundled catalog.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television")
class TvFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private val rows = parseCatalog(File("src/main/assets/home.json").readText())
    private val focused = SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)

    private fun start(vararg stack: Route): Navigator {
        val nav = Navigator(stack.toList())
        compose.setContent { KinoTheme { TvApp(rows, nav) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        return nav
    }

    private fun focusedCount() = compose.onAllNodes(focused, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun assertLanded(where: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("$where: nothing focused", focusedCount() >= 1)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    @Test
    fun homeToFilmToPlayerAndBack() {
        val nav = start(Route.Home)
        assertLanded("Inicio")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertTrue(nav.current is Route.Title)
        assertLanded("Ficha")
        press(Key.DirectionCenter)
        assertTrue(nav.current is Route.Player)
        assertLanded("Reproductor")
    }

    @Test
    fun railOpensFromHome() {
        val nav = start(Route.Home)
        assertLanded("Inicio")
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertTrue("rail should open a section, is ${nav.current}", nav.current != Route.Home)
    }

    @Test
    fun everySectionLands() {
        val nav = start(Route.Home)
        listOf(Route.Search, Route.Categories, Route.About).forEach { route ->
            compose.runOnIdle { nav.push(route) }
            assertLanded(route.toString())
            press(Key.DirectionDown)
            assertTrue("$route: focus lost after Down", focusedCount() >= 1)
            compose.runOnIdle { nav.pop() }
            assertLanded("Inicio back from $route")
        }
    }
}
