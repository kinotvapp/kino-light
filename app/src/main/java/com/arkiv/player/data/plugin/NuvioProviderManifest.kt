package com.arkiv.player.data.plugin

import org.json.JSONObject

/**
 * One entry of a Nuvio provider repo's `manifest.json` `scrapers` array (spec §1.1). [description],
 * [version] and [author] are optional in the wild -- a real manifest carries them, the fixtures this
 * app's own tests use often don't -- so they default to null instead of failing the whole entry.
 */
data class NuvioScraperEntry(
    val id: String,
    val name: String,
    val filename: String,
    val enabled: Boolean,
    val contentLanguage: List<String>,
    val supportedTypes: List<String>,
    val logo: String?,
    val disabledPlatforms: List<String>,
    val description: String? = null,
    val version: String? = null,
    val author: String? = null,
)

data class NuvioProviderManifest(val name: String, val scrapers: List<NuvioScraperEntry>)

/**
 * The spellings of a Nuvio `supportedTypes` entry, folded onto the three the adapter knows: Nuvio's
 * own manifests say `movie`/`tv`/`anime`, community repos also write `series`, `shows`, `films`...
 * The same sets the scraper picker's "Película"/"Serie"/"Anime" chips use
 * (`ui/plugin/NuvioScraperPickerState.kt`), so a card that promises series gets series.
 */
object NuvioMediaTypes {
    val MOVIE = setOf("movie", "movies", "film", "films")
    val SERIES = setOf("series", "tv", "show", "shows")
    val ANIME = setOf("anime")

    /** `movie`, `tv` or `anime` for a known spelling (any case, spaces trimmed); anything else as written, lowercased. */
    fun canonical(raw: String): String {
        val t = raw.trim().lowercase()
        return when (t) {
            in MOVIE -> "movie"
            in SERIES -> "tv"
            in ANIME -> "anime"
            else -> t
        }
    }
}

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
                description = s.optString("description").takeIf { it.isNotEmpty() },
                version = s.optString("version").takeIf { it.isNotEmpty() },
                author = s.optString("author").takeIf { it.isNotEmpty() },
            )
        }
        return NuvioProviderManifest(o.optString("name", "Nuvio"), scrapers)
    }

    /** Whether [scraper] would be offered by the picker: enabled by the manifest, and not disabled on Android (spec §4). */
    fun isInstallable(scraper: NuvioScraperEntry): Boolean =
        scraper.enabled && "android" !in scraper.disabledPlatforms.map(String::lowercase)

    /** The scrapers Kino's picker offers: see [isInstallable]. */
    fun installable(manifest: NuvioProviderManifest): List<NuvioScraperEntry> =
        manifest.scrapers.filter(::isInstallable)
}
