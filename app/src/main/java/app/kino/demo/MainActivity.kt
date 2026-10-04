package app.kino.demo

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.kino.demo.data.loadCatalog
import app.kino.demo.ui.LocalReducedEffects
import app.kino.demo.ui.Navigator
import app.kino.demo.ui.PhoneApp
import app.kino.demo.ui.Route
import app.kino.demo.ui.TvApp
import app.kino.demo.ui.brand.KinoSplash
import app.kino.demo.ui.isPickerDone
import app.kino.demo.ui.systemAnimationsOff
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoTheme
import app.kino.demo.ui.tv.FocusRescue

class MainActivity : ComponentActivity() {
    private var isTv = false

    /**
     * A D-pad key no view handled (onKeyDown only sees those), with nothing focused on the TV screen: the screen's landing
     * element takes the focus, so the remote never ends up doing nothing.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isTv && keyCode in DPAD_KEYS && FocusRescue.rescue()) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceKind.isTelevision(this)
        val rows = runCatching { loadCatalog(this) }.getOrDefault(emptyList())
        val pickerDone = isPickerDone(this)
        val nav = Navigator(if (pickerDone) listOf(Route.Home) else listOf(Route.Home, Route.SourcePicker))
        val reduced = systemAnimationsOff(this)

        setContent {
            KinoTheme {
                CompositionLocalProvider(LocalReducedEffects provides reduced) {
                    var splashVisible by rememberSaveable { mutableStateOf(true) }
                    val navigator = remember { nav }
                    Box(Modifier.fillMaxSize().background(KinoBlack)) {
                        if (isTv) TvApp(rows, navigator) else PhoneApp(rows, navigator)
                        if (splashVisible) {
                            KinoSplash(isTv = isTv, canExit = true, onFinished = { splashVisible = false })
                        }
                    }
                }
            }
        }
    }
}

private val DPAD_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
)
