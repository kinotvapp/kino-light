package com.arkiv.player.ui.player

import com.arkiv.player.cast.AudioTrackRef
import com.arkiv.player.cast.CastAudioChoice
import com.arkiv.player.cast.CastAudioRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What of the phone's audio menu a cast can carry, and what the menu says about it while casting. */
class CastAudioChoiceTest {

    private val formats = listOf(
        AudioTrackRef("1/257", "spa", null),
        AudioTrackRef("1/258", "eng", "English"),
        AudioTrackRef(null, "en", "Dub"),
    )

    @Test
    fun `the selected embedded track is what the cast carries`() {
        val choice = castAudioChoiceOf(selected = 1, menuSize = 3, embeddedCount = 2) { formats.getOrNull(it) }
        assertEquals(CastAudioChoice(1, "1/258", "eng", "English"), choice)
    }

    /** A plugin's side audio is a separate file merged in on the phone: not inside the TS the remux reads. */
    @Test
    fun `a side audio track cannot be cast`() {
        assertNull(castAudioChoiceOf(selected = 2, menuSize = 3, embeddedCount = 2) { formats.getOrNull(it) })
    }

    @Test
    fun `nothing selected or no tracks yet means the default audio`() {
        assertNull(castAudioChoiceOf(selected = -1, menuSize = 3, embeddedCount = 3) { formats.getOrNull(it) })
        assertNull(castAudioChoiceOf(selected = 0, menuSize = 0, embeddedCount = 0) { formats.getOrNull(it) })
        assertNull(castAudioChoiceOf(selected = 0, menuSize = 1, embeddedCount = 1) { null })
    }

    @Test
    fun `the menu explains what an audio change does on the TV only while casting`() {
        assertNull(castTracksNote(casting = false, route = CastAudioRoute.REMUX))
        val remux = castTracksNote(casting = true, route = CastAudioRoute.REMUX)
        val fixed = castTracksNote(casting = true, route = CastAudioRoute.FIXED)
        assertNotNull(remux)
        assertNotNull(fixed)
        assertTrue(remux!!.contains("desde donde ibas"))
        assertTrue(!remux.contains("desde el inicio"))
        assertTrue(fixed!!.contains("no permite cambiar el audio"))
    }
}
