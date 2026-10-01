package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.plugin.PluginSetting
import com.arkiv.player.data.plugin.SettingType
import org.json.JSONArray
import org.json.JSONObject

/**
 * The plugin settings that travel to the person's other devices, and the ones that never do.
 *
 * Travel: `text`, `select`, `toggle`, and a `list` whose fields are all text.
 * Never:
 * - `password`: the companion link is plain `ws://` on the LAN, and passwords live in the Keystore;
 *   the person types them again on the other device.
 * - `url` (and a `list` with any url field): a typed server becomes a host the plugin may reach
 *   (`PluginSettings` "typed servers"), so copying it would widen what the plugin reaches on the
 *   other device without the person there approving it -- and it is very often a LAN address
 *   (`http://192.168.x.x:8096`) that only makes sense on one network or device.
 */
object SharedSettings {
    const val MAX_KEYS = 50
    const val MAX_KEY_CHARS = 64
    const val MAX_TEXT_CHARS = 2048
    const val MAX_LIST_ENTRIES = 100

    fun isShared(s: PluginSetting): Boolean = when (s.type) {
        SettingType.TEXT, SettingType.SELECT, SettingType.TOGGLE -> true
        SettingType.LIST -> s.fields.none { it.type == SettingType.URL || it.type == SettingType.PASSWORD }
        else -> false
    }

    fun toJson(values: Map<String, Any>): JSONObject {
        val o = JSONObject()
        for ((k, v) in values.toSortedMap()) {
            when (v) {
                is List<*> -> o.put(
                    k,
                    JSONArray().also { a ->
                        v.forEach { e ->
                            val m = e as? Map<*, *> ?: return@forEach
                            a.put(JSONObject().also { eo -> m.forEach { (fk, fv) -> eo.put(fk.toString(), fv?.toString().orEmpty()) } })
                        }
                    },
                )
                else -> o.put(k, v)
            }
        }
        return o
    }

    /**
     * A peer's values, read defensively: short keys, text/booleans/numbers or a list of text-only
     * entries; anything else is dropped. Whether a key is a setting this device's version of the plugin
     * shares, and whether the value fits it, is decided when applying (`PluginConfigStore.applyShared`).
     */
    fun fromJson(o: JSONObject?): Map<String, Any> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Any>()
        val keys = o.keys().asSequence().take(MAX_KEYS).toList()
        for (k in keys) {
            if (k.isEmpty() || k.length > MAX_KEY_CHARS) continue
            when (val v = o.opt(k)) {
                is String -> if (v.length <= MAX_TEXT_CHARS) out[k] = v
                is Boolean -> out[k] = v
                is JSONArray -> {
                    if (v.length() > MAX_LIST_ENTRIES) continue
                    val entries = (0 until v.length()).map { i ->
                        val e = v.optJSONObject(i) ?: return@map null
                        e.keys().asSequence().take(MAX_KEYS).associateWith { f -> (e.opt(f) as? String)?.takeIf { it.length <= MAX_TEXT_CHARS } ?: "" }
                    }
                    if (entries.all { it != null }) out[k] = entries.filterNotNull()
                }
                else -> Unit
            }
        }
        return out
    }
}
