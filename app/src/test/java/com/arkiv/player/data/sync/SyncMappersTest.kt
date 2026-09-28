package com.arkiv.player.data.sync

import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.db.PlaybackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

private fun sampleEpisodeWithLocalPaths(): EpisodeEntity = EpisodeEntity(
    id = "magis:e:e1",
    itemId = "magis:c:i1",
    section = "temporada-1",
    displayName = "Episode 1",
    orderIndex = 0,
    durationSeconds = 1320.0,
    thumbPath = "/data/t.jpg",
    originalPath = "/data/x.mp4",
    originalFormat = "mp4",
    originalSize = 123456L,
    derivativePath = "/data/d.mp4",
    derivativeFormat = "mp4",
    derivativeSize = 654321L,
    torrentFileIndex = null,
    torrentData = null,
    updatedAt = 0,
    deleted = false,
)

class SyncMappersTest {
    @Test fun `playback round-trips through json`() {
        val p = PlaybackEntity("magis:c:e1", positionMs = 5000, durationMs = 60000, watched = false, lastPlayedAt = 111, updatedAt = 999, deleted = false)
        assertEquals(p, jsonToPlayback(playbackToJson(p)))
    }
    @Test fun `episode json omits device-local paths`() {
        // Build an EpisodeEntity with a local path set, map to json, confirm the path key is absent.
        val e = sampleEpisodeWithLocalPaths() // helper: originalPath="/data/x.mp4", thumbPath="/data/t.jpg"
        val json = episodeToJson(e)
        assertTrue(!json.has("originalPath") && !json.has("thumbPath") && !json.has("derivativePath"))
    }
    @Test fun `lww - strictly newer remote wins, tie keeps local`() {
        assertTrue(LwwMerge.pickWinner(localUpdatedAt = 5, remoteUpdatedAt = 6))
        assertTrue(!LwwMerge.pickWinner(localUpdatedAt = 6, remoteUpdatedAt = 6))
        assertTrue(!LwwMerge.pickWinner(localUpdatedAt = 7, remoteUpdatedAt = 6))
    }

    @Test fun `a live row from an older device has no provider and is xuper's`() {
        val fav = jsonToLiveFavorite(JSONObject().put("code", "c1").put("nombre", "RCN").put("numero", 5).put("updatedAt", 9L).put("deleted", false))
        assertEquals(LiveChannelKeys.XUPER, fav.provider)
        val recent = jsonToLiveRecent(JSONObject().put("code", "c1").put("nombre", "RCN").put("vistoAt", 3L).put("updatedAt", 9L))
        assertEquals(LiveChannelKeys.XUPER, recent.provider)
    }

    @Test fun `provider travels with favourites and recents`() {
        val p = LiveChannelKeys.pluginProvider("own-server")
        val fav = LiveFavoriteEntity("c1", "Canal Uno", 1, null, 7L, false, provider = p)
        assertEquals(fav, jsonToLiveFavorite(liveFavoriteToJson(fav)))
        val recent = LiveRecentEntity("c1", "Canal Uno", 3L, 7L, provider = p)
        assertEquals(recent, jsonToLiveRecent(liveRecentToJson(recent)))
    }
}
