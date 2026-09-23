package com.arkiv.player.ui.player

import androidx.media3.common.MediaItem
import com.arkiv.player.playback.DecoderWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decoder watchdog's rescue EFFECT, not its decision (see `DecoderWatchdogTest` for that):
 * `stop()` BEFORE `setMediaItems()`, then `prepare()`, with the position preserved. This ordering was
 * a Critical review finding -- without it, media3 1.5.1 keeps the hardware codec and the
 * software-first reload is a silent no-op. See [reloadInSoftware]'s KDoc for the mechanism.
 */
class SoftwareReloadTest {

    private class RecordingPlayer : SoftwareReloadPlayer {
        val calls = mutableListOf<String>()
        override var playWhenReady: Boolean = false

        override fun stop() {
            calls += "stop"
        }

        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            calls += "setMediaItems(index=$startIndex, position=$startPositionMs)"
        }

        override fun prepare() {
            calls += "prepare"
        }
    }

    private fun item() = MediaItem.Builder().setMediaId("ep1").build()

    @Test
    fun `stop happens before setMediaItems, then prepare, with the position preserved`() {
        val player = RecordingPlayer()

        reloadInSoftware(player, mediaItems = listOf(item()), startIndex = 2, startPositionMs = 45_000L)

        assertEquals(
            listOf("stop", "setMediaItems(index=2, position=45000)", "prepare"),
            player.calls,
        )
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `it fires at most once per load, chained to the same guard DecoderWatchdog pins`() {
        val player = RecordingPlayer()
        var alreadySoftware = false

        fun maybeReload(waitingMs: Long) {
            val shouldReload = DecoderWatchdog.shouldReloadInSoftware(
                waitingMs = waitingMs,
                renderedFirstFrame = false,
                videoTracks = 1,
                wantsToPlay = true,
                hasSurface = true,
                hasError = false,
                alreadySoftware = alreadySoftware,
            )
            if (!shouldReload) return
            reloadInSoftware(player, listOf(item()), startIndex = 0, startPositionMs = 1_000L)
            alreadySoftware = true
        }

        // Three polls past the bound, on the same load: only the first should actually reload.
        maybeReload(DecoderWatchdog.NO_FRAME_MS)
        maybeReload(DecoderWatchdog.NO_FRAME_MS + 5_000L)
        maybeReload(DecoderWatchdog.NO_FRAME_MS + 20_000L)

        assertEquals(
            listOf("stop", "setMediaItems(index=0, position=1000)", "prepare"),
            player.calls,
        )
    }
}
