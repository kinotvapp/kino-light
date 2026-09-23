package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What happens to whatever's playing when the person leaves the app. */
class PauseOnExitTest {

    @Test fun `on the TV everything pauses, the local player included`() {
        assertTrue(shouldPauseOnExit(isTv = true, isExoPlayer = true, casting = false))
        assertTrue(shouldPauseOnExit(isTv = true, isExoPlayer = false, casting = false))
    }

    /** Magis, live, and Caracol have no way to stop them from outside. */
    @Test fun `on the phone an ExoPlayer pauses`() {
        assertTrue(shouldPauseOnExit(isTv = false, isExoPlayer = true, casting = false))
    }

    /**
     * A downloaded file plays on the ExoPlayer hosted by `PlaybackService`, reached through the
     * screen's `controller`, so `isExoPlayer` is false for it: on the phone it keeps playing in the
     * background, with the media notification. This is the rule that keeps that working.
     */
    @Test fun `on the phone the service-hosted local player keeps playing`() {
        assertFalse(shouldPauseOnExit(isTv = false, isExoPlayer = false, casting = false))
        assertEquals(
            OnBackground.KEEP_PLAYING,
            onBackground(isTv = false, isExoPlayer = false, casting = false, isLive = false),
        )
    }

    @Test fun `casting to a Chromecast never pauses`() {
        for (tv in listOf(true, false)) for (exo in listOf(true, false)) {
            assertFalse("tv=$tv exo=$exo", shouldPauseOnExit(isTv = tv, isExoPlayer = exo, casting = true))
        }
    }

    @Test fun `a live channel on ExoPlayer stops and a video pauses`() {
        assertEquals(OnBackground.STOP_LIVE, onBackground(isTv = true, isExoPlayer = true, casting = false, isLive = true))
        assertEquals(OnBackground.STOP_LIVE, onBackground(isTv = false, isExoPlayer = true, casting = false, isLive = true))
        assertEquals(OnBackground.PAUSE, onBackground(isTv = true, isExoPlayer = true, casting = false, isLive = false))
        assertEquals(OnBackground.PAUSE, onBackground(isTv = false, isExoPlayer = true, casting = false, isLive = false))
        assertEquals(OnBackground.PAUSE, onBackground(isTv = true, isExoPlayer = false, casting = false, isLive = false))
        assertEquals(OnBackground.KEEP_PLAYING, onBackground(isTv = false, isExoPlayer = false, casting = false, isLive = false))
        assertEquals(OnBackground.KEEP_PLAYING, onBackground(isTv = true, isExoPlayer = true, casting = true, isLive = true))
    }

    /** A channel the person had paused can't start playing on its own on return. */
    @Test fun `on return, a live stream that was playing plays again and a paused one stays paused`() {
        assertEquals(OnReturnToLive.RESUME_LIVE, onReturnToLive(wasPlayingOnExit = true))
        assertEquals(OnReturnToLive.STAY_PAUSED, onReturnToLive(wasPlayingOnExit = false))
    }
}
