package com.arkiv.player.playback

/**
 * From where a reload should resume: the playlist's saved position, or the position the
 * controller's own clock is already sitting at.
 *
 * Exists because of the "jumped back 11m48s" bug measured on a Samsung S24+ on 2026-09-11:
 * pressing HOME left local playback running in the background (correctly), and coming back
 * through the notification recreated `PlayerScreen`. The freshly mounted screen's
 * `LaunchedEffect(playlist, generacionVivo)` re-ran with whatever `PlaylistData` the ViewModel's
 * StateFlow still held in memory -- which was the one from the *previous* mount, published
 * minutes earlier, because the fresh one (from the `LaunchedEffect(episodeId)` that calls
 * `vm.load` right after) hadn't landed yet. `MediaReusePolicy.decide` correctly said RELOAD --
 * the screen is new, see its KDoc for why that reload stays -- but reloading at that stale
 * playlist's `startPositionMs` threw away eleven minutes of real progress the controller had
 * already made while the screen was gone.
 *
 * The fix is neither "wait for the fresh playlist" (a reload must still happen promptly; ordering
 * cannot be relied on) nor "distrust the playlist" (a genuinely new episode has no live position
 * to fall back on) -- it's asking the controller itself. If it already holds THIS episode and its
 * clock has moved past zero, that clock is more current than anything a StateFlow can replay, no
 * matter how stale the replay turns out to be.
 *
 * Lives apart from the screen so it can be tested: it's the rule that decides which of two numbers
 * a reload resumes at, and that shouldn't depend on Compose, media3, or a StateFlow's timing.
 */
object ReloadPositionPolicy {

    /** Where the winning position came from -- for the log line, so a device trace can tell which one won. */
    enum class Source { LIVE, PLAYLIST }

    data class Decision(val positionMs: Long, val source: Source)

    /**
     * @param episodeId the episode the screen is about to reload.
     * @param currentMediaId what the controller currently holds as its playing item
     *   (`controller.currentMediaItem?.mediaId`), or null if nothing is loaded.
     * @param currentPositionMs the controller's own clock right now (`controller.currentPosition`).
     * @param playlistPositionMs the freshly-decided-or-not playlist's saved position for this
     *   episode (`PlaylistData.startPositionMs`).
     *
     * Only checks identity plus "moved past zero" -- never magnitude. Whether the live clock reads
     * ahead of or behind the playlist's value doesn't matter: nothing in this codebase can produce
     * an external position MORE current than the controller's own clock while that same controller
     * is still holding the episode and ticking. The DB write that eventually becomes
     * `startPositionMs` is always a snapshot of a past instant, so it can only lag the live clock,
     * never lead it, for one continuous playback session. An explicit "start over" action reaches
     * the player through a new episodeId or a new screen (`pantallaNueva`), i.e. through
     * `MediaReusePolicy.decide` returning RELOAD with `actualMediaId != episodeId` or a genuinely
     * new surface -- not through this race -- so it isn't affected by always trusting the live
     * clock here.
     *
     * Position 0 is treated as "nothing to trust yet" rather than "live", because a controller that
     * was just handed this episode (and hasn't started it) reads 0 too; in that case the playlist's
     * saved position is the only one that carries information.
     */
    fun resumePosition(
        episodeId: String,
        currentMediaId: String?,
        currentPositionMs: Long,
        playlistPositionMs: Long,
    ): Decision {
        if (currentMediaId == episodeId && currentPositionMs > 0L) {
            return Decision(currentPositionMs, Source.LIVE)
        }
        return Decision(playlistPositionMs, Source.PLAYLIST)
    }
}
