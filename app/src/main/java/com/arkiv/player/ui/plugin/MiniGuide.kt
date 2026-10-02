package com.arkiv.player.ui.plugin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.canopas.lib.showcase.component.ShowcaseComposeView

/** The mini guide's step hint: a tap anywhere moves on, so the label only says what the tap does. */
internal const val MINI_GUIDE_NEXT = "Siguiente"
internal const val MINI_GUIDE_DONE = "Entendido"

internal fun miniGuideActionLabel(isLastStep: Boolean): String = if (isLastStep) MINI_GUIDE_DONE else MINI_GUIDE_NEXT

/**
 * The window flags the guide's overlay should carry: the library adds it as a focusable panel window, so
 * it swallows Back (nothing in it handles the key, and a panel window has no Activity to fall back to). Not
 * focusable, keys go to the Activity window and its back dispatcher -- where [MiniGuideTooltip]'s
 * BackHandler lives -- while touches still land on the full-screen overlay.
 */
internal fun miniGuideOverlayFlags(flags: Int): Int = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

/**
 * How far (px, <= 0 moves up) to move a card spanning [top]..[bottom] on screen so it sits inside the visible
 * band [minTop]..[maxBottom] (between the status and navigation bars). The library places the card above its
 * target when it fits and otherwise at `target.center + target.radius`: for a target as tall as the source
 * picker's grid that is at, or past, the bottom edge. Too tall for the band, the top (the title) wins.
 */
internal fun miniGuideCardShift(top: Int, bottom: Int, minTop: Int, maxBottom: Int): Int {
    val up = (maxBottom - bottom).coerceAtMost(0)
    return if (top + up < minTop) minTop - top else up
}

/**
 * The tooltip box the mini guide's steps share (source picker and Home), styled like the app's dialogs.
 * Ends with "Siguiente" / "Entendido" ([miniGuideActionLabel]): a plain label, not a button, because the
 * IntroShowcase must run with `dismissOnClickOutside = true`, which turns any tap on the overlay (this box
 * included) into "next step". Back calls [onClose], which ends the whole guide.
 */
@Composable
internal fun MiniGuideTooltip(title: String, body: String, isLastStep: Boolean, onClose: () -> Unit) {
    ReleaseOverlayKeyFocus()
    BackHandler(onBack = onClose)
    val view = LocalView.current
    var shiftPx by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            // The library offsets this card itself (below a target too tall to fit it above, so past the
            // bottom for the picker's grid) in its own full-screen panel window, where insets padding does
            // nothing: measure where it landed on screen against the Activity's system bars and pull it
            // back inside. Read before the offset, so the shift never feeds back into the measure.
            .onGloballyPositioned { coords ->
                val band = miniGuideVisibleBand(view) ?: return@onGloballyPositioned
                val origin = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
                val top = origin[1] + coords.positionInWindow().y.toInt()
                shiftPx = miniGuideCardShift(top, top + coords.size.height, band.first, band.last)
            }
            .offset { IntOffset(0, shiftPx) }
            .background(ArkivBlack, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
        Text(
            miniGuideActionLabel(isLastStep),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = ArkivRed,
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
        )
    }
}

/**
 * The screen band (px) the card may use: the Activity's window minus its status and navigation bars (and
 * cutout). Read from the Activity's decor view -- the showcase's panel window may see no insets at all -- and
 * the larger of both windows' insets wins. Null before the window is laid out.
 */
private fun miniGuideVisibleBand(view: View): IntRange? {
    val decor = view.context.miniGuideActivity()?.window?.decorView ?: view.rootView
    if (decor.height <= 0) return null
    val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    val own = ViewCompat.getRootWindowInsets(view)?.getInsets(types)
    val host = ViewCompat.getRootWindowInsets(decor)?.getInsets(types)
    val topInset = maxOf(own?.top ?: 0, host?.top ?: 0)
    val bottomInset = maxOf(own?.bottom ?: 0, host?.bottom ?: 0)
    val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
    return (origin[1] + topInset)..(origin[1] + decor.height - bottomInset)
}

private tailrec fun Context.miniGuideActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.miniGuideActivity()
    else -> null
}

/** Makes the showcase overlay window non-focusable ([miniGuideOverlayFlags]) so Back reaches the Activity. */
@Composable
private fun ReleaseOverlayKeyFocus() {
    val view = LocalView.current
    DisposableEffect(view) {
        val root = view.rootView
        // Only ever the library's overlay window, never the Activity's own.
        if (root is ShowcaseComposeView) {
            root.post {
                val params = root.layoutParams as? WindowManager.LayoutParams ?: return@post
                val flags = miniGuideOverlayFlags(params.flags)
                if (root.isAttachedToWindow && flags != params.flags) {
                    params.flags = flags
                    runCatching { root.context.getSystemService(WindowManager::class.java)?.updateViewLayout(root, params) }
                }
            }
        }
        onDispose { }
    }
}
