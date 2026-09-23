package com.arkiv.player.data

import com.arkiv.player.companion.CompanionPlayItem
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

    @Test fun `an unknown source is null`() {
        assertNull(
            buildCompanionPlayItem(
                "torrent:x", ref = "r", itemIdentifier = "x", title = "T", season = null, episode = null, poster = "",
            ),
        )
    }
}
