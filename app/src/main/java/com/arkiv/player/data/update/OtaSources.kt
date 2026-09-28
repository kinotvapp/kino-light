package com.arkiv.player.data.update

import com.arkiv.player.data.plugin.discovery.DiscoveryFailure
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One place the OTA manifest (`latest.json`) can be read from; [name] is its telemetry key. */
data class OtaSource(val name: String, val manifestUrl: String)

/**
 * Where the OTA lives. archive.org's `kino-app` item is the source of truth; the npm package
 * `static-asset-pack@1` served by jsDelivr and unpkg is its mirror (folder `ota/`), for the devices that
 * cannot resolve or reach archive.org (they dominate the activation telemetry). These are the SAME three
 * hosts the credentials blob, the plugin catalog and the community list already use
 * (`.claude/reglas.md` #9): no new network destination.
 *
 * A mirror manifest's `url` names the APK in the same package at an EXACT version
 * (`static-asset-pack@1.0.X/ota/kino.apk`), so a CDN's cached `@1` range can never pair one release's
 * manifest with another release's bytes. The sha256 in every manifest is the same (same bytes everywhere).
 */
object OtaSources {
    private const val MIRROR_PACKAGE = "static-asset-pack@1"

    val ARCHIVE = OtaSource("archive", "https://archive.org/download/kino-app/latest.json")
    val JSDELIVR = OtaSource("jsdelivr", "https://cdn.jsdelivr.net/npm/$MIRROR_PACKAGE/ota/latest.json")
    val UNPKG = OtaSource("unpkg", "https://unpkg.com/$MIRROR_PACKAGE/ota/latest.json")

    /** Order of use: archive.org first (source of truth), then the mirrors. */
    val DEFAULT: List<OtaSource> = listOf(ARCHIVE, JSDELIVR, UNPKG)
    val MIRRORS: List<OtaSource> = listOf(JSDELIVR, UNPKG)

    private val HOSTS = mapOf("archive.org" to ARCHIVE.name, "cdn.jsdelivr.net" to JSDELIVR.name, "unpkg.com" to UNPKG.name)

    /** Only `https` on the default port of one of the three known hosts, with no credentials, may be downloaded. */
    fun isAllowedApkUrl(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.isHttps && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() && u.host in HOSTS
    }

    private val NPM_PATH = Regex("""^https://(?:cdn\.jsdelivr\.net/npm|unpkg\.com)/static-asset-pack@(\d+\.\d+\.\d+)/(ota/[A-Za-z0-9._-]+)$""")

    /**
     * The same file on both CDNs: the mirror publishes ONE `latest.json`, served by jsDelivr AND unpkg, so its
     * `url` can only name one of them. For a `static-asset-pack@<exact version>/ota/...` url on either CDN this
     * gives `[jsDelivr copy, unpkg copy]` (identical bytes, same exact version); any other url is returned alone.
     */
    fun npmTwins(url: String): List<String> {
        val m = NPM_PATH.matchEntire(url) ?: return listOf(url)
        val (version, path) = m.destructured
        return listOf(
            "https://cdn.jsdelivr.net/npm/static-asset-pack@$version/$path",
            "https://unpkg.com/static-asset-pack@$version/$path",
        )
    }

    /** [url]'s twin on [source]'s host when there is one (a manifest read from unpkg downloads from unpkg), else [url]. */
    fun onHostOf(source: OtaSource, url: String): String =
        npmTwins(url).firstOrNull { nameOf(it) == source.name } ?: url

    /** The telemetry key of [url]'s host (`archive`, `jsdelivr`, `unpkg`), or `other`. Never the URL itself. */
    fun nameOf(url: String): String = url.toHttpUrlOrNull()?.host?.let { HOSTS[it] } ?: "other"

    /** Why a fetch failed, as ONE stable word: `dns`, `tls`, `timeout`, `offline`, `io` or `error`. */
    fun reasonOf(e: Throwable): String = DiscoveryFailure.of(e).let { if (it == DiscoveryFailure.TLS) "tls" else it }
}
