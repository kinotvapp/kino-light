package com.arkiv.player.playback

/**
 * When the end of playback really is the end of the chapter.
 *
 * Players fire the same completion signal (media3's `Player.STATE_ENDED`, or libVLC's
 * `EndReached` before it) when the chapter finished as when the stream ran out of data: Magis's
 * CDN going quiet, or -- for a source removed in this branch's pruning -- a
 * torrent with no peers or a web URL that expired. The screen uses that signal to move to the next
 * chapter, so without telling the two apart a network hiccup mid-chapter turned into a skip -- and
 * the next chapter could stall the same way, cascading through the whole series.
 *
 * Lives apart from the screen so it can be tested, same as [MediaReusePolicy].
 */
object AutoAdvance {

    /** Final margin that counts as "it ended": the credits and the last frame almost never leave
     * the position stuck exactly at the duration. */
    private const val END_MARGIN_MS = 90_000L

    /** With no known duration, the minimum that had to play to avoid confusing an end with a failure. */
    private const val MIN_WITHOUT_DURATION_MS = 60_000L

    /**
     * @param positionMs last position known to the screen (it polls every 0.5 s). Deliberately not
     *   read from the player at the moment of the notice: on ending, the player can return 0.
     * @param durationMs chapter duration, or 0 if it couldn't be probed (happens on magis, where
     *   the probe sometimes loses the race against the CDN — there it's decided only by what
     *   actually played).
     */
    fun isEndOfChapter(positionMs: Long, durationMs: Long): Boolean =
        if (durationMs > 0) {
            positionMs >= durationMs - END_MARGIN_MS
        } else {
            positionMs >= MIN_WITHOUT_DURATION_MS
        }
}
