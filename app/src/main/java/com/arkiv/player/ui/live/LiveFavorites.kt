package com.arkiv.player.ui.live

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

/** The TV's hint under the channel lists and on the player's channel card. */
const val FAVORITE_HINT = "Mantén OK para agregar o quitar de favoritos"

/** The short confirmation shown right after a long OK. */
fun favoriteNotice(added: Boolean): String = if (added) "Agregado a favoritos" else "Quitado de favoritos"

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
