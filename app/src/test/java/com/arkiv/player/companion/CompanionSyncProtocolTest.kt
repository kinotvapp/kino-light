package com.arkiv.player.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSyncProtocolTest {
    @Test fun `sync hello round-trips a multi-entry since map`() {
        val hello = SyncHello(since = mapOf("items" to 100L, "episodes" to 250L, "playback" to 0L))
        assertEquals(hello, SyncHello.fromPayload(hello.toPayload()))
    }

    @Test fun `sync hello round-trips an empty since map`() {
        val hello = SyncHello(since = emptyMap())
        assertEquals(hello, SyncHello.fromPayload(hello.toPayload()))
    }

    @Test fun `sync rows round-trips`() {
        val rows = SyncRows(
            table = "items",
            rows = listOf(
                JSONObject().put("id", "a").put("updatedAt", 10L),
                JSONObject().put("id", "b").put("updatedAt", 20L),
            ),
            hwm = 20L,
            more = true,
        )
        val back = SyncRows.fromPayload(rows.toPayload())
        assertEquals(rows.table, back.table)
        assertEquals(rows.hwm, back.hwm)
        assertEquals(rows.more, back.more)
        assertEquals(rows.rows.map { it.toString() }, back.rows.map { it.toString() })
    }

    @Test fun `sync rows round-trips an empty row list`() {
        val rows = SyncRows(table = "playback", rows = emptyList(), hwm = 0L, more = false)
        val back = SyncRows.fromPayload(rows.toPayload())
        assertEquals(rows.table, back.table)
        assertEquals(rows.hwm, back.hwm)
        assertEquals(rows.more, back.more)
        assertTrue(back.rows.isEmpty())
    }

    @Test fun `sync done round-trips`() {
        val done = SyncDone(hwm = 12345L)
        assertEquals(done, SyncDone.fromPayload(done.toPayload()))
    }

    @Test fun `chunkRows keeps every page under budget and preserves order`() {
        // A generous batch of rows, each with a moderately sized payload, forcing multiple pages
        // at a small budget.
        val rows = (1..500).map { i ->
            JSONObject().put("id", "row-$i").put("updatedAt", i.toLong())
                .put("blob", "x".repeat(50))
        }
        val budget = 4 * 1024
        val pages = chunkRows(rows, budget)

        assertTrue("expected multiple pages", pages.size > 1)

        // Every page's actual encoded envelope must stay under budget.
        for (page in pages) {
            val envelope = newEnvelope(
                TYPE_SYNC_ROWS,
                SyncRows(table = "items", rows = page, hwm = 999L, more = true).toPayload(),
            ).encode()
            val bytes = envelope.toByteArray(Charsets.UTF_8).size
            assertTrue("page of ${page.size} rows encoded to $bytes bytes, over budget $budget", bytes <= budget)
        }

        // Concatenation equals the input, in order, no loss.
        val flattened = pages.flatten()
        assertEquals(rows.map { it.toString() }, flattened.map { it.toString() })
    }

    @Test fun `chunkRows on an empty list yields no pages`() {
        assertTrue(chunkRows(emptyList()).isEmpty())
    }

    @Test fun `a single oversized row still goes out as a lone page`() {
        val budget = 1024
        val hugeRow = JSONObject().put("id", "huge").put("blob", "x".repeat(budget * 4))
        val rows = listOf(
            JSONObject().put("id", "small-1"),
            hugeRow,
            JSONObject().put("id", "small-2"),
        )
        val pages = chunkRows(rows, budget)

        // The oversized row is never dropped and never merged with a neighbor.
        val flattened = pages.flatten()
        assertEquals(rows.map { it.toString() }, flattened.map { it.toString() })

        val hugePageIndex = pages.indexOfFirst { it.size == 1 && it[0].toString() == hugeRow.toString() }
        assertTrue("oversized row should be alone on its page", hugePageIndex >= 0)
    }
}
