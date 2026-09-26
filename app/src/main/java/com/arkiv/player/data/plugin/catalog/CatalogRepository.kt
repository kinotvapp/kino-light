package com.arkiv.player.data.plugin.catalog

import com.arkiv.player.data.plugin.writeFileAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** One place the catalog can be downloaded from; [name] only labels a failure. */
class CatalogSource(val name: String, val url: String)

enum class CatalogOrigin { FRESH, CACHE, SEED }

/** [failures]: source name to what went wrong ("HTTP 503", "UnknownHostException", "not a catalog"). */
data class CatalogResult(val catalog: PluginCatalog, val origin: CatalogOrigin, val failures: Map<String, String> = emptyMap())

fun interface CatalogProvider {
    suspend fun load(force: Boolean): CatalogResult
}

/** What THIS build can do; catalog entries that require anything else are hidden. Phase 2 adds `xuper-bridge`. */
object PluginCapabilities {
    val SUPPORTED: Set<String> = emptySet()
}

/**
 * The recommended-plugins catalog. Order of use: a fresh download (when the cache is older than [ttlMs]
 * or [load] is forced), else the last good copy, else the [seed] shipped in the APK, so the list is
 * never empty. The sources are the same idea as the activation blob's: several hosts with different DNS
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
    companion object {
        val DEFAULT_SOURCES = listOf(
            CatalogSource("jsdelivr", "https://cdn.jsdelivr.net/npm/static-asset-pack@1/catalog.json"),
            CatalogSource("unpkg", "https://unpkg.com/static-asset-pack@1/catalog.json"),
            CatalogSource("archive", "https://archive.org/download/kino-app/plugin-catalog.json"),
        )
    }

    override suspend fun load(force: Boolean): CatalogResult = withContext(Dispatchers.IO) {
        val cached = readCache()
        if (!force && cached != null && clock() - cacheFile.lastModified() < ttlMs) {
            return@withContext CatalogResult(cached, CatalogOrigin.CACHE)
        }
        val failures = LinkedHashMap<String, String>()
        for (source in sources) {
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
            writeFileAtomically(cacheFile, bytes)
            return@withContext CatalogResult(catalog, CatalogOrigin.FRESH, failures)
        }
        if (cached != null) return@withContext CatalogResult(cached, CatalogOrigin.CACHE, failures)
        val seeded = PluginCatalogParser.parse(seed(), capabilities) ?: PluginCatalog(emptyList())
        CatalogResult(seeded, CatalogOrigin.SEED, failures)
    }

    private fun readCache(): PluginCatalog? =
        if (cacheFile.exists()) runCatching { PluginCatalogParser.parse(cacheFile.readText(), capabilities) }.getOrNull() else null

    private class HttpFailure(val code: Int) : IOException("HTTP $code")

    /** The body, or null when it is bigger than [PluginCatalogParser.MAX_BYTES]. */
    private fun fetch(url: String): ByteArray? =
        client.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()).execute().use { response ->
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
