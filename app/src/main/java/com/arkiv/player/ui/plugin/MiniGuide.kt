package com.arkiv.player.ui.plugin

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * The tooltip box the mini guide's steps share (source picker and Home), styled like the app's dialogs.
 * Ends with "Siguiente" / "Entendido" ([miniGuideActionLabel]): a plain label, not a button, because the
 * IntroShowcase must run with `dismissOnClickOutside = true`, which turns any tap on the overlay (this box
 * included) into "next step". Back calls [onClose], which ends the whole guide.
 */
@Composable
internal fun MiniGuideTooltip(title: String, body: String, isLastStep: Boolean, onClose: () -> Unit) {
    ReleaseOverlayKeyFocus()
    BackHandler(onBack = onClose)
    Column(
        Modifier
            // The overlay window spans the whole screen, system bars included: keep the card (and its
            // "Entendido") above the navigation bar, for 3-button and gesture navigation alike.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
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
