package com.arkiv.player.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.util.Base64

/**
 * v37 -> v38: Caracol (Ditu) becomes the `caracol-tv` plugin.
 *
 * Everything Caracol did in the app is now in the plugin (see `plugins/caracol-tv/`); the native
 * sources, screens and player go away in the same release. Library items that were already saved
 * (`items.source = "ditu"`, `items.identifier = "ditu:<contentId>"`, `episodes.torrentData =
 * "ditu1:<type>:<contentId>"`) keep working by being rewritten into the plugin's wrapper shape
 * (`plg1:caracol-tv:<base64url(json)>`). The `r` inside the JSON is the plugin's own ref
 * (`cditu1:<type>:<contentId>`), exactly what the plugin's `decodeRef` reads.
 *
 * `playback.episodeId` is left alone: a `ditu:` continuation row would be unrecognised, and
 * `PlayerSource.kindFor` falls through to `UNKNOWN` so the player reports "no longer available"
 * instead of crashing. Idempotent: rerunning is a no-op (the `WHERE` filters on shapes the migration
 * just produced).
 */
internal object CaracolToPluginMigration {
    private const val PLUGIN_ID = "caracol-tv"
    private const val NEW_SOURCE = "plugin:$PLUGIN_ID"
    private const val ITEM_PREFIX_OLD = "ditu:"
    private const val ITEM_PREFIX_NEW = "plugin:$PLUGIN_ID:"
    private const val RAW_TORCHOLD_PREFIX = "ditu1:"
    private const val PLUGIN_REF_PREFIX = "plg1:$PLUGIN_ID:"
    private const val PLUGIN_RAW_PREFIX = "cditu1:"
    private const val SERIES_TYPES = "BUNDLE|GROUP_OF_BUNDLES"

    fun migrate(db: SupportSQLiteDatabase) {
        translateItems(db)
        translateEpisodes(db)
    }

    private fun translateItems(db: SupportSQLiteDatabase) {
        // 1) source
        db.execSQL("UPDATE items SET source = '$NEW_SOURCE' WHERE source = 'ditu'")
        // 2) identifier (PK): "ditu:<contentId>" -> "plugin:caracol-tv:<contentId>"
        db.execSQL(
            "UPDATE items SET identifier = '$ITEM_PREFIX_NEW' || substr(identifier, ${ITEM_PREFIX_OLD.length + 1}) " +
                "WHERE source = '$NEW_SOURCE' AND identifier LIKE '$ITEM_PREFIX_OLD%'",
        )
        // 3) torrentData: encode the old "ditu1:<type>:<id>" as a plugin ref
        val cursor = db.query("SELECT identifier, torrentData FROM items WHERE source = '$NEW_SOURCE' AND torrentData LIKE '$RAW_TORCHOLD_PREFIX%'")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val raw = c.getString(1) ?: continue
                val (kind, r) = encodePlugin(raw) ?: continue
                db.execSQL("UPDATE items SET torrentData = ? WHERE identifier = ?", arrayOf(r, id))
                // The unused `kind` is returned for logging; the source field tells us the item's row
                // is already plugin:caracol-tv so we don't filter on it here.
                if (kind.isEmpty()) continue
            }
        }
    }

    private fun translateEpisodes(db: SupportSQLiteDatabase) {
        // 4) episodes.id and episodes.itemId: same prefix swap as items
        db.execSQL(
            "UPDATE episodes SET id = '$ITEM_PREFIX_NEW' || substr(id, ${ITEM_PREFIX_OLD.length + 1}), " +
                "itemId = '$ITEM_PREFIX_NEW' || substr(itemId, ${ITEM_PREFIX_OLD.length + 1}) " +
                "WHERE itemId LIKE '$ITEM_PREFIX_OLD%'",
        )
        // 5) episodes.torrentData: "ditu1:VOD:<id>" -> plg1:caracol-tv:base64({id, k:"EPISODE", r, s, e})
        val cursor = db.query("SELECT id, season, episode, torrentData FROM episodes WHERE torrentData LIKE '$RAW_TORCHOLD_PREFIX%'")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val season = c.getInt(1)
                val number = c.getInt(2)
                val raw = c.getString(3) ?: continue
                val (kind, r) = encodePlugin(raw, season, number) ?: continue
                db.execSQL("UPDATE episodes SET torrentData = ? WHERE id = ?", arrayOf(r, id))
                if (kind.isEmpty()) continue
            }
        }
    }

    /**
     * Wraps an old `ditu1:<type>:<contentId>` ref into the plugin shape. Returns (`kind` for
     * logging, the new `plg1:caracol-tv:<base64>` ref) or null if [raw] isn't shaped like that.
     * When [season]/[number] are given, the JSON marks the kind as `EPISODE` and carries them.
     */
    private fun encodePlugin(raw: String, season: Int? = null, number: Int? = null): Pair<String, String>? {
        if (!raw.startsWith(RAW_TORCHOLD_PREFIX)) return null
        val parts = raw.split(":", limit = 3)
        if (parts.size < 3 || parts[2].isBlank()) return null
        val type = parts[1]
        val contentId = parts[2]
        val isSeries = SERIES_TYPES.split("|").contains(type)
        val kind = when {
            season != null -> "EPISODE"
            isSeries -> "SERIES"
            else -> "VOD"
        }
        val innerR = "$PLUGIN_RAW_PREFIX$type:$contentId"
        val json = JSONObject().put("id", contentId).put("k", kind).put("r", innerR)
        if (kind == "EPISODE") {
            json.put("s", season ?: 1)
            json.put("e", number ?: 1)
        }
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        return kind to "$PLUGIN_REF_PREFIX$payload"
    }
}