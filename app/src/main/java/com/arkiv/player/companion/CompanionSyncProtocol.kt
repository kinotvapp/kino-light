package com.arkiv.player.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * App-level envelope payloads for one companion progress/library sync round, carried over the
 * EXISTING [CompanionProtocol] transport ([Envelope]/[newEnvelope]/[decodeEnvelope]). This file is
 * pure data + framing -- no networking, no coroutines. The sync engine (consumer) drives the actual
 * round: send [SyncHello], receive/send zero-or-more [SyncRows] pages per table, close with
 * [SyncDone].
 */

const val TYPE_SYNC_HELLO = "sync_hello"
const val TYPE_SYNC_ROWS = "sync_rows"
const val TYPE_SYNC_DONE = "sync_done"

/**
 * Opens a sync round. [since] is a PER-TABLE cursor: table name -> the high-water-mark the sender
 * already has for that table. A table absent from the map (or the whole map empty) means "send me
 * everything" for that table.
 */
data class SyncHello(val since: Map<String, Long>) {
    fun toPayload(): JSONObject {
        val sinceObj = JSONObject()
        for ((table, cursor) in since) sinceObj.put(table, cursor)
        return JSONObject().put("since", sinceObj)
    }

    companion object {
        fun fromPayload(payload: JSONObject): SyncHello {
            val sinceObj = payload.optJSONObject("since") ?: JSONObject()
            val since = LinkedHashMap<String, Long>()
            val keys = sinceObj.keys()
            while (keys.hasNext()) {
                val table = keys.next()
                since[table] = sinceObj.getLong(table)
            }
            return SyncHello(since)
        }
    }
}

/**
 * One page of rows for a single [table]. [rows] are ASCENDING by the table's `updatedAt` (the
 * caller's ordering contract -- this type does not sort). [hwm] is the high-water-mark of THIS
 * page (i.e. the `updatedAt` of its last row); [more] is true when additional pages follow for
 * this table.
 */
data class SyncRows(val table: String, val rows: List<JSONObject>, val hwm: Long, val more: Boolean) {
    fun toPayload(): JSONObject {
        val rowsArr = JSONArray()
        for (row in rows) rowsArr.put(row)
        return JSONObject().put("table", table).put("rows", rowsArr).put("hwm", hwm).put("more", more)
    }

    companion object {
        fun fromPayload(payload: JSONObject): SyncRows {
            val rowsArr = payload.optJSONArray("rows") ?: JSONArray()
            val rows = ArrayList<JSONObject>(rowsArr.length())
            for (i in 0 until rowsArr.length()) rows.add(rowsArr.getJSONObject(i))
            return SyncRows(
                table = payload.optString("table"),
                rows = rows,
                hwm = payload.optLong("hwm"),
                more = payload.optBoolean("more"),
            )
        }
    }
}

/**
 * Closes a sync round. [hwm] is the overall high-water-mark across every table touched this round
 * -- informational only. Per-table cursor advancement is the engine's job (from each table's last
 * [SyncRows] page), NOT this message.
 */
data class SyncDone(val hwm: Long) {
    fun toPayload(): JSONObject = JSONObject().put("hwm", hwm)

    companion object {
        fun fromPayload(payload: JSONObject): SyncDone = SyncDone(payload.optLong("hwm"))
    }
}

/**
 * Headroom subtracted from [CompanionProtocol.MAX_MESSAGE_BYTES] to cover the [Envelope] wrapper
 * (`v`/`type`/`id` fields + JSON punctuation) and the [SyncRows] wrapper (`table`/`hwm`/`more`
 * fields + the `rows` array brackets) around the rows a page actually carries. Comfortably
 * generous -- table names and UUIDs are short -- so it never eats meaningfully into a page's row
 * budget.
 */
private const val FRAMING_HEADROOM_BYTES = 512

/**
 * Splits an ASCENDING [rows] list into pages such that each page, once wrapped in a
 * `SyncRows(...).toPayload()` inside a `newEnvelope(TYPE_SYNC_ROWS, ...).encode()`, stays at or
 * under [budget] UTF-8 bytes. Order is preserved and no row is ever dropped: a single row whose own
 * encoded size already exceeds the per-row budget still goes out alone, as a lone one-row page,
 * rather than being silently discarded (that page will itself exceed [budget] -- an accepted edge,
 * since dropping data is worse than one oversized message).
 */
fun chunkRows(rows: List<JSONObject>, budget: Int = CompanionProtocol.MAX_MESSAGE_BYTES): List<List<JSONObject>> {
    if (rows.isEmpty()) return emptyList()

    val rowBudget = (budget - FRAMING_HEADROOM_BYTES).coerceAtLeast(1)
    val pages = ArrayList<List<JSONObject>>()
    var page = ArrayList<JSONObject>()
    var pageBytes = 0

    fun flush() {
        if (page.isNotEmpty()) {
            pages.add(page)
            page = ArrayList()
            pageBytes = 0
        }
    }

    for (row in rows) {
        val rowStr = row.toString()
        // Account for the comma separator between array elements (all rows past the first on a page).
        val rowBytes = rowStr.toByteArray(Charsets.UTF_8).size + 1

        if (page.isNotEmpty() && pageBytes + rowBytes > rowBudget) flush()

        page.add(row)
        pageBytes += rowBytes

        // A lone oversized row: emit it immediately as its own page rather than trying to pack
        // more onto it (which would only make an already-over-budget page bigger).
        if (page.size == 1 && pageBytes > rowBudget) flush()
    }
    flush()

    return pages
}
