package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Anime mapping dataset (Fribb/anime-lists). Downloaded to disk (weekly TTL) and parsed into an
 * in-memory map on demand. If the refresh fails, what's cached is served; if there's nothing,
 * returns null (the resolver falls back to heuristics).
 */
class AnimeMappingRepository(
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val file get() = File(cacheDir, CACHE_FILE_NAME)
    private val tmpFile get() = File(cacheDir, "$CACHE_FILE_NAME.tmp")
    private val mutex = Mutex()
    @Volatile private var cache: Map<Long, AnimeMapping>? = null
    @Volatile private var lastAttemptMs = 0L

    suspend fun mappingFor(anilistId: Long): AnimeMapping? = ensureLoaded()[anilistId]

    private suspend fun ensureLoaded(): Map<Long, AnimeMapping> = mutex.withLock {
        cache?.let { return it }
        withContext(Dispatchers.IO) {
            val existing = readExisting()
            val existingValid = existing.isNotEmpty()
            // A file that exists but parses empty (corrupted/truncated) does NOT count as fresh:
            // a re-download is forced, always respecting the anti-hammering gate below.
            val fresh = existingValid && isFresh(file.lastModified(), System.currentTimeMillis())

            val parsed = if (fresh) {
                existing
            } else {
                val now = System.currentTimeMillis()
                val gated = !existingValid && (now - lastAttemptMs < RETRY_GATE_MS)
                if (gated) {
                    existing   // best-effort; falls back to heuristics without hammering the network
                } else {
                    lastAttemptMs = now
                    download() ?: existing
                }
            }
            parsed.also { if (it.isNotEmpty()) cache = it }
        }
    }

    private fun readExisting(): Map<Long, AnimeMapping> =
        runCatching { if (file.exists()) file.readText() else null }
            .getOrNull()
            ?.let { FribbAnimeListParser.parse(it) }
            .orEmpty()

    /**
     * Downloads to a temporary file, checks that it parses into a non-empty map and only then
     * atomically promotes it to the final file. If anything fails or the result is invalid/empty,
     * the existing good file is NOT touched (nor its lastModified) and null is returned.
     */
    private fun download(): Map<Long, AnimeMapping>? {
        val tmp = tmpFile
        val parsed = runCatching {
            val body = client.newCall(Request.Builder().url(DATASET_URL).build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@runCatching null
            cacheDir.mkdirs()
            tmp.writeText(body)
            FribbAnimeListParser.parse(tmp.readText()).takeIf { it.isNotEmpty() }
        }.getOrNull()

        if (parsed == null) {
            runCatching { tmp.delete() }
            return null
        }

        val renamed = runCatching { tmp.renameTo(file) }.getOrDefault(false)
        if (!renamed) {
            runCatching {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                file.writeBytes(tmp.readBytes())
                tmp.delete()
            }
        }
        return parsed
    }

    companion object {
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json"
        private const val CACHE_FILE_NAME = "anime-list-full.json"
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** Anti-hammering gate: with no valid file, don't retry the download more than once every 5 min. */
        private const val RETRY_GATE_MS = 5L * 60 * 1000

        /** Is the cache downloaded at [fetchedAtMs] still valid at [nowMs]? (0 = never downloaded). */
        fun isFresh(fetchedAtMs: Long, nowMs: Long): Boolean =
            fetchedAtMs > 0 && nowMs - fetchedAtMs < TTL_MS
    }
}
