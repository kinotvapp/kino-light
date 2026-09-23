package com.arkiv.player.ui.player

import com.arkiv.player.playback.AdultContent
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdultContent]'s guard applied to playback progress.
 *
 * `saveProgress` receives a bare `episodeId` -- what the player knows about itself every ~5 s --
 * not an item, so the question "is this adult content?" has to be answered against the playlist
 * that's currently playing. That lookup lives out here, and not inside the ViewModel, for the same
 * reason [AdultContent] lives outside `savePlayback`: it's where its edges can be pinned down, and
 * the edge that matters is the one that's NOT obvious -- what happens when the episode isn't even
 * in the playlist.
 */
class AdultProgressTest {

    private fun item(episodeId: String, adult: Boolean) = PlayerData(
        episodeId = episodeId,
        itemId = episodeId,
        title = "",
        subtitle = "",
        mediaUrl = "",
        castUrl = null,
        artworkUrl = "",
        openingStartMs = null,
        openingEndMs = null,
        endingStartMs = null,
        kind = SourceKind.MAGIS,
        adult = adult,
    )

    private fun playlist(vararg items: PlayerData) =
        PlaylistData(items.toList(), startIndex = 0, startPositionMs = 0L, requested = items.first().episodeId)

    @Test fun `normal content's progress gets logged`() {
        val list = playlist(item("magis:1", adult = false))
        assertTrue(list.shouldLogHistory("magis:1"))
    }

    @Test fun `adult content's progress does not get logged`() {
        val list = playlist(item("magis:xxx", adult = true))
        assertFalse(list.shouldLogHistory("magis:xxx"))
    }

    /**
     * Asks about THE episode that's playing, not the whole playlist. An adult series can't poison
     * the progress of whatever comes next in the same queue, nor the other way around: a single
     * marked chapter is enough for that chapter alone to not be logged.
     */
    @Test fun `in a mixed playlist the episode being asked about wins`() {
        val list = playlist(item("magis:normal", adult = false), item("magis:18", adult = true))
        assertTrue(list.shouldLogHistory("magis:normal"))
        assertFalse(list.shouldLogHistory("magis:18"))
    }

    /**
     * THE EDGE THAT MATTERS, and it goes the same direction as [AdultContent.shouldLog]: if the
     * episode isn't in the playlist, it's unknown, and what's unknown GETS LOGGED.
     *
     * Happens for real and all the time: the ViewModel survives navigation between chapters and
     * `_playlist` keeps publishing the previous chapter's while the new source resolves (see
     * [PlaylistData.requested]'s KDoc). If one of those windows were read as "it's adult", normal
     * movies' progress would silently stop being saved.
     */
    @Test fun `if the episode is not in the playlist, it gets logged`() {
        val list = playlist(item("magis:1", adult = true))
        assertTrue(list.shouldLogHistory("magis:otro"))
    }

    /** With no playlist yet (startup, or right after a `_playlist.value = null`): it gets logged. */
    @Test fun `with no playlist, it gets logged`() {
        assertTrue((null as PlaylistData?).shouldLogHistory("magis:1"))
    }
}
