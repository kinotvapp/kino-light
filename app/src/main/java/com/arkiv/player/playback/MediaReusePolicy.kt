package com.arkiv.player.playback

/** A media that's loaded (or about to be) seen as what matters for deciding: what it is and where it comes from. */
data class LoadedMedia(val mediaId: String, val uri: String)

/**
 * Whether the media already in the player works for what's being asked, or it has to reload.
 *
 * Lives apart from the screen so it can be tested: it's the rule that decides whether you get to
 * keep the buffer or start from zero, and it shouldn't depend on Compose or media3.
 */
object MediaReusePolicy {

    enum class Decision { REUSE_CURRENT, SKIP_IN_PLAYLIST, RELOAD, WAIT }

    /**
     * @param requested the episodeId `fresh` playlist was built with (see `PlaylistData.requested`).
     *   Not always [episodeId]: the ViewModel survives navigation between chapters, so as soon as
     *   the screen enters the new chapter what's published is still the PREVIOUS one's playlist
     *   until the source finishes resolving (seconds, on magis/web).
     */
    fun decide(
        episodeId: String,
        loaded: List<LoadedMedia>,
        currentMediaId: String?,
        fresh: List<LoadedMedia>,
        requested: String,
        /**
         * Whether this player screen is a DIFFERENT one than the one that left that media playing
         * -- i.e. whether the video surface is new.
         *
         * Exists because reusing a media with a new surface KILLS the decoder. Measured on the
         * Fire TV on 2026-08-13, counting over four logcat captures:
         *
         * ```
         *   REUSE_CURRENT   RELOAD   dead decoder
         *        0              7                0
         *        4              6                6
         *        2              2                2
         *        1              3                2
         * ```
         *
         * Zero reuses, zero deaths; and in the rest, two fatal errors for every reuse. This
         * device's HEVC decoder answers `err 0x80001005` (OMX_ErrorBadParameter), gets marked
         * `DecoderErrorFatal = 1` and a new one has to be created -- meanwhile the audio keeps
         * going and the screen stays BLACK for 2 to 6 s, with no spinner, because as far as the app
         * is concerned the video "already had a picture". Reloading, on the other hand, didn't fail
         * once in 18 trials.
         *
         * The whole reuse path isn't removed: it's still correct when the screen is the SAME one
         * (coming back from the overlay, switching tracks), where there's no new surface and
         * reloading would throw the buffer away for nothing.
         */
        newScreen: Boolean = false,
    ): Decision {
        // What arrived is for another episode: nothing to decide yet. Checked first, because
        // loading it would play the wrong episode from any source. See the test
        // `the_previous_chapter_s_playlist_waits`.
        if (requested != episodeId) return Decision.WAIT
        // The episode's identity is NOT enough to reuse: where it comes from has to be checked too.
        // A torrent is served on 127.0.0.1:<ephemeral port>, and `startStream()` kills the previous
        // server and opens another on a new port -- the same episodeId can be loaded pointing at a
        // port that's already dead. Reusing it left the player with nothing open: black screen with
        // the torrent downloading fine.
        val loadedUrl = loaded.firstOrNull { it.mediaId == episodeId }?.uri
        val freshUrl = fresh.firstOrNull { it.mediaId == episodeId }?.uri
        if (loadedUrl != null && loadedUrl != freshUrl) return Decision.RELOAD
        // With a new surface it reloads even if it's the same media: see [newScreen].
        if (currentMediaId == episodeId) {
            return if (newScreen) Decision.RELOAD else Decision.REUSE_CURRENT
        }
        if (loaded.isNotEmpty() && loaded.map { it.mediaId } == fresh.map { it.mediaId }) {
            return Decision.SKIP_IN_PLAYLIST
        }
        return Decision.RELOAD
    }
}
