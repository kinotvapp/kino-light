package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginLiveContract
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Follows the lists a W3U document links to (a group with a `url`: the "bundle of lists" Wiseplay
 * users share), and gathers every station into one list of [M3uEntry].
 *
 * Everything a downloaded list names is untrusted, so:
 * - a link must pass [urlAllowed] (for "Mis canales": a public http(s) host, never the home
 *   network or this device -- only an address the person typed may point there) and is fetched
 *   with NO headers, so nothing of the person's own list reaches another host;
 * - links are followed at most [Caps.maxDepth] levels below the root, at most [Caps.maxLinks] in
 *   all, each URL once (a cycle stops by itself), each answer within [Caps.perListBytes] and all of
 *   them within [Caps.totalBytes], each fetch within [Caps.perFetchMs] and the whole walk within
 *   [Caps.totalMs];
 * - at most [Caps.maxEntries] channels are kept.
 *
 * A linked list that fails (unreachable, too big, slow, not a list) is counted and skipped; the
 * rest still load. Linked entries take the link's group name in front of their own group.
 */
internal class W3uExpander(
    private val fetcher: LivePlaylistFetcher,
    private val urlAllowed: (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val caps: Caps = Caps(),
) {
    data class Caps(
        val maxDepth: Int = 2,
        val maxLinks: Int = 20,
        val perListBytes: Long = 5L * 1024 * 1024,
        val totalBytes: Long = 20L * 1024 * 1024,
        val perFetchMs: Long = 20_000L,
        val totalMs: Long = 60_000L,
        val maxEntries: Int = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER,
    )

    data class Expanded(
        val entries: List<M3uEntry>,
        val epgUrls: List<String>,
        val linksFollowed: Int,
        val linksFailed: Int,
        val linksRefused: Int,
    )

    suspend fun expand(root: W3uList, rootUrl: String): Expanded {
        val deadline = clock() + caps.totalMs
        // Station addresses pass the same rule as links (the provider filters them again when listing).
        val entries = ArrayList(root.entries.filter { urlAllowed(it.url) }.take(caps.maxEntries))
        val epgs = LinkedHashSet(root.epgUrls)
        val seen = hashSetOf(identity(rootUrl))
        val queue = ArrayDeque(root.links.map { it to 1 })
        var followed = 0
        var failed = 0
        var refused = 0
        var bytesLeft = caps.totalBytes
        while (queue.isNotEmpty()) {
            val (link, depth) = queue.removeFirst()
            if (!urlAllowed(link.url)) { refused++; continue }
            if (!seen.add(identity(link.url))) continue
            if (followed + failed >= caps.maxLinks || bytesLeft <= 0 || entries.size >= caps.maxEntries || clock() >= deadline) break
            val bytes = try {
                withTimeoutOrNull(caps.perFetchMs) { fetcher.fetch(link.url, emptyMap(), minOf(caps.perListBytes, bytesLeft)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (bytes == null) { failed++; continue }
            bytesLeft -= bytes.size
            val text = M3uParser.decode(bytes)
            val left = caps.maxEntries - entries.size
            val prefix: (String) -> String = { g -> if (g.isBlank()) link.group else "${link.group} · $g" }
            if (LenientJson.looksLikeJson(text)) {
                val nested = W3uParser.parse(text, maxEntries = left)
                if (nested == null) { failed++; continue }
                entries += nested.entries.filter { urlAllowed(it.url) }.map { it.copy(group = prefix(it.group)) }
                epgs += nested.epgUrls
                if (depth < caps.maxDepth) nested.links.forEach { queue.addLast(it.copy(group = prefix(it.group)) to depth + 1) }
            } else {
                val nested = M3uParser.parse(text, maxEntries = left)
                if (nested.total == 0) { failed++; continue }
                entries += nested.entries.filter { urlAllowed(it.url) }.map { it.copy(group = prefix(it.group)) }
                epgs += nested.epgUrls
            }
            followed++
        }
        return Expanded(entries, epgs.take(M3uParser.MAX_LIST_EPGS), followed, failed, refused)
    }

    /** One identity per list for cycle detection: scheme, host and port normalised, the fragment dropped. */
    private fun identity(url: String): String =
        url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: url.substringBefore('#')
}

/** Writes entries back as an extended M3U that [M3uParser] reads to the same entries (what a W3U list is saved as). */
internal object M3uWriter {
    fun write(entries: List<M3uEntry>, epgUrls: List<String>, out: Appendable) {
        out.append("#EXTM3U")
        if (epgUrls.isNotEmpty()) out.append(" url-tvg=\"").append(attr(epgUrls.joinToString(","))).append('"')
        out.append('\n')
        for (e in entries) {
            out.append("#EXTINF:-1")
            if (e.tvgId.isNotEmpty()) out.append(" tvg-id=\"").append(attr(e.tvgId)).append('"')
            if (e.tvgName.isNotEmpty()) out.append(" tvg-name=\"").append(attr(e.tvgName)).append('"')
            if (e.logo.isNotEmpty()) out.append(" tvg-logo=\"").append(attr(e.logo)).append('"')
            if (e.tvgShiftMin != 0) out.append(" tvg-shift=\"").append((e.tvgShiftMin / 60.0).toString().removeSuffix(".0")).append('"')
            if (e.number > 0) out.append(" tvg-chno=\"").append(e.number.toString()).append('"')
            if (e.group.isNotEmpty()) out.append(" group-title=\"").append(attr(e.group)).append('"')
            out.append(',').append(line(e.name)).append('\n')
            if (e.headers.isNotEmpty()) out.append("#EXTHTTP:").append(org.json.JSONObject(e.headers as Map<*, *>).toString()).append('\n')
            if (e.drmKey.isNotEmpty()) {
                out.append("#KODIPROP:inputstream.adaptive.license_type=clearkey\n")
                out.append("#KODIPROP:inputstream.adaptive.license_key=").append(e.drmKeyId).append(':').append(e.drmKey).append('\n')
            }
            out.append(line(e.url)).append('\n')
        }
    }

    /** No quote can end an attribute early and no line break can start a new line. */
    private fun attr(s: String) = line(s).replace('"', '\'')

    private fun line(s: String) = s.map { if (Character.isISOControl(it)) ' ' else it }.joinToString("")
}

/**
 * The own provider's download path: a download that turns out to be a W3U list (JSON, by its first
 * character, never by its extension) is expanded ([W3uExpander]) and saved as M3U, so everything
 * after the download -- the streamed parse, grouping, codes, caps, guides -- is the M3U path
 * unchanged. Anything else (an M3U, a guide) is saved as downloaded. JSON that is not a W3U list,
 * or a W3U over [maxW3uBytes] (it is parsed in memory), fails like a bad download, so a saved good
 * copy stands.
 */
internal class W3uPlaylistFetcher(
    private val inner: LivePlaylistFetcher,
    private val urlAllowed: (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxW3uBytes: Long = MAX_W3U_BYTES,
) : LivePlaylistFetcher {
    override suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray = inner.fetch(url, headers, maxBytes)

    override suspend fun fetchTo(url: String, headers: Map<String, String>, maxBytes: Long, into: File) {
        inner.fetchTo(url, headers, maxBytes, into)
        try {
            val json = withContext(Dispatchers.IO) { startsWithJson(into) }
            if (!json) return
            if (withContext(Dispatchers.IO) { into.length() } > maxW3uBytes) throw PlaylistTooLargeException(maxW3uBytes / (1024 * 1024))
            val text = withContext(Dispatchers.IO) { M3uParser.decode(into.readBytes()) }
            val list = withContext(Dispatchers.Default) { W3uParser.parse(text) } ?: throw IOException("JSON that is not a W3U list")
            val expanded = W3uExpander(inner, urlAllowed, clock).expand(list, url)
            withContext(Dispatchers.IO) { into.bufferedWriter().use { M3uWriter.write(expanded.entries, expanded.epgUrls, it) } }
        } catch (e: Throwable) {
            into.delete()
            throw e
        }
    }

    /** The first visible character (after a UTF-8 BOM and whitespace) of [file] is `{` or `[`. */
    private fun startsWithJson(file: File): Boolean {
        file.inputStream().buffered().use { input ->
            var b = input.read()
            if (b == 0xEF) { input.read(); input.read(); b = input.read() }
            while (b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code) b = input.read()
            return b == '{'.code || b == '['.code
        }
    }

    companion object {
        /** A W3U list is parsed in memory: kept well under the M3U cap for a 192 MB heap. */
        const val MAX_W3U_BYTES = 8L * 1024 * 1024
    }
}
