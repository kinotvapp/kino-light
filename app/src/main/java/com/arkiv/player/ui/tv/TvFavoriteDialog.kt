package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.arkiv.player.ui.live.FAVORITE_DIALOG_CANCEL
import com.arkiv.player.ui.live.FreshPressGate
import com.arkiv.player.ui.live.favoriteDialogCopy
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.theme.ArkivSurface

/**
 * The TV's "¿Agregar <canal> a favoritos?" / "¿Quitar <canal> de favoritos?" confirmation, shared by
 * the player (right arrow on a live channel), the channel drawer and the guide (long OK on a row).
 *
 * A [Dialog] is its own window, so while it is up it owns the D-pad: neither the video's key
 * listener nor the drawer's `onPreviewKeyEvent` sees a key. Focus starts on the confirm button,
 * which sits on the right: a second right arrow from the player has nowhere to go. Back and
 * "Cancelar" call [onDismiss]; the confirm button calls [onConfirm] (the caller toggles, announces
 * and closes). Where focus goes afterwards is the caller's job.
 *
 * [FreshPressGate] swallows what is left of the press that opened it (a long OK's repeats and
 * release), or a held OK would confirm on its own.
 */
@Composable
internal fun TvFavoriteDialog(
    channelName: String,
    isFavorite: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = favoriteDialogCopy(channelName, isFavorite)
    val confirmFocus = remember { FocusRequester() }
    val gate = remember { FreshPressGate() }
    FocusWhenReady(confirmFocus)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    !gate.admit(isDown = native.action == android.view.KeyEvent.ACTION_DOWN, repeatCount = native.repeatCount)
                }
                .widthIn(min = 360.dp, max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ArkivSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(copy.question, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvCompactAction(label = FAVORITE_DIALOG_CANCEL, onClick = onDismiss)
                TvCompactAction(
                    label = copy.confirm,
                    modifier = Modifier.focusRequester(confirmFocus),
                    onClick = onConfirm,
                )
            }
        }
    }
}
