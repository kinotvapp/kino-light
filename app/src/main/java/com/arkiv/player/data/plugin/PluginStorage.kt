package com.arkiv.player.data.plugin

import org.json.JSONObject
import java.io.File

/** `kino.storage`: strings per plugin, ≤ 64 KB in total, persisted as one JSON file (tmp + rename). */
class PluginStorage(private val file: File, private val maxBytes: Int = 64 * 1024) {
    private val values: MutableMap<String, String> by lazy { load() }

    @Synchronized fun get(key: String): String? = values[key]

    @Synchronized fun set(key: String, value: String) {
        val next = LinkedHashMap(values).apply { put(key, value) }
        val json = encode(next)
        if (json.toByteArray(Charsets.UTF_8).size > maxBytes) throw IllegalStateException("almacenamiento del plugin lleno (64 KB)")
        write(json)
        values[key] = value
    }

    @Synchronized fun remove(key: String) {
        if (values.remove(key) != null) write(encode(values))
    }

    private fun load(): MutableMap<String, String> {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return LinkedHashMap()
        return o.keys().asSequence().associateWithTo(LinkedHashMap()) { o.optString(it) }
    }

    private fun encode(map: Map<String, String>): String = JSONObject(map).toString()

    private fun write(json: String) = writeFileAtomically(file, json.toByteArray(Charsets.UTF_8))
}
