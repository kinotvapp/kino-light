package app.kino.tv.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/** How many times, [RETRY_MS] apart, a screen asks for its landing focus before giving up (about 3 s). */
private const val ATTEMPTS = 60
private const val RETRY_MS = 50L

/**
 * Where a TV screen puts the D-pad focus when it opens. `FocusRequester.requestFocus()` reports
 * nothing: on a box whose window is not focused yet (the first frames after launch) the request is
 * silently dropped. So success is the target's own focus state, and the request repeats until the
 * target really holds focus.
 */
@Stable
class LandingFocus internal constructor() {
    val requester = FocusRequester()
    var focused by mutableStateOf(false)
        internal set

    fun request() {
        runCatching { requester.requestFocus() }
    }
}

/**
 * The screen's landing focus, requested on entry (and again whenever [key] changes). The target is
 * also registered with [FocusRescue], so a D-pad press that finds nothing focused lands there.
 */
@Composable
fun rememberLandingFocus(key: Any? = Unit): LandingFocus {
    val landing = remember { LandingFocus() }
    LaunchedEffect(key) {
        repeat(ATTEMPTS) {
            if (landing.focused) return@LaunchedEffect
            landing.request()
            delay(RETRY_MS)
        }
    }
    DisposableEffect(landing) {
        FocusRescue.target = landing
        onDispose { if (FocusRescue.target === landing) FocusRescue.target = null }
    }
    return landing
}

/** Marks the element [landing] puts the focus on. Goes before the element's own focusable. */
fun Modifier.landingFocus(landing: LandingFocus): Modifier =
    focusRequester(landing.requester).onFocusChanged { landing.focused = it.isFocused }

/**
 * The safety net behind [rememberLandingFocus]: when a D-pad key reaches the activity unhandled and
 * nothing on screen holds focus, the current screen's landing target takes it.
 */
object FocusRescue {
    var target: LandingFocus? = null

    /** Whether anything inside the TV interface holds focus; kept by the root's `onFocusChanged`. */
    var screenHasFocus = false

    /** Returns true when it moved the focus. */
    fun rescue(): Boolean {
        if (screenHasFocus) return false
        val t = target ?: return false
        t.request()
        return true
    }
}
