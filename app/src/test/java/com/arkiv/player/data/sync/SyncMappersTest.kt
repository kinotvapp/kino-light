package com.arkiv.player.data.sync

import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.db.PlaybackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ---- live rows: identity travels as the liveCode in `code` (see liveFavoriteToJson) ----

    private val plugin = LiveChannelKeys.pluginProvider("own-server")

    private fun favJson(code: String) =
        JSONObject().put("code", code).put("nombre", "Canal").put("numero", 1).put("updatedAt", 9L).put("deleted", false)

    @Test fun `an outgoing xuper row keeps its bare code and a plugin row carries its live code`() {
        assertEquals("c1", liveFavoriteToJson(LiveFavoriteEntity("c1", "RCN", 5, null, 7L, false)).getString("code"))
        assertEquals("plugin:own-server:c1", liveFavoriteToJson(LiveFavoriteEntity("c1", "Canal Uno", 1, null, 7L, false, provider = plugin)).getString("code"))
        assertEquals("c1", liveRecentToJson(LiveRecentEntity("c1", "RCN", 3L, 7L)).getString("code"))
        assertEquals("plugin:own-server:c1", liveRecentToJson(LiveRecentEntity("c1", "Canal Uno", 3L, 7L, provider = plugin)).getString("code"))
    }

    @Test fun `a live row from an older device has a bare code and is xuper's`() {
        val fav = jsonToLiveFavorite(JSONObject().put("code", "c1").put("nombre", "RCN").put("numero", 5).put("updatedAt", 9L).put("deleted", false))!!
        assertEquals(LiveChannelKeys.XUPER to "c1", fav.provider to fav.code)
        val recent = jsonToLiveRecent(JSONObject().put("code", "c1").put("nombre", "RCN").put("vistoAt", 3L).put("updatedAt", 9L))!!
        assertEquals(LiveChannelKeys.XUPER to "c1", recent.provider to recent.code)
    }

    @Test fun `an incoming plugin live code is the plugin's row whatever a provider field says`() {
        for (json in listOf(favJson("plugin:own-server:c1"), favJson("plugin:own-server:c1").put("provider", "xuper"))) {
            val fav = jsonToLiveFavorite(json)!!
            assertEquals(plugin to "c1", fav.provider to fav.code)
        }
        val recent = jsonToLiveRecent(JSONObject().put("code", "plugin:own-server:c1").put("nombre", "Canal").put("vistoAt", 3L).put("updatedAt", 9L))!!
        assertEquals(plugin to "c1", recent.provider to recent.code)
    }

    @Test fun `provider and code round-trip for favourites and recents`() {
        for (fav in listOf(LiveFavoriteEntity("c1", "RCN", 5, "l", 7L, true), LiveFavoriteEntity("c1", "Canal Uno", 1, null, 7L, false, provider = plugin))) {
            assertEquals(fav, jsonToLiveFavorite(liveFavoriteToJson(fav)))
        }
        for (recent in listOf(LiveRecentEntity("c1", "RCN", 3L, 7L), LiveRecentEntity("c1", "Canal Uno", 3L, 7L, provider = plugin))) {
            assertEquals(recent, jsonToLiveRecent(liveRecentToJson(recent)))
        }
    }

    @Test fun `malformed live codes are refused, never stored as xuper's`() {
        for (code in listOf("", "plugin:own-server", "plugin::c1", "plugin:own-server:a:b", "plugin:")) {
            assertNull(code, jsonToLiveFavorite(favJson(code)))
            assertNull(code, jsonToLiveRecent(JSONObject().put("code", code).put("nombre", "X").put("vistoAt", 3L).put("updatedAt", 9L)))
        }
        assertNull(jsonToLiveFavorite(JSONObject().put("nombre", "no code").put("updatedAt", 9L)))
    }

    /**
     * A pre-v33 peer (staggered OTA: a paired phone and TV on different builds is normal). Its
     * mappers read and write only the v32 keys and its tables are keyed by bare `code`, modelled
     * here as a map `code -> row`, with its v32 LWW.
     */
    private class OldPeer {
        val favorites = mutableMapOf<String, JSONObject>()
        private val v32Keys = listOf("code", "nombre", "numero", "logo", "updatedAt", "deleted")
        private fun v32(json: JSONObject) = JSONObject().also { out -> v32Keys.filter(json::has).forEach { out.put(it, json.get(it)) } }
        fun apply(json: JSONObject) {
            val row = v32(json)
            val local = favorites[row.getString("code")]
            if (local == null || row.getLong("updatedAt") > local.getLong("updatedAt")) favorites[row.getString("code")] = row
        }
        fun push(code: String): JSONObject = v32(favorites.getValue(code))
    }

    @Test fun `an older peer keeps a plugin favourite apart from its own xuper one and echoes it back intact`() {
        val oldPeer = OldPeer()
        oldPeer.apply(liveFavoriteToJson(LiveFavoriteEntity("c1", "RCN", 5, null, 10L, false)))
        val pluginFav = LiveFavoriteEntity("c1", "Canal Uno", 1, null, 20L, true, provider = plugin)

        oldPeer.apply(liveFavoriteToJson(pluginFav))

        // Its own Xuper favourite is untouched: the plugin row landed under a code no portal channel has.
        assertEquals("RCN", oldPeer.favorites.getValue("c1").getString("nombre"))
        assertEquals(false, oldPeer.favorites.getValue("c1").getBoolean("deleted"))
        assertEquals(setOf("c1", "plugin:own-server:c1"), oldPeer.favorites.keys)
        // And its echo, with no provider field at all, comes back as the same plugin row.
        assertEquals(pluginFav, jsonToLiveFavorite(oldPeer.push("plugin:own-server:c1")))
        assertEquals(LiveFavoriteEntity("c1", "RCN", 5, null, 10L, false), jsonToLiveFavorite(oldPeer.push("c1")))
    }

    private val ownSource = OwnLiveSourceEntity(
        id = "3f1c2b7e-aaaa-bbbb-cccc-000000000001", kind = "PLAYLIST", name = "Mi lista",
        url = "http://tv.example.com/get.php?u=ana&p=1", epgUrl = "http://tv.example.com/epg.xml",
        userAgent = "VLC/3.0.20", refreshHours = 6, updatedAt = 77L, deleted = false,
    )

    @Test fun `an own live source round-trips, tombstone included`() {
        assertEquals(ownSource, jsonToOwnLiveSource(ownLiveSourceToJson(ownSource)))
        val gone = ownSource.copy(deleted = true, updatedAt = 99L)
        assertEquals(gone, jsonToOwnLiveSource(ownLiveSourceToJson(gone)))
        val single = OwnLiveSourceEntity("s2", "CHANNEL", "Uno", "https://a.example.com/x.m3u8", groupName = "Noticias", logo = "https://i.example.com/1.png")
        assertEquals(single, jsonToOwnLiveSource(ownLiveSourceToJson(single)))
    }

    @Test fun `a hostile or garbled own source row is refused, never stored`() {
        fun row(mutate: JSONObject.() -> Unit): JSONObject = ownLiveSourceToJson(ownSource).apply(mutate)
        assertNull(jsonToOwnLiveSource(row { put("kind", "OTHER") }))
        assertNull(jsonToOwnLiveSource(row { put("id", "") }))
        assertNull(jsonToOwnLiveSource(row { put("id", "a:b") }))
        assertNull(jsonToOwnLiveSource(row { put("url", "http://192.168.1.5/x.m3u8") }))
        assertNull(jsonToOwnLiveSource(row { put("url", "file:///sdcard/x.m3u8") }))
        assertNull(jsonToOwnLiveSource(row { put("name", "") }))
    }
}
