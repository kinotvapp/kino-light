package com.arkiv.player.data.sync

import com.arkiv.player.data.db.PluginInstallEntity
import com.arkiv.player.data.plugin.ManifestParser
import com.arkiv.player.data.plugin.NuvioPluginConverter
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.data.plugin.sync.PluginReach
import com.arkiv.player.data.plugin.sync.SharedSettings
import org.json.JSONObject

fun pluginInstallToJson(e: PluginInstallEntity): JSONObject = JSONObject().apply {
    put("id", e.id)
    put("address", e.address)
    put("name", e.name)
    put("nuvioRepo", e.nuvioRepo ?: JSONObject.NULL)
    put("nuvioScraperId", e.nuvioScraperId ?: JSONObject.NULL)
    put("version", e.version)
    put("sha256", e.sha256)
    put("enabled", e.enabled)
    put("approved", runCatching { JSONObject(e.approvedJson) }.getOrDefault(JSONObject()))
    put("settings", runCatching { JSONObject(e.settingsJson) }.getOrDefault(JSONObject()))
    put("updatedAt", e.updatedAt)
    put("deleted", e.deleted)
    put("secretsAt", e.secretsAt)
}

private val VERSION = Regex("^[0-9A-Za-z.+-]{1,32}$")
private val SHA = Regex("^([0-9a-f]{64})?$")
private const val MAX_NAME_CHARS = 80
private const val MAX_SCRAPER_ID_CHARS = 100

/**
 * Null for a row this build must not store: a peer's garbled or hostile data. The rules:
 * - the id is a valid manifest id and never one of the reserved ones (`own`, `magis`...);
 * - the address parses and is already canonical (it names a public GitHub repo, nothing else);
 * - only the official Xuper repos may carry the id `xuper`, and an official Xuper address only that id;
 * - a Nuvio row names both its repo and scraper, its address IS that repo, and its id is the one the
 *   converter derives from them ([NuvioPluginConverter.idFor]), so it can't impersonate another plugin;
 * - name, version and hash are short and plain; the approval and the settings are re-read defensively
 *   ([PluginReach.fromJson], [SharedSettings.fromJson]).
 * Nothing here is ever a manifest or a script: the receiving device fetches those itself.
 */
fun jsonToPluginInstall(json: JSONObject): PluginInstallEntity? {
    val id = json.optString("id").takeIf { ManifestParser.ID.matches(it) && it !in ManifestParser.RESERVED_IDS } ?: return null
    val address = json.optString("address").takeIf { it.isNotEmpty() && it.length <= 300 } ?: return null
    if (PluginAddress.parse(address)?.canonical != address) return null
    val official = XuperPrivilege.isOfficial(address)
    if ((id == XuperPrivilege.MANIFEST_ID) != official) return null
    val nuvioRepo = json.optString("nuvioRepo").takeUnless { json.isNull("nuvioRepo") || it.isEmpty() }
    val scraperId = json.optString("nuvioScraperId").takeUnless { json.isNull("nuvioScraperId") || it.isEmpty() }
    if ((nuvioRepo == null) != (scraperId == null)) return null
    if (nuvioRepo != null && scraperId != null) {
        if (official || nuvioRepo != address || scraperId.length > MAX_SCRAPER_ID_CHARS) return null
        if (NuvioPluginConverter.idFor(scraperId, nuvioRepo) != id) return null
    }
    val version = json.optString("version").takeIf { VERSION.matches(it) } ?: return null
    val sha = json.optString("sha256").takeIf { SHA.matches(it) } ?: return null
    val name = json.optString("name").filterNot { it.isISOControl() }.trim().take(MAX_NAME_CHARS).ifEmpty { id }
    return PluginInstallEntity(
        id = id,
        address = address,
        name = name,
        nuvioRepo = nuvioRepo,
        nuvioScraperId = scraperId,
        version = version,
        sha256 = sha,
        enabled = json.optBoolean("enabled", true),
        approvedJson = PluginReach.fromJson(json.optJSONObject("approved")).toJson().toString(),
        settingsJson = SharedSettings.toJson(SharedSettings.fromJson(json.optJSONObject("settings"))).toString(),
        updatedAt = json.optLong("updatedAt"),
        deleted = json.optBoolean("deleted"),
        secretsAt = json.optLong("secretsAt").coerceAtLeast(0),
    )
}
