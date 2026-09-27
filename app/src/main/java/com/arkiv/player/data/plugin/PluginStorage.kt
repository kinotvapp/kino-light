package com.arkiv.player.data.plugin

import org.json.JSONObject
import java.io.File

/**
 * `kino.storage`: strings per plugin, ≤ 256 KB in total, persisted as one JSON file (tmp + rename).
 *
 * An entry may carry a `ttlMs` (see [set]); [clock] is injected so tests move time without waiting.
 * On disk, a permanent entry (no ttl) is a bare JSON string -- exactly the format used before this
 * option existed, so data written by an older build keeps loading unchanged (migrated lazily: it
 * simply has no expiry). An entry with a ttl is `{"v": value, "e": expiresAtEpochMs}`. Expired
 * entries are dropped from the in-memory map the moment any call touches this instance (get, set,
 * keys or remove) and so stop counting against [maxBytes] from that point on; the file itself only
 * shrinks with them once a call that already writes runs (`set`/`remove`), same as any other change.
 */
class PluginStorage(
    private val file: File,
    private val maxBytes: Int = MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val value: String, val expiresAt: Long? = null)

    private val entries: MutableMap<String, Entry> by lazy { load() }

    @Synchronized fun get(key: String): String? {
        purgeExpired()
        return entries[key]?.value
    }

    @Synchronized fun set(key: String, value: String, ttlMs: Long? = null) {
        if (ttlMs != null && !isValidTtl(ttlMs)) throw IllegalArgumentException(BAD_TTL)
        val expiresAt = ttlMs?.let { clock() + it }
        purgeExpired()
        // Every char is at least one UTF-8 byte: refuse the obvious case before copying the map.
        if (key.length + value.length > maxBytes) throw IllegalStateException(FULL)
        val next = LinkedHashMap(entries).apply { put(key, Entry(value, expiresAt)) }
        val json = encode(next)
        if (json.toByteArray(Charsets.UTF_8).size > maxBytes) throw IllegalStateException(FULL)
        write(json)
        entries[key] = Entry(value, expiresAt)
    }

    /** Every non-expired key, in insertion order. */
    @Synchronized fun keys(): List<String> {
        purgeExpired()
        return entries.keys.toList()
    }

    @Synchronized fun remove(key: String) {
        purgeExpired()
        if (entries.remove(key) != null) write(encode(entries))
    }

    private fun purgeExpired() {
        val now = clock()
        val expired = entries.filterValues { it.expiresAt != null && it.expiresAt <= now }.keys
        expired.forEach { entries.remove(it) }
    }

    private fun isValidTtl(ttlMs: Long) = ttlMs in 1..MAX_TTL_MS

    private fun load(): MutableMap<String, Entry> {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return LinkedHashMap()
        val map = LinkedHashMap<String, Entry>()
        for (k in o.keys()) {
            when (val raw = o.opt(k)) {
                is String -> map[k] = Entry(raw)
                is JSONObject -> if (raw.has("v")) {
                    map[k] = Entry(raw.optString("v"), if (raw.has("e")) raw.optLong("e") else null)
                }
                else -> Unit // an unrecognized shape: skip it rather than fail the whole load
            }
        }
        return map
    }

    private fun encode(map: Map<String, Entry>): String {
        val o = JSONObject()
        for ((k, entry) in map) {
            if (entry.expiresAt == null) o.put(k, entry.value) else o.put(k, JSONObject().put("v", entry.value).put("e", entry.expiresAt))
        }
        return o.toString()
    }

    private fun write(json: String) = writeFileAtomically(file, json.toByteArray(Charsets.UTF_8))

    companion object {
        const val MAX_BYTES = 256 * 1024

        /** `set`'s `ttlMs` upper bound: 30 days. */
        const val MAX_TTL_MS = 30L * 24 * 60 * 60 * 1000

        private const val FULL = "almacenamiento del plugin lleno (256 KB)"
        private const val BAD_TTL = "kino.storage.set: ttlMs debe ser un entero mayor que 0 y de hasta $MAX_TTL_MS ms (30 días)"
    }
}
