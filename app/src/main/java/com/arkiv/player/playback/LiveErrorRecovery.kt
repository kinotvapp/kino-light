package com.arkiv.player.playback

/** The live playback errors that a fresh look at the playlist fixes without opening the channel from scratch. */
internal enum class LiveErrorKind {
    /** The player fell behind the oldest segment the playlist still lists. */
    BEHIND_LIVE_WINDOW,

    /** The playlist went backwards or was replaced by an unrelated one. */
    PLAYLIST_RESET,

    /** The playlist stopped advancing for longer than the player tolerates. */
    PLAYLIST_STUCK,

    /** Anything else: the CDN said no, the connection dropped, the data was garbage. */
    OTHER,
    ;

    /** Seek to the live edge and prepare again, as ExoPlayer's own documentation asks for. */
    val recoverableInPlace: Boolean get() = this != OTHER
}

/**
 * How often the in-place recovery may be tried before the error is handed to the full reopen instead.
 *
 * Measured in GlitchTip: these three errors were ~40% of the "Source error" reports of live channels, and the app
 * answered every one by re-resolving the whole session (a 2 s wait, then up to 11 s to resolve, three tries, then "the
 * signal was cut"). Viewers reported cuts and a spinner that only went away by leaving and coming back. A channel
 * that keeps falling behind right after a recovery is a broken feed, not a hiccup: past [max] tries in [windowMs] it
 * is the reopen's job.
 */
internal class InPlaceRecoveryBudget(private val max: Int = MAX, private val windowMs: Long = WINDOW_MS) {

    private val recent = ArrayDeque<Long>()

    /** Takes one try if there is one left. */
    fun tryConsume(nowMs: Long): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first() > windowMs) recent.removeFirst()
        if (recent.size >= max) return false
        recent.addLast(nowMs)
        return true
    }

    companion object {
        const val MAX = 3
        const val WINDOW_MS = 60_000L
    }
}
