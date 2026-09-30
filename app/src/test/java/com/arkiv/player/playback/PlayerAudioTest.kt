package com.arkiv.player.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerAudioTest {

    @Test
    fun `video is played as movie media, which is what makes other apps' audio yield`() {
        assertEquals(C.USAGE_MEDIA, KINO_VIDEO_AUDIO.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, KINO_VIDEO_AUDIO.contentType)
    }

    /**
     * ExoPlayer does NOT ask for audio focus unless told to, so a player built without it plays over another app's
     * music (reported on a phone: music kept going under a film). Every `ExoPlayer.Builder(` in the app must ask.
     */
    @Test
    fun `every ExoPlayer the app builds asks for audio focus`() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("source tree not found from ${File(".").absolutePath}", sources.isNotEmpty())
        val withoutFocus = sources
            .filter { it.readText().contains("ExoPlayer.Builder(") }
            .filterNot { file ->
                val text = file.readText()
                text.contains(".withAudioFocus()") || text.contains("handleAudioFocus")
            }
            .map { it.name }
        assertEquals("players built without audio focus: $withoutFocus", emptyList<String>(), withoutFocus)
    }
}
