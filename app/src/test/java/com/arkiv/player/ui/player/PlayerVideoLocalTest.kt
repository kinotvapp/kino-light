package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the screen tracks about the picture of the service-hosted local player. */
class PlayerVideoLocalTest {

    @Test fun `nothing loaded means no waiting at all`() {
        val v = LocalVideoState()
        v.onSurfaceAttached(nowMs = 1_000L)
        assertEquals(-1L, v.msSinceLoad(nowMs = 5_000L))
        assertEquals(-1L, v.msWithSurface(nowMs = 5_000L))
    }

    @Test fun `a load starts a new wait for the first frame`() {
        val v = LocalVideoState()
        v.onLoad(nowMs = 1_000L, prefersSoftware = false)
        v.onFirstFrame()
        assertTrue(v.renderedFirstFrame)
        v.onLoad(nowMs = 9_000L, prefersSoftware = true)
        assertFalse(v.renderedFirstFrame)
        assertTrue(v.loadPrefersSoftware)
        assertEquals(1_000L, v.msSinceLoad(nowMs = 10_000L))
    }

    /** Coming back from the background: the decoder only had a surface since the return. */
    @Test fun `the wait with a surface counts from the later of load and attach`() {
        val v = LocalVideoState()
        v.onSurfaceAttached(nowMs = 500L)
        v.onLoad(nowMs = 1_000L, prefersSoftware = false)
        assertEquals(4_000L, v.msWithSurface(nowMs = 5_000L))
        v.onSurfaceDetached()
        assertFalse(v.hasSurface)
        assertEquals(-1L, v.msWithSurface(nowMs = 20_000L))
        v.onSurfaceAttached(nowMs = 20_000L)
        assertEquals(2_000L, v.msWithSurface(nowMs = 22_000L))
    }

    /** `waitingForVideo`: after re-attaching, only a frame on the NEW surface ends the wait. */
    @Test fun `painted since attach needs a frame after the attach`() {
        val v = LocalVideoState()
        v.onLoad(nowMs = 0L, prefersSoftware = false)
        v.onSurfaceAttached(nowMs = 0L)
        v.onFirstFrame()
        v.onSurfaceDetached()
        v.onSurfaceAttached(nowMs = 30_000L)
        assertFalse(v.paintedSinceAttach)
        v.onFirstFrame()
        assertTrue(v.paintedSinceAttach)
    }

    @Test fun `aspect is width times pixel ratio over height, and unknown sizes are ignored`() {
        val v = LocalVideoState()
        v.onVideoSize(width = 1280, height = 534, pixelWidthHeightRatio = 1f)
        assertEquals(1280f / 534f, v.aspect, 0.0001f)
        v.onVideoSize(width = 0, height = 0, pixelWidthHeightRatio = 1f)
        assertEquals(1280f / 534f, v.aspect, 0.0001f)
    }
}
