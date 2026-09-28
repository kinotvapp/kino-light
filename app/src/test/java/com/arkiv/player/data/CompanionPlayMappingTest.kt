package com.arkiv.player.data

import com.arkiv.player.companion.CompanionPlayItem
import com.arkiv.player.data.plugin.PluginRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionPlayMappingTest {
    @Test fun `live id needs no row and carries just the code`() {
        val item = buildCompanionPlayItem(
            "live:caracol", ref = null, itemIdentifier = "", title = "", season = null, episode = null, poster = "",
        )
        assertEquals(CompanionPlayItem.KIND_LIVE, item?.kind)
        assertEquals("caracol", item?.liveCode)
    }

    @Test fun `magis episode rebuilds kind, ref, contentId, season and episode`() {
        val item = buildCompanionPlayItem(
            episodeId = "magis:cid-42:e164", ref = "ref-abc", itemIdentifier = "magis:cid-42",
            title = "Bleach", season = 1, episode = 164, poster = "http://p",
        )!!
        assertEquals(CompanionPlayItem.KIND_MAGIS, item.kind)
        assertEquals("ref-abc", item.ref)
        assertEquals("cid-42", item.contentId)
        assertEquals(1, item.season)
        assertEquals(164, item.episode)
        assertEquals("Bleach", item.title)
        assertEquals("http://p", item.poster)
    }

    @Test fun `ditu keeps the ref but derives its own contentId (blank here)`() {
        val item = buildCompanionPlayItem(
            "ditu:x", ref = "dref", itemIdentifier = "ditu:x", title = "T", season = null, episode = null, poster = "",
        )!!
        assertEquals(CompanionPlayItem.KIND_DITU, item.kind)
        assertEquals("dref", item.ref)
        assertEquals("", item.contentId)
    }

    @Test fun `a magis id with no ref cannot be reproduced elsewhere`() {
        assertNull(
            buildCompanionPlayItem(
                "magis:cid", ref = null, itemIdentifier = "magis:cid", title = "T", season = null, episode = null, poster = "",
            ),
        )
    }

    @Test fun `a plugin title is not handed to the companion`() {
        assertNull(buildCompanionPlayItem("plugin:demo:m1::0", ref = "plg1:demo:x", itemIdentifier = "plugin:demo:m1", title = "t", season = null, episode = null, poster = ""))
    }

    // --- the official Xuper plugin's titles (0.9.41 regression: they became PLUGIN titles) ---

    private fun xuperRef(kind: String, inner: String, number: Int = 0) =
        PluginRef("xuper", "cid-7", kind, inner, number = number).encode()

    @Test fun `an official Xuper plugin movie travels as the Magis ref it wraps`() {
        val item = buildCompanionPlayItem(
            "plugin:xuper:cid-7::0", ref = xuperRef(PluginRef.MOVIE, "magis1:movie:0:cid-7"),
            itemIdentifier = "plugin:xuper:cid-7", title = "Dune", season = null, episode = null, poster = "http://p",
            officialXuper = true,
        )!!
        // KIND_MAGIS: the TV re-mints a `magis:` title and resolves it through its own installed
        // Xuper plugin (`LegacyXuperRefSource`), and a TV still on the native Magis source reads it too.
        assertEquals(CompanionPlayItem.KIND_MAGIS, item.kind)
        assertEquals("magis1:movie:0:cid-7", item.ref)
        assertEquals("cid-7", item.contentId)
        assertEquals(0, item.episode)
        assertEquals("Dune", item.title)
        assertEquals("http://p", item.poster)
    }

    @Test fun `an official Xuper plugin chapter keeps its chapter number and season`() {
        val item = buildCompanionPlayItem(
            "plugin:xuper:cid-7::e12", ref = xuperRef(PluginRef.EPISODE, "magis1:teleplay:12:cid-7", 12),
            itemIdentifier = "plugin:xuper:cid-7", title = "Bleach", season = 2, episode = 12, poster = "",
            officialXuper = true,
        )!!
        assertEquals(CompanionPlayItem.KIND_MAGIS, item.kind)
        assertEquals("magis1:teleplay:12:cid-7", item.ref)
        assertEquals("cid-7", item.contentId)
        assertEquals(12, item.episode)
        assertEquals(2, item.season)
    }

    @Test fun `the same ref from a plugin that is not the official Xuper is not sent`() {
        assertNull(
            buildCompanionPlayItem(
                "plugin:xuper:cid-7::0", ref = xuperRef(PluginRef.MOVIE, "magis1:movie:0:cid-7"),
                itemIdentifier = "plugin:xuper:cid-7", title = "Dune", season = null, episode = null, poster = "",
                officialXuper = false,
            ),
        )
    }

    @Test fun `an official Xuper plugin ref with no Magis ref inside is not sent`() {
        assertNull(
            buildCompanionPlayItem(
                "plugin:xuper:cid-7::0", ref = xuperRef(PluginRef.MOVIE, "something-else"),
                itemIdentifier = "plugin:xuper:cid-7", title = "Dune", season = null, episode = null, poster = "",
                officialXuper = true,
            ),
        )
        assertNull(
            buildCompanionPlayItem(
                "plugin:xuper:cid-7::0", ref = null,
                itemIdentifier = "plugin:xuper:cid-7", title = "Dune", season = null, episode = null, poster = "",
                officialXuper = true,
            ),
        )
    }

    @Test fun `an unknown source is null`() {
        assertNull(
            buildCompanionPlayItem(
                "torrent:x", ref = "r", itemIdentifier = "x", title = "T", season = null, episode = null, poster = "",
            ),
        )
    }

    @Test fun `a plugin channel from the module travels as its live code`() {
        val item = buildCompanionPlayItem("live:plugin:own-server:c1", null, "", "", null, null, "")!!
        assertEquals(CompanionPlayItem.KIND_LIVE, item.kind)
        assertEquals("plugin:own-server:c1", item.liveCode)
    }
}
