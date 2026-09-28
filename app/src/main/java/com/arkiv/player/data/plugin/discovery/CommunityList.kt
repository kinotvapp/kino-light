package com.arkiv.player.data.plugin.discovery

import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.catalog.PluginCatalogParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What the fallback community list answered. [repos] is null when no source gave a usable list;
 * [serverDateMs] is the `Date` header of any source that answered at all (even with an error), used only
 * to tell whether the device clock is off when the GitHub search failed on TLS.
 */
class CommunityFallbackAnswer(val repos: List<DiscoveredRepo>?, val serverDateMs: Long?)

/** The community list to show when the GitHub search cannot answer (see [PluginDiscovery]). Never throws but for cancellation. */
fun interface CommunityFallback {
    suspend fun load(): CommunityFallbackAnswer
}

/**
 * Reads the static community list: `{"schema":1,"plugins":[{"owner","repo","stars"}]}`, published next
 * to the recommended catalog. Only repos: each one's `kino-plugin.json` is still read from GitHub and
 * checked exactly like a search result. It is DATA from the network, held to the catalog's rules: a body
 * over [PluginCatalogParser.MAX_BYTES], not strict JSON, nested too deep or of another schema is refused
 * whole (null); an entry whose `owner/repo` is not a plain repo address [PluginAddress] accepts is skipped;
 * at most [PluginCatalogParser.MAX_ENTRIES] entries are read and [DiscoveryRules.MAX_RESULTS] kept.
 */
object CommunityListParser {
    const val SUPPORTED_SCHEMA = 1

    fun parse(json: String): List<DiscoveredRepo>? {
        if (json.toByteArray(Charsets.UTF_8).size > PluginCatalogParser.MAX_BYTES) return null
        if (!PluginCatalogParser.isSafeToParse(json)) return null
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        if (root.optInt("schema", 0) != SUPPORTED_SCHEMA) return null
        val array = root.optJSONArray("plugins") ?: return null
        val seen = HashSet<String>()
        val out = ArrayList<DiscoveredRepo>()
        for (i in 0 until minOf(array.length(), PluginCatalogParser.MAX_ENTRIES)) {
            if (out.size == DiscoveryRules.MAX_RESULTS) break
            val repo = array.optJSONObject(i)?.let(::repoOf) ?: continue
            if (seen.add(repo.key)) out += repo
        }
        return out
    }

    private fun repoOf(o: JSONObject): DiscoveredRepo? {
        val owner = o.opt("owner") as? String ?: return null
        val name = o.opt("repo") as? String ?: return null
        val fullName = "$owner/$name"
        if (!PluginCatalogParser.isValidRepo(fullName)) return null
        val address = PluginAddress.parse(fullName) ?: return null
        if (address.path.isNotEmpty() || address.ref != PluginAddress.HEAD) return null
        val stars = (o.opt("stars") as? Int)?.coerceAtLeast(0) ?: 0
        return DiscoveredRepo(address.owner, address.repo, stars)
    }
}

/** One place the community list can be downloaded from; [name] only labels it. */
class CommunityListSource(val name: String, val url: String)

/**
 * Downloads the community list from the same three hosts, in the same order and with the same client
 * rules as the recommended catalog (`CatalogRepository`): jsDelivr and unpkg serving the npm package
 * `static-asset-pack@1`, then archive.org's `kino-app` item. No new destination: `.claude/reglas.md` #9.
 * Redirects are followed (all three answer 302), each call is bounded, and the first source whose body
 * parses wins.
 */
class CommunityListRepository(
    client: OkHttpClient,
    private val sources: List<CommunityListSource> = DEFAULT_SOURCES,
) : CommunityFallback {
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val CALL_TIMEOUT_SECONDS = 15L
        val DEFAULT_SOURCES = listOf(
            CommunityListSource("jsdelivr", "https://cdn.jsdelivr.net/npm/static-asset-pack@1/community.json"),
            CommunityListSource("unpkg", "https://unpkg.com/static-asset-pack@1/community.json"),
            CommunityListSource("archive", "https://archive.org/download/kino-app/plugin-community.json"),
        )
    }

    override suspend fun load(): CommunityFallbackAnswer = withContext(Dispatchers.IO) {
        var serverDate: Long? = null
        for (source in sources) {
            ensureActive()
            val body = try {
                fetch(source.url) { date -> if (serverDate == null) serverDate = date }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                null
            } ?: continue
            val repos = CommunityListParser.parse(body.toString(Charsets.UTF_8)) ?: continue
            return@withContext CommunityFallbackAnswer(repos, serverDate)
        }
        CommunityFallbackAnswer(null, serverDate)
    }

    /** The body of a 2xx, or null (error status, or bigger than [PluginCatalogParser.MAX_BYTES]); [onDate] sees any `Date` header. */
    private fun fetch(url: String, onDate: (Long) -> Unit): ByteArray? =
        http.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()).execute().use { response ->
            response.headers.getDate("Date")?.let { onDate(it.time) }
            if (!response.isSuccessful) return@use null
            val input = response.body?.byteStream() ?: return@use null
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > PluginCatalogParser.MAX_BYTES) return@use null
            }
            out.toByteArray()
        }
}
