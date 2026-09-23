package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

}
