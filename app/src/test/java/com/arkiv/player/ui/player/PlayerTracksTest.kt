package com.arkiv.player.ui.player

import com.arkiv.player.playback.TrackSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule in the audio/subtitles menu that actually decides something: how a track with no
 * language gets labeled ([spuLabel]). It used to live inside `PlayerContent` as a local function
 * closed over its state, so until now it couldn't be called from here; it moved out to
 * `PlayerTracks.kt` just so it could be pinned down.
 */
class PlayerTracksTest {

    // ---- spuLabel ----

    private val tracks = listOf(0 to "Track 1", 1 to "Track 2")

    @Test
    fun `magis prefixes the declared language to the unnamed track`() {
        val label = spuLabel(
            id = 0, name = "Track 1", isMagis = true,
            declaredLanguages = listOf("es-419", "en"), spuTracks = tracks,
        )
        assertEquals("Español latino · Track 1", label)
    }

    @Test
    fun `the language is taken by track POSITION, not by its id`() {
        val label = spuLabel(
            id = 1, name = "Track 2", isMagis = true,
            declaredLanguages = listOf("es-419", "en"), spuTracks = tracks,
        )
        assertEquals("Inglés · Track 2", label)
    }

    /**
     * Outside magis the language list comes from another source and doesn't describe these
     * tracks: labeling them with it would be lying in the menu.
     */
    @Test
    fun `outside magis the name is left raw`() {
        val label = spuLabel(
            id = 0, name = "Track 1", isMagis = false,
            declaredLanguages = listOf("es-419"), spuTracks = tracks,
        )
        assertEquals("Track 1", label)
    }

    @Test
    fun `with no declared language for that position nothing is guessed`() {
        val label = spuLabel(
            id = 1, name = "Track 2", isMagis = true,
            declaredLanguages = listOf("es-419"), spuTracks = tracks,
        )
        assertEquals("Track 2", label)
    }

    @Test
    fun `an unrecognized code leaves the name raw`() {
        val label = spuLabel(
            id = 0, name = "Track 1", isMagis = true,
            declaredLanguages = listOf("zz"), spuTracks = tracks,
        )
        assertEquals("Track 1", label)
    }

    @Test
    fun `the synthetic Desactivar entry is never labeled`() {
        val label = spuLabel(
            id = -1, name = "Desactivar", isMagis = true,
            declaredLanguages = listOf("es-419"), spuTracks = tracks,
        )
        assertEquals("Desactivar", label)
    }
    /**
     * A fresh screen finds the service player still on the PREVIOUS download (it keeps playing in
     * the background), so its tracks must not fill this screen's menu nor spend its language pick.
     */
    @Test
    fun `the local player's tracks only count for this screen's episode`() {
        assertTrue(tracksBelongToEpisode("magis::ep1", "magis::ep1"))
        assertFalse(tracksBelongToEpisode("magis::ep0", "magis::ep1"))
        assertFalse(tracksBelongToEpisode(null, "magis::ep1"))
    }

    // ---- audioTrackLabel ----

    @Test
    fun `a plugin's own audio track is labeled from its declared language, not the raw fallback`() {
        val label = audioTrackLabel(
            id = 1, embeddedCount = 1,
            pluginTracks = listOf(ResolvedAudioTrack(lang = "es-419", url = "https://x/a.aac")),
            embeddedLabel = "A2",
        )
        assertEquals("Español latino", label)
    }

    @Test
    fun `an explicit label always wins over the language`() {
        val label = audioTrackLabel(
            id = 1, embeddedCount = 1,
            pluginTracks = listOf(ResolvedAudioTrack(lang = "en", url = "https://x/a.aac", label = "English (5.1)")),
            embeddedLabel = "A2",
        )
        assertEquals("English (5.1)", label)
    }

    @Test
    fun `an index inside the container's own tracks keeps the embedded label untouched`() {
        val label = audioTrackLabel(
            id = 0, embeddedCount = 1,
            pluginTracks = listOf(ResolvedAudioTrack(lang = "es-419", url = "https://x/a.aac")),
            embeddedLabel = "Pista original",
        )
        assertEquals("Pista original", label)
    }

    @Test
    fun `a blank or und language keeps the embedded fallback name`() {
        listOf("", "und", "UND").forEach { lang ->
            val label = audioTrackLabel(
                id = 0, embeddedCount = 0,
                pluginTracks = listOf(ResolvedAudioTrack(lang = lang, url = "https://x/a.aac")),
                embeddedLabel = "A1",
            )
            assertEquals(lang, "A1", label)
        }
    }

    @Test
    fun `an unrecognized language code is shown uppercased, not guessed`() {
        val label = audioTrackLabel(
            id = 0, embeddedCount = 0,
            pluginTracks = listOf(ResolvedAudioTrack(lang = "fr", url = "https://x/a.aac")),
            embeddedLabel = "A1",
        )
        assertEquals("FR", label)
    }

    @Test
    fun `with no plugin tracks at that position nothing changes`() {
        val label = audioTrackLabel(id = 5, embeddedCount = 1, pluginTracks = emptyList(), embeddedLabel = "A6")
        assertEquals("A6", label)
    }

    // ---- reusing the preferred-language picker over a menu that mixes embedded and plugin tracks ----

    @Test
    fun `the existing preferred-language picker chooses a plugin's own audio track by its declared language`() {
        val menu = listOf(
            0 to "Pista original",
            1 to audioTrackLabel(1, 1, listOf(ResolvedAudioTrack(lang = "es-419", url = "https://x/a.aac")), "A2"),
        )
        assertEquals(1, TrackSelector.select(menu, TrackSelector.DEFAULT_AUDIO, requireChoice = true))
    }

    @Test
    fun `with only one real track the picker leaves the player's default alone`() {
        val menu = listOf(0 to audioTrackLabel(0, 0, listOf(ResolvedAudioTrack(lang = "es-419", url = "https://x/a.aac")), "A1"))
        assertNull(TrackSelector.select(menu, TrackSelector.DEFAULT_AUDIO, requireChoice = true))
    }
}
