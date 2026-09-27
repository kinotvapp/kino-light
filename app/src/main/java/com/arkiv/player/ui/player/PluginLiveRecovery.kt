package com.arkiv.player.ui.player

import com.arkiv.player.playback.InPlaceRecoveryBudget
import com.arkiv.player.playback.LiveErrorKind
import com.arkiv.player.playback.LiveReopenPolicy

/** What a plugin's live channel does when its player fails. See [pluginLiveRecovery]. */
internal enum class PluginLiveRecovery {
    /** Seek to the live edge and prepare again on the same player; the plugin is not called. */
    REJOIN_EDGE,

    /** Call the plugin's `resolve()` again with the same ref, after a short wait, and rebuild the player. */
    RE_RESOLVE,

    /** The reopen budget is spent: the person reads that the signal was cut. */
    GIVE_UP,
}

/**
 * The decision, pure: the same shape as Magis live (`LiveExoPlayer`'s in-place recovery, then
 * `reopenLiveAfterCut`'s bounded reopens), never the VOD error dialog on the first hiccup.
 *
 * A playlist-level error ([LiveErrorKind.recoverableInPlace]: behind the live window, playlist
 * reset or stuck) is fixed by re-joining the live edge while the in-place budget lasts
 * ([inPlaceLeft], `InPlaceRecoveryBudget`: a few tries a minute); past it, or for any other cut,
 * the channel is resolved again, up to [LiveReopenPolicy.MAX_REOPENS] times ([reopensSoFar]);
 * then the person is warned.
 */
internal fun pluginLiveRecovery(kind: LiveErrorKind, reopensSoFar: Int, inPlaceLeft: Boolean = true): PluginLiveRecovery = when {
    kind.recoverableInPlace && inPlaceLeft -> PluginLiveRecovery.REJOIN_EDGE
    reopensSoFar < LiveReopenPolicy.MAX_REOPENS -> PluginLiveRecovery.RE_RESOLVE
    else -> PluginLiveRecovery.GIVE_UP
}

/**
 * The per-channel state behind [pluginLiveRecovery] that `PlayerViewModel` keeps for the plugin
 * channel on screen: the in-place budget, how many reopens were spent, and whether the channel has
 * played long enough since the last one to get its budget back. Pure, so it can be pinned down here
 * (the ViewModel can't be instantiated in a JVM test).
 *
 * Health is measured by how far the clock ADVANCED since the reopen, not by the position itself: a
 * live window's clock starts wherever its edge is (30 s into a 30 s DVR window), so "position over
 * 5 s" would replenish the budget the instant a dead reopen reports its first reading.
 */
internal class PluginLiveReopens(private val nowMs: () -> Long = System::currentTimeMillis) {
    private val inPlace = InPlaceRecoveryBudget()

    /** Reopens spent since the channel last played healthily. */
    var count: Int = 0
        private set

    /** The first clock reading after the last reopen, or -1 before one arrives. */
    private var baseline = -1L

    /** What to do about an error of [kind] right now. A REJOIN_EDGE takes one in-place try. */
    fun decide(kind: LiveErrorKind): PluginLiveRecovery {
        val inPlaceLeft = kind.recoverableInPlace && inPlace.tryConsume(nowMs())
        return pluginLiveRecovery(kind, count, inPlaceLeft)
    }

    /** Spends one reopen and returns how long to wait before it (2 s, 4 s, 8 s). */
    fun reopen(): Long {
        count++
        baseline = -1L
        return LiveReopenPolicy.waitMs(count)
    }

    /** Every clock reading while the channel plays: [LiveReopenPolicy.MIN_HEALTHY_MS] of advance after a reopen replenishes the budget. */
    fun playing(positionMs: Long) {
        if (count == 0) return
        if (baseline < 0L) { baseline = positionMs; return }
        if (positionMs - baseline >= LiveReopenPolicy.MIN_HEALTHY_MS) reset()
    }

    /** A fresh channel (or a healthy one): nothing spent. */
    fun reset() {
        count = 0
        baseline = -1L
    }
}
