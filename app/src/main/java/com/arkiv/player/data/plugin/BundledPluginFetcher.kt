package com.arkiv.player.data.plugin

import java.io.IOException

/**
 * A [PluginFetcher] that answers Xuper's own files from the APK's assets instead of GitHub, so
 * installing it through the normal flow (`preview("kinotvapp/kino-plugin-xuper")`, consent, install)
 * works offline and through the SAME validation path as any other plugin.
 *
 * Only a URL that is EXACTLY what [PluginAddress.rawUrl] builds for the Xuper repo at `HEAD`, and
 * whose remainder is a plain file name (see [bundledFileOf]), is looked up in [assets]. Anything
 * else goes to [delegate] without [assets] ever being asked: another ref, owner or repo, a
 * sub-folder, a query, a fragment, an encoded or backslashed name. Whoever installs from those has
 * not installed Xuper, and the privileges Xuper's address carries must never be reachable through
 * a look-alike.
 *
 * [assets] returns a bundled file's bytes, or null when there is no such file (which then falls to
 * [delegate] too). It must not throw for a missing file: an exception is not caught here.
 */
class BundledPluginFetcher(
    private val assets: (file: String) -> ByteArray?,
    private val delegate: PluginFetcher,
) : PluginFetcher {

    override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
        val file = bundledFileOf(url)
        val bytes = file?.let(assets)
            ?: return delegate.fetch(url, maxBytes)
        // The same limit the network path enforces, so a bundled file cannot bypass the installer's caps.
        if (bytes.size > maxBytes) throw IOException("archivo demasiado grande")
        return bytes
    }

    /**
     * The plain file name inside the Xuper repo that [url] points at, or null when it points anywhere
     * else or the name is not a plain one. "Plain" is the manifest's own path rule
     * ([ManifestParser.isSafeRelativePath]: `[A-Za-z0-9._-]` segments, no `.`/`..` segment, no leading
     * or double `/`, no backslash, at most [ManifestParser.MAX_PATH_CHARS]) restricted to one segment,
     * because the bundled folder is flat. An allow-list, so a query, a fragment, a `%` escape or
     * anything else nobody thought of is refused by default instead of reaching the lookup.
     */
    private fun bundledFileOf(url: String): String? {
        if (!url.startsWith(XUPER_RAW_PREFIX)) return null
        val file = url.removePrefix(XUPER_RAW_PREFIX)
        return file.takeIf { '/' !in it && ManifestParser.isSafeRelativePath(it) }
    }

    private companion object {
        val XUPER_RAW_PREFIX: String = PluginAddress("kinotvapp", "kino-plugin-xuper").rawUrl("")
    }
}
