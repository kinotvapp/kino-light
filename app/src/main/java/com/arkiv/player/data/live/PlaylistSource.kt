package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginLiveContract
import com.arkiv.player.data.plugin.PluginPlaylist
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One declared playlist and its disk copy.
 *
 * - Files: `<cacheDir>/live/<key>.m3u|.epg`, fresh for `refreshHours` (from the file's
 *   `lastModified`), re-downloaded on `force`. A download is streamed to `<file>.tmp` and renamed
 *   over the saved copy only once complete, so a failed or cut download keeps the good copy.
 * - [key] comes from the URL WITHOUT its query or fragment. IPTV lists carry rotating tokens there
 *   (`get.php?username=…&token=…`, signed CDN URLs); a key per token would re-download on every
 *   rotation, ignore `refreshHours` and orphan a copy per token. The current full URL is still what
 *   is downloaded.
 * - The parsed list is kept in memory while its bytes are fresh, so a second screen parses nothing.
 *   It holds at most the channel budget [entries] was given (what the provider's earlier playlists
 *   left): a smaller budget trims it in memory, a larger one parses the file again only when the
 *   list was actually cut.
 *   After a failed download the parsed list (or, first time, the stale copy) stands [RETRY_MS]
 *   before trying again; the stale copy is never parsed again while a parsed one is on hand.
 * - Parsing streams from the file on [Dispatchers.Default] against its time budget, under
 *   [parseGate] (process-wide by default): with many providers, only one playlist or guide is ever
 *   being parsed at a time, so a 192 MB heap never holds several at once.
 * - The M3U parse drops hidden/adult groups and entries [entryAllowed] refuses as it goes, so the
 *   channel cap holds only channels that can be shown.
 * - With no [cacheDir] there is nowhere to stream to: no playlist ([entries] is null).
 * - The guide is the declared `epgUrl`. With [listGuides] and none declared, it is the list's own
 *   header guides (`url-tvg` / `x-tvg-url`, [M3uResult.epgUrls]) that [entryAllowed] accepts --
 *   addresses found inside a downloaded list, so they pass the same filter as its channels --
 *   saved as `<key>.epg`, `<key>.1.epg`, ... and merged, the first guide winning a channel.
 * - After every save, `live/` is kept under [cacheBudgetBytes] ([pruneLiveDir]), then every plugin's
 *   `live/` together under [allCachesBudgetBytes] ([pruneAllLiveDirs]) when [allCachesRoot] is given.
 *
 * Not safe for concurrent [entries] calls: `PluginLiveProvider` serialises them under its
 * playlist lock. [guide] touches only the `.epg` file and may run alongside [entries]; the
 * provider makes it single-flight per playlist.
 */
internal class PlaylistSource(
    playlist: PluginPlaylist,
    private val fetcher: LivePlaylistFetcher,
    private val cacheDir: File?,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val entryAllowed: (String) -> Boolean = { true },
    private val parseGate: Semaphore = PARSE_GATE,
    private val cacheBudgetBytes: Long = MAX_LIVE_CACHE_BYTES,
    /** The folder holding EVERY plugin's data dir (`plugin-data`); null = no global ceiling ([pruneAllLiveDirs]). */
    private val allCachesRoot: File? = null,
    private val allCachesBudgetBytes: Long = MAX_ALL_LIVE_CACHES_BYTES,
    /** With no declared `epgUrl`, use the guides the list's own header names (the person's own lists; plugins declare theirs). */
    private val listGuides: Boolean = false,
) {
    /** The current declaration. A new token in its URL keeps this source (and its key and parsed list): see [adopt]. */
    @Volatile var playlist: PluginPlaylist = playlist
        private set
    val key: String = cacheKey(playlist.url)

    /** Test seam: runs between the M3U encoding sniff and the read (a file vanishing mid-parse). */
    @Volatile internal var beforeRead: (File) -> Unit = {}
    @Volatile private var parsed: M3uResult? = null
    /** Why the last list download failed when it said something the person can act on (an Xtream login problem); null after a good download. */
    @Volatile var lastFailure: XtreamException? = null
        private set
    /** The budget [parsed] was parsed or trimmed to. */
    private var parsedMax = 0
    private var parsedUntil = 0L

    private val refreshMs get() = playlist.refreshHours * 3_600_000L

    /**
     * Takes a new declaration of the same key when nothing the parse depends on changed (only the
     * URLs, headers or refresh period: a rotated token). False = the caller needs a new source.
     */
    fun adopt(next: PluginPlaylist): Boolean {
        if (cacheKey(next.url) != key || next.hideGroups != playlist.hideGroups) return false
        playlist = next
        return true
    }

    /**
     * At most [maxEntries] entries ([M3uResult.total] still counts them all). Null = never
     * downloaded and the download failed, or no [cacheDir].
     */
    suspend fun entries(force: Boolean, maxEntries: Int = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER): M3uResult? {
        if (cacheDir == null) return null
        if (!force && clock() < parsedUntil) fitted(maxEntries)?.let { return it }
        val file = fileOf("m3u")
        val state = refresh(playlist.url, file, PluginLiveContract.MAX_PLAYLIST_BYTES, force, onFailure = { lastFailure = it as? XtreamException })
        if (state == Refresh.DOWNLOADED) lastFailure = null
        if (state == Refresh.FAILED) {
            parsedUntil = clock() + RETRY_MS
            // Offline: the list already parsed is as good as the stale copy, without parsing it again.
            fitted(maxEntries)?.let { return it }
            if (!file.exists()) return parsed
        }
        val pl = playlist
        val result = try {
            parseGate.withPermit {
                withContext(Dispatchers.Default) {
                    inUse(file) {
                        val until = System.currentTimeMillis() + PluginLiveContract.PLAYLIST_PARSE_BUDGET_MS
                        M3uParser.parse(
                            file, maxEntries = maxEntries, deadline = { System.currentTimeMillis() > until },
                            hide = { isHiddenGroup(it.group, pl) }, allow = entryAllowed, beforeRead = beforeRead,
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreadable or gone (another cleanup, the disk): like a failed download, what's on hand stands.
            log("playlist ${pl.url.take(100)}: saved copy unreadable (${e.message}); ${if (parsed != null) "keeping the parsed list" else "no list"}")
            parsedUntil = clock() + RETRY_MS
            return parsed
        }
        if (result.stoppedEarly) log("playlist ${pl.url.take(100)}: parse stopped at its time budget (${result.entries.size} entries)")
        parsed = result
        parsedMax = maxEntries
        if (state != Refresh.FAILED) parsedUntil = withContext(Dispatchers.IO) { file.lastModified() } + refreshMs
        return result
    }

    /**
     * [parsed] within [max] entries, without reading the file: as it is (same budget, or a list
     * that was never cut), trimmed (a smaller budget; kept trimmed), or null when a cut list needs
     * more than it holds.
     */
    private fun fitted(max: Int): M3uResult? {
        val p = parsed ?: return null
        return when {
            max == parsedMax -> p
            max < parsedMax -> p.copy(entries = p.entries.take(max)).also { parsed = it; parsedMax = max }
            p.entries.size >= p.total && !p.stoppedEarly -> p.also { parsedMax = max }
            else -> null
        }
    }

    /** The guides [guide] reads: the declared one, else (with [listGuides]) the parsed list's own, filtered. */
    fun guideUrls(): List<String> {
        val declared = playlist.epgUrl
        if (declared.isNotEmpty()) return listOf(declared)
        if (!listGuides) return emptyList()
        return parsed?.epgUrls.orEmpty().filter(entryAllowed).take(M3uParser.MAX_LIST_EPGS)
    }

    fun hasGuide(): Boolean = guideUrls().isNotEmpty()

    /**
     * Null = no EPG, or none could be downloaded nor read from disk. A truncated guide is returned as
     * is. Several guides ([guideUrls]) are merged: the first one holding a channel keeps it.
     */
    suspend fun guide(wantedIds: Set<String>, wantedNames: Set<String>, fromMs: Long, toMs: Long, force: Boolean): XmltvGuide? {
        if (cacheDir == null) return null
        var merged: XmltvGuide? = null
        guideUrls().forEachIndexed { i, url ->
            val g = guideOf(url, fileOf(if (i == 0) "epg" else "$i.epg"), wantedIds, wantedNames, fromMs, toMs, force) ?: return@forEachIndexed
            merged = merged?.let { m ->
                XmltvGuide(
                    displayNames = g.displayNames + m.displayNames,
                    programmes = g.programmes + m.programmes,
                    truncated = m.truncated || g.truncated,
                )
            } ?: g
        }
        return merged
    }

    private suspend fun guideOf(
        url: String, file: File, wantedIds: Set<String>, wantedNames: Set<String>, fromMs: Long, toMs: Long, force: Boolean,
    ): XmltvGuide? {
        // The list's own download headers (its credentials) go to the declared guide only, never to a host the list named.
        val headers = if (url == playlist.epgUrl) playlist.headers else emptyMap()
        refresh(url, file, PluginLiveContract.MAX_EPG_BYTES, force, headers)
        if (!withContext(Dispatchers.IO) { file.exists() }) return null
        return parseGate.withPermit {
            withContext(Dispatchers.Default) {
                val until = System.currentTimeMillis() + PluginLiveContract.EPG_PARSE_BUDGET_MS
                runCatching {
                    inUse(file) { XmltvParser.open(file) }.use { input ->
                        XmltvParser.parse(input, fromMs, toMs, wantedIds, wantedNames, deadline = { System.currentTimeMillis() > until })
                    }
                }.onFailure { log("guide ${url.take(100)} unreadable: ${it.message}") }.getOrNull()
            }
        }?.also { if (it.truncated) log("guide ${url.take(100)} was cut short; using what was read") }
    }

    private fun fileOf(ext: String) = File(File(cacheDir, LIVE_DIR), "$key.$ext")

    private enum class Refresh { FRESH, DOWNLOADED, FAILED }

    /** Downloads [url] over [file] when it is missing, older than `refreshHours`, or [force]d. On FAILED the saved copy (if any) stands. */
    private suspend fun refresh(url: String, file: File, max: Long, force: Boolean, headers: Map<String, String> = playlist.headers, onFailure: (Exception) -> Unit = {}): Refresh {
        val now = clock()
        val fresh = withContext(Dispatchers.IO) { file.exists() && now - file.lastModified() < refreshMs }
        if (fresh && !force) return Refresh.FRESH
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return try {
            withContext(Dispatchers.IO) { file.parentFile?.mkdirs() }
            fetcher.fetchTo(url, headers, max, tmp)
            withContext(Dispatchers.IO) {
                tmp.setLastModified(now)
                // Rename over the saved copy (atomic on the same directory); the good copy is never deleted first.
                if (!tmp.renameTo(file)) { tmp.delete(); throw java.io.IOException("could not replace the saved copy") }
                file.setLastModified(now)
                pruneLiveDir(cacheDir!!, keepKeys = null, budgetBytes = cacheBudgetBytes, justWritten = file, log = log)
                allCachesRoot?.let { pruneAllLiveDirs(it, allCachesBudgetBytes, justWritten = file, log = log) }
            }
            Refresh.DOWNLOADED
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { tmp.delete() }
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { tmp.delete() }
            onFailure(e)
            log("playlist ${url.take(100)} not downloaded (${e.message}); ${if (file.exists()) "using the saved copy" else "no saved copy"}")
            Refresh.FAILED
        }
    }

    companion object {
        /** After a failed download, how long what's on hand stands before trying again. */
        const val RETRY_MS = 10 * 60 * 1000L
        /** All of one plugin's saved playlists and guides together. */
        const val MAX_LIVE_CACHE_BYTES = 150L * 1024 * 1024
        /** Every plugin's saved playlists and guides together. */
        const val MAX_ALL_LIVE_CACHES_BYTES = 300L * 1024 * 1024
        private const val LIVE_DIR = "live"

        /** One playlist or guide parse at a time, across every provider of the process. */
        val PARSE_GATE = Semaphore(1)

        /**
         * Files being opened or parsed right now (absolute paths): [pruneLiveDir] never deletes them.
         * Both parsers open their file twice (a sniff, then the read); once a stream is open,
         * deleting the path no longer hurts it, so a guide is held only while it is being opened.
         */
        private val inUse = HashSet<String>()

        private inline fun <T> inUse(file: File, block: () -> T): T {
            val path = file.absolutePath
            synchronized(inUse) { inUse += path }
            try { return block() } finally { synchronized(inUse) { inUse -= path } }
        }

        private fun isInUse(file: File) = synchronized(inUse) { file.absolutePath in inUse }

        /** First 8 hex of sha1(scheme://host:port/path): query and fragment (tokens) left out. */
        fun cacheKey(url: String): String {
            val u = url.toHttpUrlOrNull() ?: return sha1Hex(url).take(8)
            // Two Xtream accounts on one server are two lists: the login (not a token) is part of what a list is.
            val account = if (u.pathSegments.lastOrNull().equals(XtreamUrl.API, ignoreCase = true)) u.queryParameter("username")?.let { "?$it" }.orEmpty() else ""
            return sha1Hex("${u.scheme}://${u.host}:${u.port}${u.encodedPath}$account").take(8)
        }

        internal fun isHiddenGroup(group: String, playlist: PluginPlaylist): Boolean {
            val g = group.trim().ifEmpty { "Sin categoría" }.lowercase()
            return g in ADULT_GROUPS || g in playlist.hideGroups
        }

        /**
         * `<cacheDir>/live` housekeeping (blocking IO). With [keepKeys], every file (temp files
         * too) of another key is deleted: a playlist no longer declared. Then, while the complete
         * files add up to more than [budgetBytes], the oldest download goes first, never
         * [justWritten]; a temp file being written is never evicted for size.
         */
        fun pruneLiveDir(cacheDir: File, keepKeys: Set<String>?, budgetBytes: Long, justWritten: File? = null, log: (String) -> Unit) {
            val dir = File(cacheDir, LIVE_DIR)
            val files = dir.listFiles()?.toMutableList() ?: return
            if (keepKeys != null) {
                files.removeAll { f ->
                    (f.name.substringBefore('.') !in keepKeys && !isInUse(f)).also { orphan -> if (orphan && !f.delete()) log("live cache: ${f.name} not deleted") }
                }
            }
            val complete = files.filter { !it.name.endsWith(".tmp") && it.isFile }.sortedBy { it.lastModified() }.toMutableList()
            var total = complete.sumOf { it.length() }
            for (f in complete) {
                if (total <= budgetBytes) break
                if (f == justWritten || isInUse(f)) continue
                val size = f.length()
                if (f.delete()) { total -= size; log("live cache over ${budgetBytes / (1024 * 1024)} MB: ${f.name} evicted") }
            }
        }

        /**
         * The global ceiling over `<root>/<plugin>/live` (blocking IO). While the complete files of every
         * plugin add up to more than [budgetBytes], the least recently used plugin cache loses its files
         * first, oldest first. A cache's recency is its newest download (`lastModified`: the files are
         * never touched on read, their date is their freshness), so a plugin nobody opens stops being
         * refreshed and ages to the front. [justWritten], files in use, temp files and anything outside
         * `live/` are never evicted.
         */
        fun pruneAllLiveDirs(root: File, budgetBytes: Long, justWritten: File? = null, log: (String) -> Unit) {
            val caches = root.listFiles()?.mapNotNull { plugin ->
                File(plugin, LIVE_DIR).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.takeIf { it.isNotEmpty() }
            } ?: return
            var total = caches.sumOf { files -> files.sumOf { it.length() } }
            if (total <= budgetBytes) return
            for (files in caches.sortedBy { fs -> fs.maxOf { it.lastModified() } }) {
                for (f in files.sortedBy { it.lastModified() }) {
                    if (total <= budgetBytes) return
                    if (f == justWritten || isInUse(f)) continue
                    val size = f.length()
                    if (f.delete()) { total -= size; log("live caches over ${budgetBytes / (1024 * 1024)} MB: ${f.parentFile?.parentFile?.name}/${f.name} evicted") }
                }
            }
        }
    }
}
