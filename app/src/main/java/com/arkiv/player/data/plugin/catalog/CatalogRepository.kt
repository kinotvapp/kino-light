package com.arkiv.player.data.plugin.catalog

import com.arkiv.player.data.plugin.writeFileAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One place the catalog can be downloaded from; [name] only labels a failure. */
class CatalogSource(val name: String, val url: String)

enum class CatalogOrigin { FRESH, CACHE, SEED }

/**
 * [failures]: source name to what went wrong ("HTTP 503", "UnknownHostException", "not a catalog",
 * "empty catalog"). Local storage problems use the keys `cache` (the good download could not be saved)
 * and `seed` (the APK asset could not be read).
 */
data class CatalogResult(val catalog: PluginCatalog, val origin: CatalogOrigin, val failures: Map<String, String> = emptyMap())

interface CatalogProvider {
    /** The catalog as [CatalogRepository] resolves it: may download, so it can take seconds. */
    suspend fun load(force: Boolean): CatalogResult

    /**
     * What is on the device right now, without touching the network: the last good download (whatever its
     * age) when it parses and has entries, else the copy shipped in the APK. Never throws, so a screen can
     * paint a list on its first frame and let [load] refresh it in the background. Reads two small local
     * files; origin is [CatalogOrigin.CACHE] or [CatalogOrigin.SEED].
     */
    fun cachedOrSeed(): CatalogResult
}

/** What THIS build can do; catalog entries that require anything else are hidden. Phase 2 adds `xuper-bridge`. */
object PluginCapabilities {
    val SUPPORTED: Set<String> = emptySet()
}

/**
 * The recommended-plugins catalog. Order of use: a fresh download (when the cache is older than [ttlMs]
 * or [load] is forced), else the last good copy, else the [seed] shipped in the APK, so the list is
 * never empty: an empty catalog (no entries, or every entry invalid or hidden by [capabilities]) counts as a
 * failure wherever it comes from. The sources are the same idea as the activation blob's: several hosts with different DNS
 * names and CDNs (jsDelivr, unpkg, archive.org), because a device that cannot resolve one often can
 * resolve another.
 */
class CatalogRepository(
    private val client: OkHttpClient,
    private val sources: List<CatalogSource> = DEFAULT_SOURCES,
    private val cacheFile: File,
    private val seed: () -> String,
    private val capabilities: Set<String> = PluginCapabilities.SUPPORTED,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 6 * 60 * 60 * 1000L,
) : CatalogProvider {
    /**
     * Own client, built once: redirects are followed whatever the injected [client] says (jsDelivr, unpkg and
     * archive.org answer 302), and a whole call, connect to last byte, is bounded so a slow source cannot
     * stall the chain.
     */
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val CALL_TIMEOUT_SECONDS = 15L
        val DEFAULT_SOURCES = listOf(
            CatalogSource("jsdelivr", "https://cdn.jsdelivr.net/npm/static-asset-pack@1/catalog.json"),
            CatalogSource("unpkg", "https://unpkg.com/static-asset-pack@1/catalog.json"),
            CatalogSource("archive", "https://archive.org/download/kino-app/plugin-catalog.json"),
        )
    }

    override suspend fun load(force: Boolean): CatalogResult = withContext(Dispatchers.IO) {
        val cached = readCache()
        // A cache dated in the future (the clock moved backwards) is stale, never fresh forever.
        val age = clock() - cacheFile.lastModified()
        if (!force && cached != null && age in 0L until ttlMs) {
            return@withContext CatalogResult(cached, CatalogOrigin.CACHE)
        }
        val failures = LinkedHashMap<String, String>()
        for (source in sources) {
            ensureActive()
            val bytes = try {
                fetch(source.url) ?: run { failures[source.name] = "too big"; null }
            } catch (e: HttpFailure) {
                failures[source.name] = "HTTP ${e.code}"; null
            } catch (e: IOException) {
                failures[source.name] = e.javaClass.simpleName; null
            } ?: continue
            val text = bytes.toString(Charsets.UTF_8)
            val catalog = PluginCatalogParser.parse(text, capabilities)
            if (catalog == null) { failures[source.name] = "not a catalog"; continue }
            if (catalog.entries.isEmpty()) { failures[source.name] = "empty catalog"; continue }
            ensureActive()
            // A full or read-only disk must not lose a good download: it is still returned, just not kept.
            try {
                writeFileAtomically(cacheFile, bytes)
            } catch (e: IOException) {
                failures["cache"] = e.javaClass.simpleName
            }
            return@withContext CatalogResult(catalog, CatalogOrigin.FRESH, failures)
        }
        if (cached != null) return@withContext CatalogResult(cached, CatalogOrigin.CACHE, failures)
        CatalogResult(readSeed(failures), CatalogOrigin.SEED, failures)
    }

    override fun cachedOrSeed(): CatalogResult {
        readCache()?.let { return CatalogResult(it, CatalogOrigin.CACHE) }
        val failures = LinkedHashMap<String, String>()
        return CatalogResult(readSeed(failures), CatalogOrigin.SEED, failures)
    }

    /** The APK's copy; an unreadable or invalid one is an empty catalog, with `seed` in [failures] when it could not be read. */
    private fun readSeed(failures: MutableMap<String, String>): PluginCatalog =
        try {
            PluginCatalogParser.parse(seed(), capabilities) ?: PluginCatalog(emptyList())
        } catch (e: IOException) {
            failures["seed"] = "unreadable"
            PluginCatalog(emptyList())
        }

    private fun readCache(): PluginCatalog? =
        if (cacheFile.exists()) {
            runCatching { PluginCatalogParser.parse(cacheFile.readText(), capabilities) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }
        } else {
            null
        }

    private class HttpFailure(val code: Int) : IOException("HTTP $code")

    /** The body, or null when it is bigger than [PluginCatalogParser.MAX_BYTES]. */
    private fun fetch(url: String): ByteArray? =
        http.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val input = response.body?.byteStream() ?: return@use ByteArray(0)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > PluginCatalogParser.MAX_BYTES) return@use null
            }
            out.toByteArray()
        }
}
