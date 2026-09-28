package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginLiveContract
import com.arkiv.player.data.plugin.PluginPlaylist
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One declared playlist: its bytes on disk (`<cacheDir>/live/<key>.m3u|.epg`), fresh for
 * `refreshHours` (from the file's `lastModified`), re-downloaded on `force`, and the stale copy
 * kept for when the network fails. A 20 MB IPTV list must not be downloaded on every screen, nor
 * lost on a bad connection. The parsed list is also kept in memory while its bytes are fresh, so a
 * screen asking again parses nothing; after a failed download it is kept [RETRY_MS] before trying
 * again. Parsing runs on [Dispatchers.Default] against its time budget; disk IO on [Dispatchers.IO].
 *
 * Not thread-safe: `PluginLiveProvider` serialises every call.
 */
internal class PlaylistSource(
    val playlist: PluginPlaylist,
    private val fetcher: LivePlaylistFetcher,
    private val cacheDir: File?,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) {
    val key: String = sha1Hex(playlist.url).take(8)

    private val refreshMs = playlist.refreshHours * 3_600_000L
    private var parsed: M3uResult? = null
    private var parsedUntil = 0L

    /** Bytes and the time they stop being worth reusing. */
    private class Loaded(val bytes: ByteArray, val until: Long)

    /** Null = never downloaded and the download failed. */
    suspend fun entries(force: Boolean): M3uResult? {
        if (!force) parsed?.takeIf { clock() < parsedUntil }?.let { return it }
        val loaded = load(playlist.url, "m3u", PluginLiveContract.MAX_PLAYLIST_BYTES, force)
        if (loaded == null) {
            parsedUntil = clock() + RETRY_MS
            return parsed
        }
        val result = withContext(Dispatchers.Default) {
            val until = System.currentTimeMillis() + PluginLiveContract.PLAYLIST_PARSE_BUDGET_MS
            M3uParser.parse(M3uParser.decode(loaded.bytes), deadline = { System.currentTimeMillis() > until })
        }
        if (result.stoppedEarly) log("playlist ${playlist.url.take(100)}: parse stopped at its time budget (${result.entries.size} entries)")
        parsed = result
        parsedUntil = loaded.until
        return result
    }

    /** Null = no EPG, or it could not be downloaded nor read from disk. A truncated guide is returned as is. */
    suspend fun guide(wantedIds: Set<String>, wantedNames: Set<String>, fromMs: Long, toMs: Long, force: Boolean): XmltvGuide? {
        if (playlist.epgUrl.isEmpty()) return null
        val loaded = load(playlist.epgUrl, "epg", PluginLiveContract.MAX_EPG_BYTES, force) ?: return null
        return withContext(Dispatchers.Default) {
            val until = System.currentTimeMillis() + PluginLiveContract.EPG_PARSE_BUDGET_MS
            runCatching {
                XmltvParser.parse(XmltvParser.open(loaded.bytes), fromMs, toMs, wantedIds, wantedNames, deadline = { System.currentTimeMillis() > until })
            }.onFailure { log("guide ${playlist.epgUrl.take(100)} unreadable: ${it.message}") }.getOrNull()
        }?.also { if (it.truncated) log("guide ${playlist.epgUrl.take(100)} was cut short; using what was read") }
    }

    private suspend fun load(url: String, ext: String, max: Long, force: Boolean): Loaded? = withContext(Dispatchers.IO) {
        val file = cacheDir?.let { File(File(it, "live"), "$key.$ext") }
        val now = clock()
        if (!force && file != null && file.exists() && now - file.lastModified() < refreshMs) {
            return@withContext Loaded(file.readBytes(), file.lastModified() + refreshMs)
        }
        try {
            val bytes = fetcher.fetch(url, playlist.headers, max)
            file?.let { save(it, bytes, now) }
            Loaded(bytes, now + refreshMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val stale = file?.takeIf { it.exists() }
            log("playlist ${url.take(100)} not downloaded (${e.message}); ${if (stale != null) "using the saved copy" else "no saved copy"}")
            stale?.let { Loaded(it.readBytes(), now + RETRY_MS) }
        }
    }

    /** Written aside and renamed, so a crash mid-write never leaves half a list as the saved copy. */
    private fun save(file: File, bytes: ByteArray, now: Long) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            tmp.setLastModified(now)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            file.setLastModified(now)
        }.onFailure { log("playlist cache not written: ${it.message}") }
    }

    companion object {
        /** After a failed download, how long the stale copy (or nothing) stands before trying again. */
        const val RETRY_MS = 10 * 60 * 1000L
    }
}
