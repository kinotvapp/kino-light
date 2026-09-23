package com.arkiv.player.playback

import com.arkiv.player.data.DituEntities
import com.arkiv.player.data.ditu.DituChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The path a Caracol live channel takes to the player. See [DituLive]'s KDoc. */
class DituLiveTest {

    private val channelOne = DituChannel(channelId = 1, name = "Caracol TV", logoUrl = "", assetId = 11)
    private val channelTwo = DituChannel(channelId = 2, name = "Noticias Caracol", logoUrl = "", assetId = 22)

    /** Without this `PlayerViewModel.load` wouldn't route it to `loadDitu` and the channel wouldn't play. */
    @Test fun `a channel's id is Caracol's`() {
        val id = DituLive.leave(channelOne)

        assertTrue(DituLive.isLive(id))
        assertEquals(SourceKind.DITU, PlayerSource.kindFor(id))
    }

    @Test fun `the channel that was left is taken with its id`() {
        val id = DituLive.leave(channelOne)

        assertEquals(channelOne, DituLive.take(id))
    }

    /** A live channel can't reopen an old channel, nor can a library episode find a channel. */
    @Test fun `a different id returns nothing`() {
        val oldId = DituLive.leave(channelTwo)
        DituLive.leave(channelOne)

        assertNull(DituLive.take(oldId))
        assertNull(DituLive.take("ditu:42::0"))
    }

    /**
     * Reloading on an expired token asks for the channel again with the same id. If [DituLive.take]
     * cleared it, the second time wouldn't find it and the live channel would die when the token
     * expired.
     */
    @Test fun `take does not clear it`() {
        val id = DituLive.leave(channelOne)

        DituLive.take(id)

        assertEquals(channelOne, DituLive.take(id))
    }

    /** Something saved from Caracol in the library can't enter through the live branch. */
    @Test fun `a library id is not a live channel`() {
        val movie = DituEntities.movieEpisodeId(DituEntities.itemIdFor("42"))
        val chapter = DituEntities.episodeIdFor(DituEntities.itemIdFor("99"), 1)

        assertFalse(DituLive.isLive(movie))
        assertFalse(DituLive.isLive(chapter))
    }
}
