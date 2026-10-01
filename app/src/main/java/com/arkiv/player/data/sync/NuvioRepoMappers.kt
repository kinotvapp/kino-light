package com.arkiv.player.data.sync

import com.arkiv.player.data.db.NuvioRepoEntity
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.XuperPrivilege
import org.json.JSONObject

fun nuvioRepoToJson(e: NuvioRepoEntity): JSONObject = JSONObject()
    .put("address", e.address)
    .put("updatedAt", e.updatedAt)
    .put("deleted", e.deleted)

/** The longest repo address a row may carry (`owner/repo/path@ref` fits many times over). */
internal const val MAX_NUVIO_REPO_CHARS = 300

/** Whether [address] may name a Nuvio repo in sync: short, already canonical (a public GitHub repo), never Xuper's. */
fun isSyncableNuvioRepo(address: String): Boolean =
    address.isNotEmpty() && address.length <= MAX_NUVIO_REPO_CHARS &&
        PluginAddress.parse(address)?.canonical == address && !XuperPrivilege.isOfficial(address)

/**
 * Null for a row this build must not store. The address is all there is, and it is only ever opened
 * the way a typed one is (the scraper picker fetches the repo's manifest itself on this device).
 */
fun jsonToNuvioRepo(json: JSONObject): NuvioRepoEntity? {
    val address = json.optString("address").takeIf(::isSyncableNuvioRepo) ?: return null
    return NuvioRepoEntity(address, json.optLong("updatedAt"), json.optBoolean("deleted"))
}
