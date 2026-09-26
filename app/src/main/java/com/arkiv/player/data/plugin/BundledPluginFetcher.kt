package com.arkiv.player.data.plugin

import java.io.IOException

/**
 * A [PluginFetcher] that answers Xuper's own files from the APK's assets instead of GitHub, so
 * installing it through the normal flow (`preview("kinotvapp/kino-plugin-xuper")`, consent, install)
 * works offline and through the SAME validation path as any other plugin.
 *
 * Only a URL that is EXACTLY what [PluginAddress.rawUrl] builds for the Xuper repo at `HEAD`, whose
 * file [assets] knows, is served from here. The same file under another ref, another owner, another
 * repo or a sub-folder goes to [delegate]: whoever installs from those has not installed Xuper, and
 * the privileges Xuper's address carries must never be reachable through a look-alike.
 *
 * [assets] returns a bundled file's bytes, or null when there is no such file.
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

    /** The file name inside the Xuper repo that [url] points at, or null when it points anywhere else. */
    private fun bundledFileOf(url: String): String? {
        if (!url.startsWith(XUPER_RAW_PREFIX)) return null
        val file = url.removePrefix(XUPER_RAW_PREFIX)
        // The bare root has no file; ".." would let a lookup climb out of the bundled folder.
        return file.takeIf { it.isNotEmpty() && ".." !in it }
    }

    private companion object {
        val XUPER_RAW_PREFIX: String = PluginAddress("kinotvapp", "kino-plugin-xuper").rawUrl("")
    }
}
