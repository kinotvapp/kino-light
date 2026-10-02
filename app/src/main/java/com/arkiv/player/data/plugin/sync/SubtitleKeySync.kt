package com.arkiv.player.data.plugin.sync

import com.arkiv.player.companion.PairingCrypto
import com.arkiv.player.companion.PeerKeyStore
import com.arkiv.player.companion.PeerScopedTable
import com.arkiv.player.data.subtitles.SubtitleKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The person's OWN online-subtitle values (OpenSubtitles key, SubDL key, OpenSubtitles account) between their
 * devices, end-to-end encrypted exactly like [PluginSecretSync]: every value sealed with AES-256-GCM under the key
 * agreed with THAT peer at pairing, bound to its name, so nothing readable crosses the plain `ws://` link. It rides
 * the same `plugin_secrets` table as one extra row ([ROW_ID]); a build that does not know it finds no plugin by
 * that id and ignores the row.
 *
 * One clock for the whole group ([SubtitleKeys.stamp]): the LAST SAVED side wins and applies all four values, a
 * cleared value ("") included. The shared Kino key from the activation blob is never read here: only
 * [SubtitleKeys.ownValues]. Values are never logged.
 */
class SubtitleKeySync(
    private val keys: SubtitleKeys,
    private val peerKeys: PeerKeyStore,
    private val log: (String) -> Unit = { runCatching { android.util.Log.i("KinoSubtitleSync", it) } },
) {
    val changes: Flow<Unit> get() = keys.localChanges

    suspend fun changedSince(cursor: Long, peerId: String): List<JSONObject> {
        val key = runCatching { peerKeys.get(peerId) }.getOrNull() ?: return emptyList()
        val stamp = keys.stamp()
        if (stamp <= cursor) return emptyList()
        val values = withContext(Dispatchers.IO) { runCatching { keys.ownValues() }.getOrNull() } ?: return emptyList()
        val sealed = JSONObject()
        for ((name, value) in values) sealed.put(name, PairingCrypto.seal(key, value, aad(name)))
        return listOf(JSONObject().put("id", ROW_ID).put("updatedAt", stamp).put("values", sealed))
    }

    /** False when a value could not be opened (another key, or altered): nothing is applied and the row comes again. */
    suspend fun apply(row: JSONObject, peerId: String): Boolean {
        val key = runCatching { peerKeys.get(peerId) }.getOrNull() ?: return false
        val stamp = row.optLong("updatedAt")
        if (stamp <= keys.stamp()) return true
        val sealed = row.optJSONObject("values") ?: return true
        val values = LinkedHashMap<String, String>()
        for (name in NAMES) {
            if (!sealed.has(name)) continue
            val opened = PairingCrypto.open(key, sealed.optString(name), aad(name))
            if (opened == null) {
                log("subtitle values could not be opened: not applied")
                return false
            }
            values[name] = opened
        }
        if (values.isEmpty()) return true
        withContext(Dispatchers.IO) { keys.applyRemote(values, stamp) }
        log("subtitle values received from another device")
        return true
    }

    companion object {
        const val ROW_ID = "subtitle-keys"
        private val NAMES = listOf(SubtitleKeys.WIRE_OPENSUBTITLES, SubtitleKeys.WIRE_SUBDL, SubtitleKeys.WIRE_OS_USER, SubtitleKeys.WIRE_OS_PASSWORD)

        /** What a sealed value is bound to: this value's name, nothing else. */
        fun aad(name: String) = "kino-subtitle-keys-v1|$name"
    }
}

/** The one peer-scoped table the engine knows (`plugin_secrets`), carrying plugin passwords AND the subtitle values. */
class CombinedSecretTable(
    private val plugins: PluginSecretSync,
    private val subtitles: SubtitleKeySync,
) : PeerScopedTable {
    override val table: String = plugins.table
    override val changes: Flow<Unit> get() = subtitles.changes
    override fun ready(peerId: String): Boolean = plugins.ready(peerId)

    override suspend fun changedSince(cursor: Long, peerId: String): List<JSONObject> =
        (plugins.changedSince(cursor, peerId) + subtitles.changedSince(cursor, peerId)).sortedBy { it.optLong("updatedAt") }

    override suspend fun apply(row: JSONObject, peerId: String): Boolean =
        if (row.optString("id") == SubtitleKeySync.ROW_ID) subtitles.apply(row, peerId) else plugins.apply(row, peerId)
}
