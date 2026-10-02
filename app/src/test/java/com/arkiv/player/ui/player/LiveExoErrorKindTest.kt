package com.arkiv.player.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoTimeoutException
import com.arkiv.player.playback.LiveErrorKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ERRORES-AKD (stuck playing) and ERRORES-AKG (surface detach timeout) on the live player. */
class LiveExoErrorKindTest {
    private fun unexpected(cause: RuntimeException) =
        ExoPlaybackException.createForUnexpected(cause, PlaybackException.ERROR_CODE_TIMEOUT)

    @Test
    fun `stuck playing with no progress is its own kind, other stuck types are not`() {
        assertEquals(LiveErrorKind.STUCK_PLAYING, liveErrorKind(unexpected(StuckPlayerException(StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, 10_000))))
        assertEquals(LiveErrorKind.OTHER, liveErrorKind(unexpected(StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NOT_LOADING, 600_000))))
    }

    @Test
    fun `only a surface detach timeout is the view going away`() {
        assertTrue(isSurfaceDetachTimeout(unexpected(ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_DETACH_SURFACE))))
        assertFalse(isSurfaceDetachTimeout(unexpected(ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_RELEASE))))
        assertFalse(isSurfaceDetachTimeout(unexpected(StuckPlayerException(StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, 10_000))))
    }

    @Test
    fun `a detach timeout neither reports nor reopens, and the player is released without waiting on the surface`() {
        val src = File("src/main/java/com/arkiv/player/ui/player/LiveExoPlayer.kt").readText()
        val onError = src.substringAfter("override fun onPlayerError").substringBefore("val kind = liveErrorKind(error)")
        val detach = onError.substringAfter("if (isSurfaceDetachTimeout(error))")
        assertFalse(detach.contains("Crash.report"))
        assertTrue(detach.contains("!disposed.get() && textureView.isAttachedToWindow"))
        val dispose = src.substringAfter("onDispose {").substringBefore("mirror.resetClock()")
        assertFalse(dispose.contains("exoPlayer.clearVideoTextureView"))
        assertTrue(dispose.indexOf("disposed.set(true)") in 0 until dispose.indexOf("exoPlayer.release()"))
    }
}
