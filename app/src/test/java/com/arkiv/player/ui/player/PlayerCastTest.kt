package com.arkiv.player.ui.player

import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which titles the phone player offers DLNA/Chromecast for ([playerOffersCast]), and the shape an
 * official-Xuper plugin title is cast in ([castableStreamItem]). The 0.9.41 regression: once Xuper's
 * Home rows moved to the plugin path every Xuper title played as `SourceKind.PLUGIN`, and the
 * "no cast for plugins" rule hid the buttons for all of them.
 */
class PlayerCastTest {

    private val cdn = "https://cdn.example/vod/abc_media.ts"
    private val headers = mapOf("Content-Auth" to "a", "Content-License" to "l")

    private fun item(
        kind: SourceKind,
        episodeId: String = "plugin:xuper:m1::0",
        pluginXuper: Boolean = false,
    ) = PlayerData(
        episodeId = episodeId,
        itemId = episodeId.substringBefore("::"),
        title = "Title",
        subtitle = "",
        mediaUrl = cdn,
        castUrl = null,
        artworkUrl = "",
        openingStartMs = null, openingEndMs = null, endingStartMs = null,
        kind = kind,
        requestHeaders = headers,
        pluginXuper = pluginXuper,
    )

    /** `PlayerData.pluginXuper` as `loadPlugin` sets it: the installed record's address, never the id. */
    private fun pluginFrom(address: String) = item(SourceKind.PLUGIN, pluginXuper = XuperPrivilege.isOfficial(address))

    private val proxy: (String, Map<String, String>) -> String = { url, h -> "http://127.0.0.1:9/s?h=${h.size}&u=$url" }

    // --- playerOffersCast ---

    @Test fun `TV never offers cast, whatever plays`() {
        assertFalse(playerOffersCast(isTv = true, streamItem = null))
        assertFalse(playerOffersCast(isTv = true, streamItem = item(SourceKind.MAGIS, "magis:1")))
        assertFalse(playerOffersCast(isTv = true, streamItem = pluginFrom(XuperPrivilege.SOURCE_REPO)))
    }

    @Test fun `phone offers cast for native sources, in either orientation`() {
        // Orientation is not an input anymore: landscape shows the buttons inside the auto-hiding
        // controls, like portrait.
        assertTrue(playerOffersCast(isTv = false, streamItem = null))
        assertTrue(playerOffersCast(isTv = false, streamItem = item(SourceKind.MAGIS, "magis:1")))
    }

    @Test fun `official Xuper plugin titles offer cast, new and legacy address`() {
        assertTrue(playerOffersCast(isTv = false, streamItem = pluginFrom(XuperPrivilege.SOURCE_REPO)))
        for (legacy in XuperPrivilege.LEGACY_SOURCE_REPOS) {
            assertTrue(playerOffersCast(isTv = false, streamItem = pluginFrom(legacy)))
        }
    }

    @Test fun `any other plugin keeps no cast`() {
        assertFalse(playerOffersCast(isTv = false, streamItem = pluginFrom("someone-else/kino-plugin-xuper")))
        assertFalse(playerOffersCast(isTv = false, streamItem = pluginFrom("kinotvapp/kino-plugin-archive")))
    }

    @Test fun `a plugin live channel keeps no cast, even Xuper's`() {
        val live = item(SourceKind.PLUGIN, PluginIds.liveEpisodeId("xuper", "ch1"), pluginXuper = true)
        assertTrue(PluginIds.isLiveEpisode(live.episodeId))
        assertFalse(playerOffersCast(isTv = false, streamItem = live))
    }

    // --- castableStreamItem ---

    @Test fun `native items are cast as they are`() {
        val magis = item(SourceKind.MAGIS, "magis:1").copy(mediaUrl = "http://127.0.0.1:9/s?u=x", castUrl = cdn)
        assertSame(magis, castableStreamItem(magis, proxy))
    }

    @Test fun `an official Xuper plugin title is cast in the native Magis shape`() {
        val plugin = pluginFrom(XuperPrivilege.SOURCE_REPO)
        val cast = castableStreamItem(plugin, proxy)!!
        // MAGIS: what makes castRequestFor send the proxy's LAN url (the CDN wants headers the
        // receiver cannot send) and DLNA pull from the proxy instead of the raw CDN.
        assertEquals(SourceKind.MAGIS, cast.kind)
        // The loopback proxy with the stream's headers in it, like loadMagis' `localUrl`.
        assertEquals(proxy(cdn, headers), cast.mediaUrl)
        // The raw CDN url: where the container (_media.ts / _media.mp4) and the remux key come from.
        assertEquals(cdn, cast.castUrl)
        assertEquals(plugin.episodeId, cast.episodeId)
        assertEquals(plugin.title, cast.title)
    }

    @Test fun `other plugins and plugin live channels have no castable shape`() {
        assertNull(castableStreamItem(pluginFrom("someone-else/kino-plugin-xuper"), proxy))
        val live = item(SourceKind.PLUGIN, PluginIds.liveEpisodeId("xuper", "ch1"), pluginXuper = true)
        assertNull(castableStreamItem(live, proxy))
    }
}
