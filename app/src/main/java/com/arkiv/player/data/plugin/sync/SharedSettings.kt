package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.plugin.PluginSetting
import com.arkiv.player.data.plugin.SettingType
import org.json.JSONArray
import org.json.JSONObject

/**
 * The plugin settings that travel to the person's other devices in the `plugin_installs` row, and
 * the ones that never do there.
 *
 * Travel: `text`, `select`, `toggle`, `url`, and a `list` without password fields. A typed server
 * (`url`) is a host the plugin may reach ("typed servers"): the person typed it on their other
 * device, and it is validated here exactly like a save by hand (`PluginSettings.validateValue`:
 * a loopback or malformed address is skipped) before it becomes one. A LAN address is fine: it is
 * the person's own server.
 * Never here: `password` (and a `list` with a password field). The companion link is plain `ws://`;
 * passwords travel only end-to-end encrypted with the key the two devices agreed at pairing
 * (see `PluginSecretSync`), never inside this row.
 */
object SharedSettings {
    const val MAX_KEYS = 50
    const val MAX_KEY_CHARS = 64
    const val MAX_TEXT_CHARS = 2048
    const val MAX_LIST_ENTRIES = 100

    fun isShared(s: PluginSetting): Boolean = when (s.type) {
        SettingType.TEXT, SettingType.SELECT, SettingType.TOGGLE, SettingType.URL -> true
        SettingType.LIST -> s.fields.none { it.type == SettingType.PASSWORD }
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
