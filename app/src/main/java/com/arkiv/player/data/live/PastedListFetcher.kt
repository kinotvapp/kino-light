package com.arkiv.player.data.live

import java.io.File
import java.io.IOException

/**
 * The own provider's fetcher for lists with no address: a `kino-list:<id>` url ([OwnPastedList.urlFor])
 * is answered with the stored text ([content], null while its parts are not all on this device: then it
 * fails like a failed download, so a saved good copy stands); every other url goes to [inner] unchanged.
 * Wrapped by [W3uPlaylistFetcher], so a pasted W3U is expanded with the same caps and host rule as a
 * downloaded one, its nested lists fetched by [inner] with no headers.
 */
internal class PastedListFetcher(
    private val inner: LivePlaylistFetcher,
    private val content: suspend (String) -> String?,
) : LivePlaylistFetcher {
    override suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray {
        if (!OwnPastedList.isPasted(url)) return inner.fetch(url, headers, maxBytes)
        val bytes = textOf(url).toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) throw PlaylistTooLargeException(maxBytes / (1024 * 1024))
        return bytes
    }

    override suspend fun fetchTo(url: String, headers: Map<String, String>, maxBytes: Long, into: File) {
        if (!OwnPastedList.isPasted(url)) return inner.fetchTo(url, headers, maxBytes, into)
        super.fetchTo(url, headers, maxBytes, into)
    }

    private suspend fun textOf(url: String): String =
        content(url.removePrefix(OwnPastedList.SCHEME)) ?: throw IOException("the pasted list is not complete on this device yet")
}
