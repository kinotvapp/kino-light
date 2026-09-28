package com.arkiv.player.ui.live

import android.view.KeyEvent
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.gateway.LiveChannel

/**
 * Stars or unstars [channel] under its own provider (the same code under two providers is two
 * favourites). The one write every favourite toggle goes through: the phone's grid, the TV's
 * drawer and guide ([LiveViewModel.toggleFavorite]) and the long OK on the TV player.
 */
suspend fun LiveFavoriteDao.toggle(channel: LiveChannel, isFavorite: Boolean) {
    if (isFavorite) delete(channel.provider, channel.code)
    else save(LiveFavoriteEntity(channel.code, channel.name, channel.number, channel.logo, provider = channel.provider))
}

/** The TV's hint under the channel lists (drawer and guide), where a long OK opens the confirmation. */
const val FAVORITE_HINT = "Mantén OK para agregar o quitar de favoritos"

/** The TV's hint on the player's channel card, where the right arrow opens the confirmation. */
const val PLAYER_FAVORITE_HINT = "Flecha derecha para agregar o quitar de favoritos"

/** The short confirmation shown right after the favourite dialog's confirm button. */
fun favoriteNotice(added: Boolean): String = if (added) "Agregado a favoritos" else "Quitado de favoritos"

/** What the TV's favourite confirmation dialog says: the question and its confirm button's label. */
data class FavoriteDialogCopy(val question: String, val confirm: String)

/**
 * The dialog's copy for [channelName], by whether it is a favourite right now: adding asks
 * "¿Agregar …?" / "Agregar", removing asks "¿Quitar …?" / "Quitar". The other button is always
 * "Cancelar" ([FAVORITE_DIALOG_CANCEL]).
 */
fun favoriteDialogCopy(channelName: String, isFavorite: Boolean): FavoriteDialogCopy =
    if (isFavorite) FavoriteDialogCopy("¿Quitar $channelName de favoritos?", "Quitar")
    else FavoriteDialogCopy("¿Agregar $channelName a favoritos?", "Agregar")

const val FAVORITE_DIALOG_CANCEL = "Cancelar"

/**
 * Whether a key on the live video (TV, drawer closed) opens the favourite dialog: the right arrow,
 * the only arrow live leaves free (left opens the drawer, up/down zap). Only on an En vivo module
 * channel -- Caracol and a plugin's `live` title have no channel to star. With the drawer open the
 * right arrow belongs to it (DrawerDpad closes it), so it never reaches this.
 */
fun liveKeyOpensFavoriteDialog(keyCode: Int, isModuleLive: Boolean, drawerOpen: Boolean): Boolean =
    isModuleLive && !drawerOpen && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

/**
 * Keeps a dialog from acting on the tail of the key press that opened it. A long OK opens the
 * favourite dialog from a list row while OK is still held: the rest of that press (its repeats and
 * its release) can land on the new window, and tv-material's Surface clicks on a bare release, so
 * the dialog would confirm itself. Nothing passes until a key goes down fresh (repeat 0) inside the
 * dialog; everything before that is swallowed.
 */
class FreshPressGate {
    private var armed = false

    /** True when the event may reach the dialog's buttons; false when it must be consumed. */
    fun admit(isDown: Boolean, repeatCount: Int): Boolean {
        if (!armed && isDown && repeatCount == 0) armed = true
        return armed
    }
}

/**
 * The row that takes focus when the one with [removedCode] leaves a TV list (a channel unstarred
 * while "Favoritos" is on screen): the one below it, or the one above when it was the last. Null
 * when it was the only row, or isn't on the list -- the caller then lands on the category column.
 * Without this, Compose clears focus with the removed row and the remote goes dead.
 */
fun neighborAfterRemoval(codes: List<String>, removedCode: String): String? {
    val i = codes.indexOf(removedCode)
    if (i < 0) return null
    return codes.getOrNull(i + 1) ?: codes.getOrNull(i - 1)
}
