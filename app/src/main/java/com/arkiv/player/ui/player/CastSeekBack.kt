package com.arkiv.player.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * A seek, while casting, to before where the remux on the TV begins (a remux started mid-title,
 * `TsStart`): the TV has nothing there, so the remux effect gets the remux from the start point
 * before it ready and hands it over -- the TV plays on meanwhile, the staged swap another audio
 * uses (`RemuxCastStart`) -- starting at the point asked for.
 *
 * Handed to the effect as a key ([generation]) and a value it takes once ([take]), rather than run
 * where the seek happens: the seek would otherwise carry everything `castRequestFor` reads into
 * every lambda that seeks, and PlayerContent is at ART's register limit.
 */
@Stable
internal class CastSeekBack {

    /** Bumped by every [request]: a key of the remux effect. */
    var generation by mutableIntStateOf(0)
        private set

    @Volatile private var pending: Long? = null

    /** Asks for the TV to go to [titleMs] (the title's clock). */
    fun request(titleMs: Long) {
        pending = titleMs
        generation++
    }

    /** The point asked for, once: a later run of the effect (another audio) does not seek again. */
    fun take(): Long? = pending.also { pending = null }
}
