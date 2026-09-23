package com.arkiv.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spinner's condition. It used to be written TWICE —in the overlay that draws it and in the
 * log that diagnoses it— so they could go out of sync and the log would stop describing what's on
 * screen, which is exactly what it exists for.
 */
class PlayerSpinnerTest {

    private fun spinner(
        noPlaylist: Boolean = false,
        buffering: Boolean = false,
        noFirstFrame: Boolean = false,
        lostVideoOutput: Boolean = false,
        casting: Boolean = false,
    ) = shouldShowSpinner(noPlaylist, buffering, noFirstFrame, lostVideoOutput, casting)

    @Test
    fun `with no reason it does not show`() {
        assertFalse(spinner())
    }

    @Test
    fun `each reason on its own shows it`() {
        assertTrue(spinner(noPlaylist = true))
        assertTrue(spinner(buffering = true))
        assertTrue(spinner(noFirstFrame = true))
        assertTrue(spinner(lostVideoOutput = true))
    }

    /**
     * "Starts black with sound": libVLC already lets the audio through but hasn't given the first
     * frame yet, and there `playbackState` is NOT BUFFERING. Without this separate reason, the
     * screen was left with no spinner and no picture.
     */
    @Test
    fun `no first frame counts even while not buffering`() {
        assertTrue(spinner(buffering = false, noFirstFrame = true))
    }

    /**
     * While casting, waiting on the LOCAL video output makes no sense: the TV puts up the
     * picture, so that wait doesn't matter and will never arrive.
     */
    @Test
    fun `casting ignores the lost video output`() {
        assertTrue(spinner(lostVideoOutput = true, casting = false))
        assertFalse(spinner(lostVideoOutput = true, casting = true))
    }

    /**
     * The other three DO apply while casting: while the receiver loads, the local screen also has
     * to explain what's going on. Without this it was left with the gradient and the Chromecast
     * badge and nothing else -- no controls, no explanation.
     */
    @Test
    fun `while casting the other three reasons still apply`() {
        assertTrue(spinner(noPlaylist = true, casting = true))
        assertTrue(spinner(buffering = true, casting = true))
        assertTrue(spinner(noFirstFrame = true, casting = true))
    }
}
