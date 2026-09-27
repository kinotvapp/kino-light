package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * After `StreamExoPlayer` drops a failing side audio track and rebuilds the merge: the menu is
 * labelled against the tracks REALLY merged now, and the language the person was hearing is chosen
 * again on the rebuilt source (the old `TrackSelectionOverride` names groups that no longer exist).
 */
class AudioFallbackRepickTest {

    private val es = ResolvedAudioTrack(lang = "es-419", url = "https://cdn.example/es.m4a", label = "Español latino")
    private val en = ResolvedAudioTrack(lang = "en", url = "https://cdn.example/en.m4a", label = "English")

    // --- labels -------------------------------------------------------------------------------

    @Test fun `with every dub merged, the embedded track keeps its name and each dub gets its own`() {
        assertEquals(
            listOf(0 to "Japonés", 1 to "Español latino", 2 to "English"),
            audioMenu(listOf("Japonés", "A2", "A3"), listOf(es, en)),
        )
    }

    @Test fun `once en is dropped the menu is labelled against the active list, not the original`() {
        // Groups after the rebuild: [embedded, es]. Labelled against [es, en] (the bug) this read
        // [Español latino, English]: the original named after a dub, the dub after a dead one.
        assertEquals(
            listOf(0 to "Japonés", 1 to "Español latino"),
            audioMenu(listOf("Japonés", "A2"), listOf(es)),
        )
    }

    @Test fun `with every dub dropped the embedded tracks keep their own names`() {
        assertEquals(listOf(0 to "Japonés"), audioMenu(listOf("Japonés"), emptyList()))
    }

    // --- re-pick ------------------------------------------------------------------------------

    @Test fun `a rebuild re-applies the dub the person was hearing, by name, on the new groups`() {
        val repick = AudioFallbackRepick()
        repick.onReport(listOf(es, en), audioLabel = null, spuLabel = null)
        assertNull(repick.take(listOf(0 to "Japonés", 1 to "Español latino", 2 to "English")))

        // en fails: the source is rebuilt with [es]. ExoPlayer first reports no tracks at all...
        repick.onReport(listOf(es), audioLabel = "Español latino", spuLabel = "Español")
        assertNull(repick.take(emptyList()))
        // ...then the rebuilt ones: the menu is now labelled, so the choice can be found again.
        repick.onReport(listOf(es), audioLabel = null, spuLabel = null)
        val pending = repick.take(listOf(0 to "Japonés", 1 to "Español latino"))
        assertEquals(AudioFallbackRepick.Pending(audioLabel = "Español latino", spuLabel = "Español"), pending)
        assertEquals(1, trackIdByLabel(pending!!.audioLabel, listOf(0 to "Japonés", 1 to "Español latino")))
        // Consumed once: a later report (the person switching tracks) is theirs, not re-picked.
        assertNull(repick.take(listOf(0 to "Japonés", 1 to "Español latino")))
    }

    @Test fun `when the dub the person heard is the one that died, nothing matches and the preference decides`() {
        val repick = AudioFallbackRepick()
        repick.onReport(listOf(es, en), audioLabel = null, spuLabel = null)
        repick.onReport(listOf(en), audioLabel = "Español latino", spuLabel = null)
        val pending = repick.take(listOf(0 to "Japonés", 1 to "English"))!!
        assertNull(trackIdByLabel(pending.audioLabel, listOf(0 to "Japonés", 1 to "English")))
    }

    @Test fun `a second failure before the tracks come back keeps the first, correctly labelled choice`() {
        val repick = AudioFallbackRepick()
        repick.onReport(listOf(es, en), audioLabel = null, spuLabel = null)
        repick.onReport(listOf(es), audioLabel = "Español latino", spuLabel = null)
        repick.onReport(emptyList(), audioLabel = null, spuLabel = null)
        assertEquals("Español latino", repick.take(listOf(0 to "Japonés"))!!.audioLabel)
    }

    @Test fun `the same list reported again is no rebuild`() {
        val repick = AudioFallbackRepick()
        repick.onReport(listOf(es, en), audioLabel = null, spuLabel = null)
        repick.onReport(listOf(es, en), audioLabel = "English", spuLabel = null)
        assertNull(repick.take(listOf(0 to "Japonés", 1 to "Español latino", 2 to "English")))
    }

    @Test fun `a new playback starts clean`() {
        val repick = AudioFallbackRepick()
        repick.onReport(listOf(es, en), audioLabel = null, spuLabel = null)
        repick.reset()
        repick.onReport(listOf(es), audioLabel = "English", spuLabel = null)
        assertNull(repick.take(listOf(0 to "Japonés", 1 to "Español latino")))
    }

    @Test fun `a label is found by exact name only`() {
        val tracks = listOf(0 to "Japonés", 1 to "Español latino")
        assertEquals(0, trackIdByLabel("Japonés", tracks))
        assertNull(trackIdByLabel("Español", tracks))
        assertNull(trackIdByLabel(null, tracks))
    }
}
