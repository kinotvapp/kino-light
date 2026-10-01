package com.arkiv.player.data.plugin.sync

import android.content.Context
import com.arkiv.player.companion.PairingCrypto
import com.arkiv.player.companion.PeerKeyStore
import com.arkiv.player.companion.PeerScopedTable
import com.arkiv.player.data.db.PluginInstallDao
import com.arkiv.player.data.plugin.ManifestParser
import org.json.JSONObject

/** Per plugin, the clock of the newest passwords this device holds (saved here, or applied from a peer). */
interface SecretStamps {
    fun get(id: String): Long
    fun set(id: String, stamp: Long)

    companion object {
        val NONE = object : SecretStamps {
            override fun get(id: String) = 0L
            override fun set(id: String, stamp: Long) {}
        }
    }
}

/** Production [SecretStamps]: plain preferences (clocks only, never a value). */
class PrefsSecretStamps(context: Context) : SecretStamps {
    private val prefs = context.applicationContext.getSharedPreferences("kino_plugin_secret_stamps", Context.MODE_PRIVATE)
    override fun get(id: String) = prefs.getLong(id, 0L)
    override fun set(id: String, stamp: Long) { prefs.edit().putLong(id, maxOf(stamp, get(id))).apply() }
}

/**
 * Plugin passwords between the person's devices, end-to-end encrypted: the companion sync table
 * `plugin_secrets`, which exists only on the wire. Each row is one plugin's passwords, every value
 * sealed with AES-256-GCM under the key this device agreed with THAT peer at pairing
 * ([PairingCrypto]), bound to the plugin id and the setting key. So:
 * - nothing readable crosses the plain `ws://` link, and a value can't be moved to another setting;
 * - a peer without a key (an older build, or a pairing whose key isn't agreed yet) is never sent a
 *   row (the engine also requires the peer to announce the table: older builds never do);
 * - rows are sealed per peer at push time, so a phone paired with two TVs uses each one's key.
 *
 * A row is sent when the person saved the plugin's passwords on one device
 * ([PluginInstallEntity.secretsAt], stamped by [PluginSyncMirror.recordSettings]); the receiver
 * applies it only when it is newer than what it holds ([SecretStamps]), so the same passwords
 * coming back are a no-op, and only for a plugin it has a live row for. Values are never logged.
 */
class PluginSecretSync(
    private val dao: PluginInstallDao,
    private val keys: PeerKeyStore,
    private val host: PluginSyncHost,
    private val stamps: SecretStamps,
    private val log: (String) -> Unit = { runCatching { android.util.Log.i("KinoPluginSync", it) } },
) : PeerScopedTable {
    override val table: String = TABLE

    override fun ready(peerId: String): Boolean = runCatching { keys.get(peerId) }.getOrNull() != null

    override suspend fun changedSince(cursor: Long, peerId: String): List<JSONObject> {
        val key = runCatching { keys.get(peerId) }.getOrNull() ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (row in dao.all().filter { !it.deleted && it.secretsAt > cursor }.sortedBy { it.secretsAt }) {
            val local = host.installed(row.id) ?: continue
            if (local.record.address != row.address) continue
            val values = runCatching { host.secretValues(local) }.getOrNull().orEmpty()
            if (values.isEmpty()) continue
            val sealed = JSONObject()
            for ((k, v) in values) sealed.put(k, PairingCrypto.seal(key, v, aad(row.id, k)))
            out += JSONObject().put("id", row.id).put("updatedAt", row.secretsAt).put("values", sealed)
        }
        return out
    }

    override suspend fun apply(row: JSONObject, peerId: String): Boolean {
        val key = runCatching { keys.get(peerId) }.getOrNull() ?: return false
        val id = row.optString("id").takeIf { ManifestParser.ID.matches(it) && it !in ManifestParser.RESERVED_IDS } ?: return true
        val stamp = row.optLong("updatedAt")
        if (stamp <= stamps.get(id)) return true
        val live = dao.get(id)
        if (live == null || live.deleted) return true
        val sealed = row.optJSONObject("values") ?: return true
        val names = sealed.keys().asSequence().take(MAX_KEYS).toList()
        val values = LinkedHashMap<String, String>()
        for (k in names) {
            if (k.isEmpty() || k.length > MAX_KEY_CHARS) continue
            val opened = PairingCrypto.open(key, sealed.optString(k), aad(id, k))
            if (opened == null) {
                // Another key (the pairing was redone on one side) or altered on the way: nothing applied,
                // and the row is asked for again on the next connection.
                log("[$id] a password could not be opened: not applied")
                return false
            }
            values[k] = opened
        }
        if (values.isNotEmpty()) host.applySecrets(id, values)
        stamps.set(id, stamp)
        log("[$id] passwords received from another device")
        return true
    }

    companion object {
        const val TABLE = "plugin_secrets"
        private const val MAX_KEYS = 20
        private const val MAX_KEY_CHARS = 64

        /** What a sealed value is bound to: this plugin's this setting, nothing else. */
        fun aad(pluginId: String, settingKey: String) = "kino-plugin-secret-v1|$pluginId|$settingKey"
    }
}
