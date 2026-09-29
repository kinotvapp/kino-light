package com.arkiv.player.data.plugin

import org.json.JSONObject

/** One entry of a Nuvio provider repo's `manifest.json` `scrapers` array (spec §1.1). */
data class NuvioScraperEntry(
    val id: String,
    val name: String,
    val filename: String,
    val enabled: Boolean,
    val contentLanguage: List<String>,
    val supportedTypes: List<String>,
    val logo: String?,
    val disabledPlatforms: List<String>,
)

data class NuvioProviderManifest(val name: String, val scrapers: List<NuvioScraperEntry>)

/**
 * Parses Nuvio's OWN manifest format: a `manifest.json` with a top-level `scrapers` array,
 * nothing like `kino-plugin.json`'s shape. This is how Kino tells the two apart in the install box.
 */
object NuvioManifestParser {
    private fun strings(o: JSONObject, key: String): List<String> =
        o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    /** Null when [text] isn't shaped like a Nuvio provider manifest -- the caller falls back to [ManifestParser]. */
    fun parse(text: String): NuvioProviderManifest? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val scrapersJson = o.optJSONArray("scrapers") ?: return null
        val scrapers = (0 until scrapersJson.length()).mapNotNull { i ->
            val s = scrapersJson.optJSONObject(i) ?: return@mapNotNull null
            val id = s.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val filename = s.optString("filename").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            NuvioScraperEntry(
                id = id, name = s.optString("name", id).ifEmpty { id }, filename = filename,
                enabled = s.optBoolean("enabled", true),
                contentLanguage = strings(s, "contentLanguage"), supportedTypes = strings(s, "supportedTypes"),
                logo = s.optString("logo").takeIf { it.isNotEmpty() }, disabledPlatforms = strings(s, "disabledPlatforms"),
            )
        }
        return NuvioProviderManifest(o.optString("name", "Nuvio"), scrapers)
    }

    /** The scrapers Kino's picker offers: enabled by the manifest, and not disabled on Android (spec §4). */
    fun installable(manifest: NuvioProviderManifest): List<NuvioScraperEntry> =
        manifest.scrapers.filter { it.enabled && "android" !in it.disabledPlatforms.map(String::lowercase) }
}
