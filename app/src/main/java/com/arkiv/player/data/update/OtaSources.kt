package com.arkiv.player.data.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One place the OTA manifest (`latest.json`) can be read from; [name] is its telemetry key. A source with
 * [requiresIdentity] only counts when its manifest names this app ([OtaManifest.APP_ID] in `"app"`): no client
 * <= 0.9.49 ever read it, so it never has to serve a legacy manifest.
 */
data class OtaSource(val name: String, val manifestUrl: String, val requiresIdentity: Boolean = false)

/**
 * Where the OTA lives (from 0.9.50). The same `latest.json` (schema in [OtaManifest]) and the same APK bytes
 * are published to, in the order the app reads them:
 *
 *  1. **GitHub Releases** of the public repo `kinotvapp/kino-light`: one Release per version (tag `v<name>`)
 *     holding `latest.json`, `kino.apk` (universal) and `kino-<abi>.apk` per ABI, plus ONE rolling release,
 *     tag [GITHUB_OTA_TAG], whose `latest.json` the publish script replaces on every release. The app reads
 *     that fixed tag, never `releases/latest`: the repo also holds a demo app, and whatever GitHub marks
 *     "latest" must not decide for Kino. The manifest must also name this app (`"app"`, [OtaManifest.APP_ID]);
 *     a foreign one counts as a failed source. github.com answers a 302 to its asset CDN
 *     (`release-assets.githubusercontent.com`), which OkHttp follows (https only).
 *  2. The npm package `static-asset-pack@1` (folder `ota/`) served by **jsDelivr** and **unpkg**: the same
 *     package that already carries the credentials blob and the plugin catalog. Its manifest names the APKs
 *     at an EXACT package version (`static-asset-pack@1.0.X/ota/...`) so a CDN's cached `@1` range can never
 *     pair one release's manifest with another release's bytes.
 *  3. **archive.org** item `kino-app` (`latest.json` + the universal `kino.apk` only, fixed name: the URL
 *     the Downloader codes point to). Clients <= 0.9.49 read it first, so its manifest keeps schema 1's
 *     universal fields.
 *
 * Every APK url in a manifest must be `https` on one of these hosts ([isAllowedApkUrl]); every copy of a
 * file has the same sha256, checked after download ([ApkDownloader]).
 */
object OtaSources {
    private const val MIRROR_PACKAGE = "static-asset-pack@1"

    /** The public repo whose Releases carry the OTA (and, after the 2026-10 split, a small demo app). */
    private const val GITHUB_REPO = "kinotvapp/kino-light"

    /** The rolling GitHub release that only ever holds the current `latest.json`. */
    const val GITHUB_OTA_TAG = "ota"

    val GITHUB = OtaSource("github", "https://github.com/$GITHUB_REPO/releases/download/$GITHUB_OTA_TAG/latest.json", requiresIdentity = true)
    val ARCHIVE = OtaSource("archive", "https://archive.org/download/kino-app/latest.json")
    val JSDELIVR = OtaSource("jsdelivr", "https://cdn.jsdelivr.net/npm/$MIRROR_PACKAGE/ota/latest.json")
    val UNPKG = OtaSource("unpkg", "https://unpkg.com/$MIRROR_PACKAGE/ota/latest.json")

    /** Order of use: GitHub Releases, then the npm mirror (jsDelivr, its twin unpkg), then archive.org. */
    val DEFAULT: List<OtaSource> = listOf(GITHUB, JSDELIVR, UNPKG, ARCHIVE)

    private val HOSTS = mapOf(
        "github.com" to GITHUB.name,
        "cdn.jsdelivr.net" to JSDELIVR.name,
        "unpkg.com" to UNPKG.name,
        "archive.org" to ARCHIVE.name,
    )

    /** Per host, the only paths an APK may come from: this repo's release assets, the mirror package at an EXACT version, the `kino-app` item. */
    private val APK_PATHS = mapOf(
        "github.com" to Regex("""^/$GITHUB_REPO/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$"""),
        "cdn.jsdelivr.net" to Regex("""^/npm/static-asset-pack@\d+\.\d+\.\d+/ota/[A-Za-z0-9._-]+$"""),
        "unpkg.com" to Regex("""^/static-asset-pack@\d+\.\d+\.\d+/ota/[A-Za-z0-9._-]+$"""),
        "archive.org" to Regex("""^/download/kino-app/[A-Za-z0-9._-]+$"""),
    )

    /**
     * Only `https` on the default port of one of the four known hosts, with no credentials and no query, on that
     * host's own path ([APK_PATHS]) may be downloaded. A host alone is no authenticity control -- anyone can
     * publish an npm package or an archive.org item -- so the path pins Kino's own package / item / repo.
     */
    fun isAllowedApkUrl(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        if (!(u.isHttps && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() && u.query == null)) return false
        return APK_PATHS[u.host]?.matches(u.encodedPath) == true
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

    /** The telemetry key of [url]'s host (`github`, `jsdelivr`, `unpkg`, `archive`), or `other`. Never the URL itself. */
    fun nameOf(url: String): String = url.toHttpUrlOrNull()?.host?.let { HOSTS[it] } ?: "other"

    /** Why a fetch failed, as ONE stable word: `dns`, `tls`, `timeout`, `offline`, `io` or `error`. */
    fun reasonOf(e: Throwable): String {
        val msg = e.message.orEmpty()
        val cls = e.javaClass.simpleName.lowercase()
        return when {
            "SSL" in cls || "TLS" in cls || "CertPath" in cls || "Trust" in cls -> "tls"
            "UnknownHost" in cls || "HostUnreachable" in cls -> "dns"
            "SocketTimeout" in cls -> "timeout"
            "ConnectException" in cls || "Connection" in cls || "ECONNREFUSED" in msg -> "io"
            "No route" in cls || "Network" in cls -> "offline"
            else -> "error"
        }
    }
}