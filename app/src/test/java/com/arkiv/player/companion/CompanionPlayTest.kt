package com.arkiv.player.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionPlayTest {
    @Test fun `play item round-trips through its payload`() {
        val item = CompanionPlayItem(
            kind = CompanionPlayItem.KIND_MAGIS, ref = "r-123", contentId = "c-9",
            title = "Bleach", season = 1, episode = 164, episodeTitle = "La estrategia",
            seriesRef = "s-1", poster = "http://p", backdrop = "http://b",
        )
        val back = CompanionPlayItem.fromPayload(item.toPayload())
        assertEquals(item, back)
    }

    @Test fun `live item keeps only the code`() {
        val item = CompanionPlayItem(kind = CompanionPlayItem.KIND_LIVE, liveCode = "caracol")
        assertEquals("caracol", CompanionPlayItem.fromPayload(item.toPayload())?.liveCode)
        assertEquals(CompanionPlayItem.KIND_LIVE, CompanionPlayItem.fromPayload(item.toPayload())?.kind)
    }

    @Test fun `fromPayload rejects a blank kind`() {
        assertNull(CompanionPlayItem.fromPayload(org.json.JSONObject().put("kind", "")))
    }

    @Test fun `non-ASCII title survives`() {
        val item = CompanionPlayItem(kind = CompanionPlayItem.KIND_MAGIS, ref = "r", contentId = "c", title = "Corazón — Otoño")
        assertEquals("Corazón — Otoño", CompanionPlayItem.fromPayload(item.toPayload())?.title)
    }

    @Test fun `ack round-trips`() {
        val ack = CompanionPlayAck(ok = false, reason = "no_link", title = "En vivo")
        assertEquals(ack, CompanionPlayAck.fromPayload(ack.toPayload()))
    }
}
