package com.arkiv.player.cast

import com.arkiv.player.playback.RemuxPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cast carries ONE audio track, the one the phone has on. Pinned here because getting it
 * wrong is silent: the TV simply plays another language than the phone's menu shows.
 */
class CastAudioTest {

    /** A Magis transport stream as the phone's player lists it: ids are `<program>/<PID>`. */
    private val magis = listOf(
        AudioTrackRef("1/257", "spa", null),
        AudioTrackRef("1/258", "eng", null),
        AudioTrackRef("1/259", "por", null),
    )

    private fun choice(ordinal: Int, ref: AudioTrackRef) =
        CastAudioChoice(ordinal, ref.id, ref.language, ref.label)

    @Test
    fun `the remux finds the phone's track by its id`() {
        assertEquals(1, CastAudio.indexIn(magis, choice(1, magis[1])))
    }

    /** The id wins over the position: an upstream merge can shift the phone's order. */
    @Test
    fun `the id wins over a shifted position`() {
        val shifted = CastAudioChoice(ordinal = 0, id = "1/258", language = "eng", label = null)
        assertEquals(1, CastAudio.indexIn(magis, shifted))
    }

    @Test
    fun `without ids the position is used when the language agrees`() {
        val noIds = magis.map { it.copy(id = null) }
        assertEquals(2, CastAudio.indexIn(noIds, CastAudioChoice(2, null, "por", null)))
    }

    @Test
    fun `a position that contradicts the language gives way to the language`() {
        val noIds = magis.map { it.copy(id = null) }
        // Position 0 is Spanish, but the phone chose English: the English track, not the Spanish.
        assertEquals(1, CastAudio.indexIn(noIds, CastAudioChoice(0, null, "en", null)))
    }

    @Test
    fun `two and three letter codes and regions name the same language`() {
        val tracks = listOf(AudioTrackRef(null, "en", null), AudioTrackRef(null, "es-419", null))
        assertEquals(1, CastAudio.indexIn(tracks, CastAudioChoice(5, null, "spa", null)))
    }

    @Test
    fun `tracks with no language are matched by position`() {
        val tracks = listOf(AudioTrackRef(null, null, null), AudioTrackRef(null, "und", null))
        assertEquals(1, CastAudio.indexIn(tracks, CastAudioChoice(1, null, null, null)))
    }

    @Test
    fun `nothing to find means the default selection`() {
        assertNull(CastAudio.indexIn(magis, null))
        assertNull(CastAudio.indexIn(emptyList(), choice(0, magis[0])))
        assertNull(CastAudio.indexIn(magis, CastAudioChoice(9, "2/300", "jpn", null)))
    }

    @Test
    fun `changing audio on a remuxed cast remuxes and reloads`() {
        assertEquals(
            CastAudioSwitch.REMUX_AND_RELOAD,
            CastAudio.onChoiceChanged(casting = true, route = CastAudioRoute.REMUX, onTv = 0, wanted = 1),
        )
    }

    @Test
    fun `a cast the receiver fetches as it is can only change on the phone`() {
        assertEquals(
            CastAudioSwitch.PHONE_ONLY,
            CastAudio.onChoiceChanged(casting = true, route = CastAudioRoute.FIXED, onTv = 0, wanted = 1),
        )
    }

    @Test
    fun `the same audio or no cast does nothing`() {
        assertEquals(CastAudioSwitch.NONE, CastAudio.onChoiceChanged(true, CastAudioRoute.REMUX, 1, 1))
        assertEquals(CastAudioSwitch.NONE, CastAudio.onChoiceChanged(false, CastAudioRoute.REMUX, 0, 1))
    }

    /** Served as HLS, the new audio's remux starts where the TV was, not over from the start. */
    @Test
    fun `a reloaded remux keeps the position`() {
        assertEquals(3_600_000L, CastAudio.reloadStartMs(CastAudioRoute.REMUX, 3_600_000L))
        assertEquals(42_000L, CastAudio.reloadStartMs(CastAudioRoute.FIXED, 42_000L))
        assertEquals(0L, CastAudio.reloadStartMs(CastAudioRoute.REMUX, -5L))
    }

    @Test
    fun `each audio is its own remux file`() {
        val cdn = "https://cdn.example/x_media.ts"
        val a0 = RemuxPolicy.keyFrom(cdn, 0L, 0)
        val a1 = RemuxPolicy.keyFrom(cdn, 0L, 1)
        assertNotEquals(a0, a1)
        assertNotEquals(RemuxPolicy.fileName(a0), RemuxPolicy.fileName(a1))
        // No audio keeps the old key, and the start point still reads back with an audio in it.
        assertEquals(cdn, RemuxPolicy.keyFrom(cdn, 0L, null))
        assertEquals(90_000L, RemuxPolicy.fromInKey(RemuxPolicy.keyFrom(cdn, 90_000L, 2)))
        assertEquals(0L, RemuxPolicy.fromInKey(a1))
    }
}
