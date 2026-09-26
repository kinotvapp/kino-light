package com.arkiv.player.data.plugin.catalog

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One recommended plugin, as the remote catalog describes it. */
data class CatalogEntry(
    val id: String,
    /** `owner/repo`, the only thing the installer is ever given from the catalog. */
    val repo: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
    /** Needs its settings filled before it works (own-server): the gate waits for that. */
    val needsSetup: Boolean = false,
    /** What the person was already using before plugins existed (Xuper): listed first, marked. */
    val legacyDefault: Boolean = false,
    /** Ships inside the APK: installs without a network. */
    val bundled: Boolean = false,
    /** Capabilities this build must have for the entry to be shown (for example `xuper-bridge`). */
    val requires: List<String> = emptyList(),
)

data class PluginCatalog(val entries: List<CatalogEntry>)

/**
 * Reads the remote catalog. It is DATA, never code: everything is validated, an entry that fails is
 * dropped and the rest kept, and a whole catalog that cannot be trusted (garbage, too big, a schema
 * newer than this app knows) is null so the caller falls back to its cache or its seed.
 */
object PluginCatalogParser {
    const val SUPPORTED_SCHEMA = 1
    const val MAX_BYTES = 64 * 1024
    const val MAX_ENTRIES = 50
    private const val MAX_NAME = 60
    private const val MAX_DESCRIPTION = 200
    private const val MAX_TAGS = 5
    private const val MAX_TAG = 30
    private val REPO = Regex("^[A-Za-z0-9_-]{1,100}/[A-Za-z0-9_-]{1,100}$")
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

    /** The one rule for what an `owner/repo` from the catalog may look like (also checked before installing). */
    fun isValidRepo(repo: String): Boolean = REPO.matches(repo)

    /** [capabilities]: what THIS build can do; entries that require anything else are hidden. */
    fun parse(json: String, capabilities: Set<String>): PluginCatalog? {
        if (json.toByteArray().size > MAX_BYTES) return null
        val root = try { JSONObject(json) } catch (e: JSONException) { return null }
        if (root.optInt("schema", 0) != SUPPORTED_SCHEMA) return null
        val array = root.optJSONArray("plugins") ?: return null
        val seen = HashSet<String>()
        val entries = ArrayList<CatalogEntry>()
        for (i in 0 until minOf(array.length(), MAX_ENTRIES)) {
            val entry = array.optJSONObject(i)?.let(::entryOf) ?: continue
            if (!capabilities.containsAll(entry.requires)) continue
            if (seen.add(entry.id)) entries += entry
        }
        return PluginCatalog(entries)
    }

    private fun entryOf(o: JSONObject): CatalogEntry? {
        val id = (o.opt("id") as? String)?.takeIf { ID.matches(it) } ?: return null
        val repo = (o.opt("repo") as? String)?.takeIf { REPO.matches(it) } ?: return null
        val name = (o.opt("name") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: return null
        return CatalogEntry(
            id = id,
            repo = repo,
            name = name,
            description = ((o.opt("description") as? String) ?: "").trim().take(MAX_DESCRIPTION),
            tags = strings(o.optJSONArray("tags")).map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_TAG }.take(MAX_TAGS),
            needsSetup = o.optBoolean("needsSetup", false),
            legacyDefault = o.optBoolean("legacyDefault", false),
            bundled = o.optBoolean("bundled", false),
            requires = strings(o.optJSONArray("requires")),
        )
    }

    private fun strings(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.opt(it) as? String }
}
