package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edges of the "no first frame yet" spinner.
 *
 * The good case (covering black with sound) is easy; what has to be pinned by test are the two
 * failure modes, which are worse than the original problem: leaving the spinner on top of a video
 * that IS playing, and leaving it up forever on something that will never have a picture.
 */
class FirstFrameWaitTest {

    @Test
    fun `just loaded and no tracks yet, it waits`() {
        // The exact instant this came to cover: libVLC opening, every count at 0 -which isn't "no
        // video", it's "don't know yet"- and the screen black.
        assertTrue(
            FirstFrameWait.shouldWait(
                loadedMsAgo = 300, hadFrame = false,
                videoTracks = 0, audioTracks = 0,
            ),
        )
    }

    @Test
    fun `with audio playing and video declared but no picture, it waits`() {
        // Black WITH SOUND proper: tracks already exist, audio came out, the picture didn't.
        assertTrue(
            FirstFrameWait.shouldWait(
                loadedMsAgo = 2_000, hadFrame = false,
                videoTracks = 2, audioTracks = 3,
            ),
        )
    }

    @Test
    fun `audio only content waits for no image at all`() {
        // If it waited, the spinner would stay up forever over something that sounds fine.
        assertFalse(
            FirstFrameWait.shouldWait(
                loadedMsAgo = 2_000, hadFrame = false,
                videoTracks = 0, audioTracks = 2,
            ),
        )
    }

    @Test
    fun `a previous frame wins, even if there is no video output right now`() {
        // Losing the output AFTER already having a frame is the other case, and it's covered by
        // `waitingForVideo` in PlayerScreen (coming back from the background). This one stays out of it.
        assertFalse(
            FirstFrameWait.shouldWait(
                loadedMsAgo = 2_000, hadFrame = true,
                videoTracks = 2, audioTracks = 3,
            ),
        )
    }

    @Test
    fun `past the cap, whatever there is gets shown`() {
        assertFalse(
            FirstFrameWait.shouldWait(
                loadedMsAgo = FirstFrameWait.CAP_MS + 1, hadFrame = false,
                videoTracks = 2, audioTracks = 3,
            ),
        )
    }

    @Test
    fun `the cap holds out longer than the software rescue`() {
        // The "hardware with no picture -> software" rescue takes 12 s to fire and the software
        // reload is what ends up giving a picture. A shorter cap would pull the spinner right at
        // the worst moment: plain black during the rescue.
        assertTrue(FirstFrameWait.CAP_MS > 12_000L * 2)
    }

    @Test
    fun `with no media loaded there is nothing to wait for`() {
        assertFalse(
            FirstFrameWait.shouldWait(
                loadedMsAgo = -1, hadFrame = false,
                videoTracks = 0, audioTracks = 0,
            ),
        )
    }

    // ---- The resume seek ----

    /**
     * MEASURED ON THE FIRE TV on 2026-08-14, resuming Dragon Ball E139 at 22:07:
     *
     * ```
     * 09:14:50.150  loadMedia start=1327653ms
     * 09:14:50.319  ← pide rango=bytes=0-              ← VLC opens at BYTE 0
     * 09:14:51.155  ⏱ abrió en 1025ms → primera imagen ← ...from the START of the chapter
     * 09:14:51.448  PAUSA (buffering) en pos=0ms
     * 09:14:52.951  REANUDO tras 1501ms (pos=1327116ms)
     * ```
     *
     * `:start-time` does NOT open at the saved minute: VLC opens at 0, pulls a frame from there,
     * and ONLY THEN seeks. That first frame turns on `hadFrame`, the spinner switches off -- and
     * the user is left staring at a FROZEN FRAME FROM THE START for 1.5 s while the audio is
     * already playing. It looks exactly like a freeze, and on top of that shows the wrong content.
     *
     * The original player covers this same gap: its seek turns the spinner on the instant it's
     * requested (`A0` -> `buffering/show`, in the decompiled app).
     */
    @Test
    fun `a frame from the start does not cancel the wait if the resume point has not been reached`() {
        assertTrue(
            FirstFrameWait.shouldWait(
                loadedMsAgo = 1_300, hadFrame = true,
                videoTracks = 1, audioTracks = 2,
                requestedMs = 1_327_653, positionMs = 0,
            ),
        )
    }

    /** Already reached the requested point: the wait is over, even by a small margin. */
    @Test
    fun `once the resume point is reached the wait ends`() {
        assertFalse(
            FirstFrameWait.shouldWait(
                loadedMsAgo = 2_900, hadFrame = true,
                videoTracks = 1, audioTracks = 2,
                requestedMs = 1_327_653, positionMs = 1_327_116,
            ),
        )
    }

    /** With no resume requested (starting from zero) none of this applies: the usual rule wins. */
    @Test
    fun `with no resume requested the first frame still cancels the wait`() {
        assertFalse(
            FirstFrameWait.shouldWait(
                loadedMsAgo = 1_300, hadFrame = true,
                videoTracks = 1, audioTracks = 2,
                requestedMs = 0, positionMs = 0,
            ),
        )
    }

    /**
     * The cap wins regardless. If the seek never lands -the case this spinner CANNOT fix- the
     * screen has to be given back at some point instead of spinning forever.
     */
    @Test
    fun `the cap cuts the wait short even if the requested point is never reached`() {
        assertFalse(
            FirstFrameWait.shouldWait(
                loadedMsAgo = FirstFrameWait.CAP_MS + 1, hadFrame = true,
                videoTracks = 1, audioTracks = 2,
                requestedMs = 1_327_653, positionMs = 0,
            ),
        )
    }

    /**
     * Landing a bit early counts as arrived. The seek lands on the keyframe BEFORE the requested
     * point, so requiring `position >= requested` would leave the spinner sitting over a video that
     * already started fine: in the measurement above it landed 537 ms before the requested point.
     */
    @Test
    fun `landing a bit before the requested point counts as arrived`() {
        assertFalse(
            FirstFrameWait.shouldWait(
                loadedMsAgo = 2_900, hadFrame = true,
                videoTracks = 1, audioTracks = 2,
                requestedMs = 1_327_653, positionMs = 1_320_000,
            ),
        )
    }

    /**
     * The composed decision this call site relies on: with a resume requested and no frame yet, the
     * spinner stays up (there's nothing to check against a landed position); once the player's
     * position reaches it, it clears. Pins `PlayerScreen.localWaitsForFirstFrame`'s wiring of
     * `requestedMs`/`positionMs` from the playlist's start position and the controller's clock.
     */
    @Test
    fun `with no frame and a resume requested the wait holds until the position is reached`() {
        assertTrue(
            FirstFrameWait.shouldWait(
                loadedMsAgo = 500, hadFrame = false,
                videoTracks = 0, audioTracks = 0,
                requestedMs = 1_327_653, positionMs = 0,
            ),
        )
        assertFalse(
            FirstFrameWait.shouldWait(
                loadedMsAgo = 2_900, hadFrame = true,
                videoTracks = 1, audioTracks = 2,
                requestedMs = 1_327_653, positionMs = 1_327_653,
            ),
        )
    }
}
