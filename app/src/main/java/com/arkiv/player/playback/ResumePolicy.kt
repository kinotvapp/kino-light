package com.arkiv.player.playback

/**
 * Where to resume an episode from what was saved.
 *
 * Lives apart from the ViewModel so it can be tested: it's the rule that decides whether opening
 * something puts you back where you left off or at zero again, and that shouldn't depend on
 * anything from Android.
 */
object ResumePolicy {

    /** Below this it doesn't count as having started watching: opening and backing out leaves no mark. */
    private const val MIN_MS = 10_000L

    /** Past this it's considered watched; resuming there would drop you into the credits. */
    private const val NEARLY_FINISHED = 0.9

    /**
     * @param savedPositionMs saved position.
     * @param savedDurationMs saved duration, or 0 if unknown.
     *
     * Deliberately does NOT check the download's state. With torrent (source removed in this
     * branch's pruning) it was needed: without it the position was discarded if that zone hadn't
     * downloaded yet, and since it never had in a freshly-opened magnet, playback ALWAYS started
     * from the beginning until the streaming server learned to anchor the sequential download at
     * the requested point. Magis and Ditu don't have that problem -they're pure streaming, there's
     * no "zone not downloaded yet" to check-, so today the parameter simply isn't needed.
     */
    fun startPosition(savedPositionMs: Long, savedDurationMs: Long): Long {
        if (savedPositionMs <= MIN_MS) return 0L
        if (savedDurationMs > 0L && savedPositionMs >= savedDurationMs * NEARLY_FINISHED) return 0L
        return savedPositionMs
    }
}
